package vn.lanlab;

import static vn.lanlab.Db.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class Auth {
  public record Principal(long id, String role, Instant expires) {
    public boolean teacher() {
      return role.equals("teacher");
    }
  }

  private final Db db;
  private final Config cfg;
  private final ObjectMapper json;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
  private final SecureRandom random = new SecureRandom();
  private final String dummy = bcrypt.encode("dummy-password");
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  public Auth(Db db, Config cfg, ObjectMapper json) {
    this.db = db;
    this.cfg = cfg;
    this.json = json;
  }

  public static String code(String s) {
    return Text.upper(Text.strip(s));
  }

  public String hash(String password) {
    int n = password.getBytes(StandardCharsets.UTF_8).length;
    if (n < 1 || n > 72) throw ApiError.bad("password must be 1-72 bytes");
    return bcrypt.encode(password);
  }

  private byte[] sign(String s) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(
          new SecretKeySpec(cfg.get("JWT_SECRET").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return m.doFinal(s.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private String encode(Object value) {
    try {
      return B64.encodeToString(json.writeValueAsBytes(value));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] canonical(String s) {
    byte[] bytes = Base64.getUrlDecoder().decode(s);
    if (!B64.encodeToString(bytes).equals(s)) throw new IllegalArgumentException();
    return bytes;
  }

  public Principal verify(String token) {
    try {
      String[] p = token.split("\\.", -1);
      if (p.length != 3) throw new IllegalArgumentException();
      var header = json.readTree(canonical(p[0]));
      if (!"HS256".equals(header.path("alg").asText())) throw new IllegalArgumentException();
      if (!MessageDigest.isEqual(sign(p[0] + "." + p[1]), canonical(p[2])))
        throw new IllegalArgumentException();
      var claims = json.readTree(canonical(p[1]));
      long id = Long.parseLong(claims.path("sub").asText());
      String role = claims.path("role").asText();
      Instant expiry = Instant.ofEpochSecond(claims.path("exp").asLong(0));
      if (id < 1
          || !List.of("teacher", "student").contains(role)
          || !Instant.now().isBefore(expiry)
          || (claims.has("nbf") && Instant.now().getEpochSecond() < claims.path("nbf").asLong()))
        throw new IllegalArgumentException();
      return new Principal(id, role, expiry);
    } catch (Exception e) {
      throw new ApiError(401, "invalid access token");
    }
  }

  public Map<String, Object> user(long id) {
    var account = db.one("SELECT * FROM users WHERE id=$1", id);
    if (account == null) throw new ApiError(401, "account not found");
    return only(account, "id", "role", "code", "fullname");
  }

  private byte[] digest(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] refreshHash(String s) {
    try {
      if (canonical(s).length != 32) throw new IllegalArgumentException();
      return digest(s);
    } catch (Exception e) {
      throw new ApiError(401, "invalid or expired refresh token");
    }
  }

  private String newRefresh() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return B64.encodeToString(b);
  }

  private Map<String, Object> tokens(
      Map<String, Object> account, String refresh, Instant now, Instant refreshExpiry) {
    Instant exp = now.plus(cfg.duration("ACCESS_TOKEN_TTL", "15m"));
    String payload =
        encode(map("alg", "HS256", "typ", "JWT"))
            + "."
            + encode(
                map(
                    "sub",
                    "" + num(account, "id"),
                    "role",
                    account.get("role"),
                    "iat",
                    now.getEpochSecond(),
                    "exp",
                    exp.getEpochSecond()));
    return map(
        "access_token",
        payload + "." + B64.encodeToString(sign(payload)),
        "access_expires_at",
        exp,
        "refresh_token",
        refresh,
        "refresh_expires_at",
        refreshExpiry,
        "user",
        only(account, "id", "role", "code", "fullname"));
  }

  public Map<String, Object> login(String rawCode, String password) {
    String code = code(rawCode);
    if (code.isEmpty() || password.isEmpty()) throw ApiError.bad("code and password are required");
    var acct = db.one("SELECT * FROM users WHERE code=$1", code);
    if (!bcrypt.matches(password, acct == null ? dummy : str(acct, "password_hash"))
        || acct == null) throw new ApiError(401, "invalid code or password");
    Instant now = Instant.now(), exp = now.plus(cfg.duration("REFRESH_TOKEN_TTL", "168h"));
    String refresh = newRefresh();
    db.tx(
        () -> {
          var locked =
              db.need("account", "SELECT * FROM users WHERE id=$1 FOR UPDATE", num(acct, "id"));
          if (!Objects.equals(locked.get("password_hash"), acct.get("password_hash")))
            throw new ApiError(401, "invalid code or password");
          db.update(
              "INSERT INTO refresh_tokens(user_id,token_hash,expires_at) VALUES($1,$2,$3)",
              num(acct, "id"),
              digest(refresh),
              exp);
          return null;
        });
    return tokens(acct, refresh, now, exp);
  }

  public Map<String, Object> refresh(String old) {
    byte[] oldHash = refreshHash(old);
    String next = newRefresh();
    Instant now = Instant.now(), expiry = now.plus(cfg.duration("REFRESH_TOKEN_TTL", "168h"));
    var account =
        db.tx(
            () -> {
              var owner = db.one("SELECT user_id FROM refresh_tokens WHERE token_hash=$1", oldHash);
              if (owner == null) throw new ApiError(401, "invalid or expired refresh token");
              var acct =
                  db.need(
                      "account",
                      "SELECT * FROM users WHERE id=$1 FOR UPDATE",
                      num(owner, "user_id"));
              var token =
                  db.one(
                      "SELECT * FROM refresh_tokens WHERE token_hash=$1 AND user_id=$2 FOR UPDATE",
                      oldHash,
                      num(acct, "id"));
              if (token == null) throw new ApiError(401, "invalid or expired refresh token");
              db.update("DELETE FROM refresh_tokens WHERE id=$1", num(token, "id"));
              if (!((Instant) token.get("expires_at")).isAfter(now)) return null;
              db.update(
                  "INSERT INTO refresh_tokens(user_id,token_hash,expires_at) VALUES($1,$2,$3)",
                  num(acct, "id"),
                  digest(next),
                  expiry);
              return acct;
            });
    if (account == null) throw new ApiError(401, "invalid or expired refresh token");
    return tokens(account, next, now, expiry);
  }

  public void logout(String token) {
    byte[] h;
    try {
      h = refreshHash(token);
    } catch (ApiError e) {
      return;
    }
    db.update("DELETE FROM refresh_tokens WHERE token_hash=$1", h);
  }

  public void changePassword(Principal p, String current, String next) {
    String hash = hash(next);
    var acct = db.need("account", "SELECT * FROM users WHERE id=$1", p.id);
    if (!bcrypt.matches(current, str(acct, "password_hash")))
      throw new ApiError(401, "invalid code or password");
    db.tx(
        () -> {
          var locked = db.need("account", "SELECT * FROM users WHERE id=$1 FOR UPDATE", p.id);
          if (!Objects.equals(locked.get("password_hash"), acct.get("password_hash")))
            throw new ApiError(401, "invalid code or password");
          db.update(
              "UPDATE users SET password_hash=$1,updated_at=CURRENT_TIMESTAMP WHERE id=$2",
              hash,
              p.id);
          db.update("DELETE FROM refresh_tokens WHERE user_id=$1", p.id);
          return null;
        });
  }
}
