package vn.lanlab;

import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

public record Config(Map<String, String> env) {
  public Config {
    env = Map.copyOf(env);
  }

  public String get(String key, String fallback) {
    String s = env.get(key);
    return s == null || s.isEmpty() ? fallback : s;
  }

  public String get(String key) {
    return get(key, "");
  }

  public int integer(String key, int fallback, int min, int max) {
    try {
      int n = Integer.parseInt(get(key, "" + fallback));
      if (n < min || n > max) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be " + min + "-" + max);
    }
  }

  public Duration duration(String key, String fallback) {
    return parseDuration(get(key, fallback));
  }

  public static Duration parseDuration(String s) {
    Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|ms|s|m|h)").matcher(s);
    int end = 0;
    double nanos = 0;
    while (m.find()) {
      if (m.start() != end) throw new IllegalArgumentException("invalid duration: " + s);
      end = m.end();
      nanos +=
          Double.parseDouble(m.group(1))
              * switch (m.group(2)) {
                case "ns" -> 1;
                case "us", "µs" -> 1e3;
                case "ms" -> 1e6;
                case "s" -> 1e9;
                case "m" -> 60e9;
                default -> 3600e9;
              };
    }
    if (end != s.length() || nanos <= 0 || nanos > Long.MAX_VALUE)
      throw new IllegalArgumentException("invalid duration: " + s);
    return Duration.ofNanos((long) nanos);
  }

  public Path data() {
    return Path.of(get("DATA_DIR", "/data")).toAbsolutePath().normalize();
  }

  public int slots() {
    return integer("WORKER_CONCURRENCY", 2, 1, 64);
  }

  public Duration lease() {
    Duration d = duration("WORKER_LEASE", "60s");
    if (d.compareTo(Duration.ofSeconds(3)) < 0)
      throw new IllegalArgumentException("WORKER_LEASE must be at least 3s");
    return d;
  }

  public static InetSocketAddress address(String s) {
    int colon = s.lastIndexOf(':');
    if (colon < 0) throw new IllegalArgumentException("expected host:port: " + s);
    String host = s.substring(0, colon).replace("[", "").replace("]", "");
    return new InetSocketAddress(
        host.isEmpty() ? "0.0.0.0" : host, Integer.parseInt(s.substring(colon + 1)));
  }

  public List<String> list(String key) {
    return Arrays.stream(get(key).split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  public String workerId() {
    try {
      return get("WORKER_ID", InetAddress.getLocalHost().getHostName());
    } catch (Exception e) {
      return get("WORKER_ID", "worker");
    }
  }

  public Map<String, Object> datasource() {
    String url = get("DATABASE_URL");
    if (url.isEmpty()) throw new IllegalArgumentException("DATABASE_URL is required");
    if (url.startsWith("jdbc:")) return Map.of("spring.datasource.url", url);
    URI uri = URI.create(url);
    String[] user = Optional.ofNullable(uri.getRawUserInfo()).orElse("").split(":", 2);
    String jdbc =
        "jdbc:postgresql://"
            + uri.getHost()
            + ":"
            + (uri.getPort() < 0 ? 5432 : uri.getPort())
            + uri.getRawPath()
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    return Map.of(
        "spring.datasource.url",
        jdbc,
        "spring.datasource.username",
        decode(user[0]),
        "spring.datasource.password",
        user.length > 1 ? decode(user[1]) : "");
  }

  private static String decode(String s) {
    return URLDecoder.decode(s.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
  }
}
