package vn.lanlab;

import static vn.lanlab.Db.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.apache.commons.csv.*;

public final class Students {
  private final Db db;
  private final Auth auth;
  private static final String COLS = "id,code,fullname,date_of_birth,created_at,updated_at";

  public Students(Db db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  public static LocalDate birth(String raw) {
    String s = raw.strip();
    if (s.isEmpty()) throw ApiError.bad("date_of_birth is required");
    for (String pattern : List.of("d/M/uuuu", "d-M-uuuu", "d.M.uuuu", "uuuu-M-d")) {
      try {
        LocalDate d =
            LocalDate.parse(
                s, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT));
        if (d.getYear() < 1900 || d.isAfter(LocalDate.now(ZoneOffset.UTC)))
          throw ApiError.bad("date_of_birth out of range");
        return d;
      } catch (DateTimeParseException ignored) {
      }
    }
    throw ApiError.bad("date_of_birth must be dd/mm/yyyy or yyyy-mm-dd");
  }

  static Map<String, Object> validate(String code, String name, String dob) {
    code = Auth.code(code);
    name = Text.strip(name).replaceAll("(?U)\\s+", " ");
    if (code.isEmpty()
        || code.codePointCount(0, code.length()) > 50
        || code.codePoints().anyMatch(c -> Text.space(c) || Character.isISOControl(c)))
      throw ApiError.bad("code must be 1-50 characters without spaces");
    if (name.isEmpty() || name.codePointCount(0, name.length()) > 200)
      throw ApiError.bad("fullname must be 1-200 characters");
    return map("code", code, "fullname", name, "date_of_birth", birth(dob));
  }

  private Map<String, Object> insert(Map<String, Object> s) {
    return db.one(
        "INSERT INTO users(code,fullname,role,password_hash,date_of_birth)"
            + " VALUES($1,$2,'student',$3,$4) ON CONFLICT(code) DO NOTHING RETURNING "
            + COLS,
        s.get("code"),
        s.get("fullname"),
        s.get("password_hash"),
        s.get("date_of_birth"));
  }

  private void password(Map<String, Object> s) {
    s.put(
        "password_hash",
        auth.hash(
            ((LocalDate) s.get("date_of_birth")).format(DateTimeFormatter.ofPattern("ddMMuuuu"))));
  }

  public Map<String, Object> create(String code, String name, String dob) {
    var s = validate(code, name, dob);
    password(s);
    var saved = insert(s);
    if (saved == null) throw ApiError.conflict("code already used by another account");
    return saved;
  }

  public List<Map<String, Object>> list(String query) {
    String pattern =
        "%" + query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    return db.rows(
        "SELECT "
            + COLS
            + " FROM users WHERE role='student' AND (code ILIKE $1 OR fullname ILIKE $1) ORDER BY"
            + " code,id",
        pattern);
  }

  public void reset(long id) {
    var s = db.need("student", "SELECT " + COLS + " FROM users WHERE id=$1 AND role='student'", id);
    String password =
        s.get("date_of_birth") instanceof LocalDate d
            ? d.format(DateTimeFormatter.ofPattern("ddMMuuuu"))
            : str(s, "code");
    String hashed = auth.hash(password);
    db.tx(
        () -> {
          db.need("student", "SELECT id FROM users WHERE id=$1 AND role='student' FOR UPDATE", id);
          db.update(
              "UPDATE users SET password_hash=$1,updated_at=CURRENT_TIMESTAMP WHERE id=$2",
              hashed,
              id);
          db.update("DELETE FROM refresh_tokens WHERE user_id=$1", id);
          return null;
        });
  }

  public Map<String, Object> importCsv(byte[] file) {
    if (file.length > 1 << 20) throw new ApiError(413, "CSV exceeds 1 MiB");
    List<Map<String, Object>> rows = new ArrayList<>(), errors = new ArrayList<>();
    Map<String, Integer> seen = new HashMap<>();
    String text = new String(file, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
    try (CSVParser parser =
        CSVFormat.DEFAULT
            .builder()
            .setIgnoreSurroundingSpaces(true)
            .setHeader()
            .setSkipHeaderRecord(true)
            .get()
            .parse(new StringReader(text))) {
      var headers = parser.getHeaderNames();
      int code = -1, name = -1, dob = -1;
      for (int i = 0; i < headers.size(); i++) {
        switch (headers.get(i).strip().toLowerCase(Locale.ROOT)) {
          case "code" -> code = i;
          case "fullname", "full_name" -> name = i;
          case "dob", "date_of_birth" -> dob = i;
        }
      }
      if (code < 0 || name < 0 || dob < 0)
        throw ApiError.bad("header must contain columns full_name, code and date_of_birth");
      int cursor = 0, physicalLine = 1;
      for (CSVRecord record : parser) {
        while (cursor < record.getCharacterPosition())
          if (text.charAt(cursor++) == '\n') physicalLine++;
        if (record.size() == 1 && Text.strip(record.get(0)).isEmpty()) continue;
        if (rows.size() + errors.size() >= 1000) throw ApiError.bad("more than 1000 rows");
        int line = physicalLine;
        String raw = field(record, code);
        try {
          var row = validate(raw, field(record, name), field(record, dob));
          String c = str(row, "code");
          if (seen.containsKey(c))
            throw ApiError.bad("duplicate of line " + seen.get(c) + " in this file");
          seen.put(c, line);
          row.put("line", line);
          rows.add(row);
        } catch (ApiError e) {
          errors.add(map("line", line, "code", raw.strip(), "error", e.getMessage()));
        }
      }
    } catch (IOException | UncheckedIOException e) {
      throw ApiError.bad("invalid CSV: " + e.getMessage());
    }
    if (!errors.isEmpty())
      throw new ApiError(
          400,
          map(
              "error",
              "import rejected, nothing was saved: " + errors.size() + " invalid row(s)",
              "row_errors",
              errors));
    if (rows.isEmpty()) throw ApiError.bad("no data rows");
    rows.forEach(this::password);
    return db.tx(
        () -> {
          List<Map<String, Object>> created = new ArrayList<>(), skipped = new ArrayList<>();
          for (var row : rows) {
            var s = insert(row);
            if (s != null) created.add(s);
            else {
              var old = db.need("account", "SELECT role FROM users WHERE code=$1", row.get("code"));
              skipped.add(
                  map(
                      "line",
                      row.get("line"),
                      "code",
                      row.get("code"),
                      "reason",
                      str(old, "role").equals("teacher")
                          ? "code belongs to a teacher account"
                          : "already exists"));
            }
          }
          return map(
              "created_count",
              created.size(),
              "skipped_count",
              skipped.size(),
              "created",
              created,
              "skipped",
              skipped);
        });
  }

  private static String field(CSVRecord row, int index) {
    return index < row.size() ? row.get(index) : "";
  }
}
