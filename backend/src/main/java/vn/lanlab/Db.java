package vn.lanlab;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class Db {
  public final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private static final Pattern PARAM = Pattern.compile("\\$(\\d+)");

  public Db(DataSource source) {
    jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(15);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  record Query(String sql, Object[] args) {}

  static Query bind(String sql, Object... args) {
    Matcher m = PARAM.matcher(sql);
    List<Object> ordered = new ArrayList<>();
    while (m.find()) {
      Object v = args[Integer.parseInt(m.group(1)) - 1];
      if (v instanceof java.time.Instant i) v = Timestamp.from(i);
      ordered.add(v);
    }
    return new Query(m.replaceAll("?"), ordered.toArray());
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    Query q = bind(sql, args);
    return jdbc.query(
        q.sql,
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof Timestamp t) v = t.toInstant();
            if (v instanceof java.sql.Date d) v = d.toLocalDate();
            row.put(rs.getMetaData().getColumnLabel(i), v);
          }
          return row;
        },
        q.args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> need(String name, String sql, Object... args) {
    var row = one(sql, args);
    if (row == null) throw ApiError.missing(name);
    return row;
  }

  public int update(String sql, Object... args) {
    Query q = bind(sql, args);
    return jdbc.update(q.sql, q.args);
  }

  public <T> T tx(Supplier<T> body) {
    return transaction.execute(status -> body.get());
  }

  public void notify(String kind, long id) {
    one("SELECT pg_notify('judge_events', $1)", kind + ":" + id);
  }

  public static long num(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v instanceof Number n ? n.longValue() : 0;
  }

  public static String str(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }

  public static Map<String, Object> only(Map<String, Object> row, String... keys) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (String k : keys) m.put(k, row.get(k));
    return m;
  }
}
