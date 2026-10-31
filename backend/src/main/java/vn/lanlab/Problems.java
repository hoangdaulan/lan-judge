package vn.lanlab;

import static vn.lanlab.Db.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Problems {
  private final Db db;
  private final Exams exams;
  private final Storage storage;
  private static final String SELECT =
      "SELECT p.*,e.teacher_id FROM problems p JOIN exams e ON e.id=p.exam_id";

  public Problems(Db db, Exams exams, Storage storage) {
    this.db = db;
    this.exams = exams;
    this.storage = storage;
  }

  public static String testDir(long id) {
    return "problems/" + id + "/tests";
  }

  public Map<String, Object> owned(Auth.Principal p, long id) {
    Exams.role(p, true);
    return db.need("problem", SELECT + " WHERE p.id=$1 AND e.teacher_id=$2", id, p.id());
  }

  static Map<String, Object> publicProblem(Map<String, Object> p) {
    var out = new LinkedHashMap<>(p);
    out.remove("teacher_id");
    out.put("test_dir", testDir(num(p, "id")));
    return out;
  }

  public List<Map<String, Object>> list(Auth.Principal p, long exam) {
    exams.ownedExam(p, exam);
    return db.rows(SELECT + " WHERE p.exam_id=$1 ORDER BY p.code,p.id", exam).stream()
        .map(Problems::publicProblem)
        .toList();
  }

  private Map<String, Object> validate(Map<String, Object> in) {
    var out = new LinkedHashMap<>(in);
    String code = Auth.code(Api.text(in, "code"));
    if (code.isEmpty()
        || code.codePointCount(0, code.length()) > 20
        || code.codePoints().anyMatch(c -> Text.space(c) || Character.isISOControl(c)))
      throw ApiError.bad("code must be 1-20 characters without spaces");
    out.put("code", code);
    out.put("title", Exams.title(Api.text(in, "title"), "title"));
    out.put("statement", Api.text(in, "statement"));
    for (String key : List.of("time_limit_ms", "memory_limit_mb")) {
      if (!(in.get(key) instanceof Integer || in.get(key) instanceof Long))
        throw ApiError.bad(key + " must be an integer");
    }
    long time = num(in, "time_limit_ms"), memory = num(in, "memory_limit_mb");
    if (time < 1 || time > 10000 || memory < 1 || memory > 1024)
      throw ApiError.bad("invalid time_limit_ms or memory_limit_mb");
    return out;
  }

  public Map<String, Object> create(Auth.Principal p, long exam, Map<String, Object> body)
      throws IOException {
    exams.ownedExam(p, exam);
    var in = validate(body);
    var row =
        db.need(
            "problem",
            "INSERT INTO problems(exam_id,code,title,statement,time_limit_ms,memory_limit_mb)"
                + " VALUES($1,$2,$3,$4,$5,$6) RETURNING *",
            exam,
            in.get("code"),
            in.get("title"),
            str(in, "statement"),
            in.get("time_limit_ms"),
            in.get("memory_limit_mb"));
    try {
      Files.createDirectories(storage.resolve(testDir(num(row, "id"))));
    } catch (IOException e) {
      org.slf4j.LoggerFactory.getLogger(Problems.class).error("create test directory failed", e);
    }
    return publicProblem(row);
  }

  public Map<String, Object> update(Auth.Principal p, long id, Map<String, Object> body) {
    owned(p, id);
    var in = validate(body);
    return db.tx(
        () -> {
          var old = db.need("problem", "SELECT * FROM problems WHERE id=$1 FOR UPDATE", id);
          boolean bump =
              num(old, "time_limit_ms") != num(in, "time_limit_ms")
                  || num(old, "memory_limit_mb") != num(in, "memory_limit_mb");
          var row =
              db.need(
                  "problem",
                  "UPDATE problems SET"
                      + " code=$2,title=$3,statement=$4,time_limit_ms=$5,memory_limit_mb=$6,version=version+$7,updated_at=CURRENT_TIMESTAMP"
                      + " WHERE id=$1 RETURNING *",
                  id,
                  in.get("code"),
                  in.get("title"),
                  str(in, "statement"),
                  in.get("time_limit_ms"),
                  in.get("memory_limit_mb"),
                  bump ? 1 : 0);
          int requeued = bump ? requeue(id, num(row, "version")) : 0;
          return map(
              "problem",
              publicProblem(row),
              "rejudge_queued",
              bump,
              "requeued_submissions",
              requeued);
        });
  }

  public void delete(Auth.Principal p, long id) {
    owned(p, id);
    db.tx(
        () -> {
          db.need("problem", "SELECT id FROM problems WHERE id=$1 FOR UPDATE", id);
          if (num(db.one("SELECT count(*) AS n FROM submissions WHERE problem_id=$1", id), "n") > 0)
            throw ApiError.conflict("problem has submissions and cannot be deleted");
          db.update("DELETE FROM test_cases WHERE problem_id=$1", id);
          db.update("DELETE FROM problems WHERE id=$1", id);
          return null;
        });
  }

  public List<Map<String, Object>> tests(long id) {
    return db.rows("SELECT id,input,output FROM test_cases WHERE problem_id=$1 ORDER BY id", id);
  }

  public static int compareNames(String a, String b) {
    Long x = null, y = null;
    try {
      x = Long.valueOf(a);
    } catch (NumberFormatException ignored) {
    }
    try {
      y = Long.valueOf(b);
    } catch (NumberFormatException ignored) {
    }
    if (x != null && y != null && x.compareTo(y) != 0) return x.compareTo(y);
    if (x != null && y == null) return -1;
    if (x == null && y != null) return 1;
    return java.util.Arrays.compareUnsigned(
        a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public List<Map<String, Object>> scan(long id) throws IOException {
    String rel = testDir(id);
    Path dir = storage.resolve(rel);
    if (!Files.isDirectory(dir))
      throw ApiError.conflict("test directory " + rel + " does not exist under DATA_DIR");
    Map<String, Map<String, Object>> pairs = new TreeMap<>(Problems::compareNames);
    List<String> errors = new ArrayList<>();
    try (var files = Files.list(dir)) {
      for (Path path : files.toList()) {
        String name = path.getFileName().toString();
        if (name.startsWith(".") || !(name.endsWith(".in") || name.endsWith(".out"))) continue;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          errors.add(name + ": not a regular file");
          continue;
        }
        boolean input = name.endsWith(".in");
        String base = name.substring(0, name.length() - (input ? 3 : 4));
        var pair = pairs.computeIfAbsent(base, k -> map("name", k));
        pair.put(input ? "input" : "output", rel + "/" + name);
        pair.put(input ? "input_size" : "output_size", Files.size(path));
      }
    }
    for (var pair : pairs.values()) {
      String name = str(pair, "name");
      if (!pair.containsKey("input")) errors.add(name + ".out has no " + name + ".in");
      else if (!pair.containsKey("output")) errors.add(name + ".in has no " + name + ".out");
    }
    if (!errors.isEmpty())
      throw ApiError.conflict("invalid test files in " + rel + ": " + String.join("; ", errors));
    if (pairs.isEmpty())
      throw ApiError.conflict("no tests in " + rel + " (expected pairs like 1.in / 1.out)");
    if (pairs.size() > 500) throw ApiError.conflict("too many tests (max 500)");
    return new ArrayList<>(pairs.values());
  }

  public Map<String, Object> disk(long id, List<Map<String, Object>> registered)
      throws IOException {
    try {
      var files = scan(id);
      Set<String> paths = new HashSet<>();
      for (var t : registered) paths.add(t.get("input") + "\0" + t.get("output"));
      boolean sync =
          files.size() == registered.size()
              && files.stream()
                  .allMatch(t -> paths.contains(t.get("input") + "\0" + t.get("output")));
      return map("tests", files.size(), "in_sync", sync);
    } catch (ApiError e) {
      return map("tests", 0, "error", e.getMessage(), "in_sync", false);
    }
  }

  public Map<String, Object> rejudge(Auth.Principal p, long id) throws IOException {
    owned(p, id);
    var files = scan(id);
    return db.tx(
        () -> {
          db.need("problem", "SELECT id FROM problems WHERE id=$1 FOR UPDATE", id);
          db.update("DELETE FROM test_cases WHERE problem_id=$1", id);
          for (var t : files)
            db.update(
                "INSERT INTO test_cases(problem_id,input,output) VALUES($1,$2,$3)",
                id,
                t.get("input"),
                t.get("output"));
          var row =
              db.need(
                  "problem",
                  "UPDATE problems SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=$1"
                      + " RETURNING *",
                  id);
          int n = requeue(id, num(row, "version"));
          return map(
              "problem",
              publicProblem(row),
              "tests",
              files,
              "rejudge_queued",
              true,
              "requeued_submissions",
              n);
        });
  }

  // Caller holds the problem lock; always lock runs before jobs, ascending.
  int requeue(long id, long version) {
    db.rows(
        "SELECT r.id FROM judge_runs r JOIN submissions s ON s.id=r.submission_id WHERE"
            + " s.problem_id=$1 AND r.status<>'obsolete' ORDER BY r.id FOR UPDATE OF r",
        id);
    db.rows(
        "SELECT j.id FROM judge_jobs j JOIN judge_runs r ON r.id=j.judge_run_id JOIN submissions s"
            + " ON s.id=r.submission_id WHERE s.problem_id=$1 AND j.status<>'obsolete' ORDER BY"
            + " j.id FOR UPDATE OF j",
        id);
    db.update(
        "UPDATE judge_jobs j SET"
            + " status='obsolete',worker_id=NULL,lease_token=NULL,lease_expires_at=NULL,updated_at=CURRENT_TIMESTAMP"
            + " FROM judge_runs r,submissions s WHERE r.id=j.judge_run_id AND s.id=r.submission_id"
            + " AND s.problem_id=$1 AND j.status IN('queued','running')",
        id);
    db.update(
        "UPDATE judge_runs r SET status='obsolete',verdict=NULL FROM submissions s WHERE"
            + " s.id=r.submission_id AND s.problem_id=$1 AND r.status<>'obsolete'",
        id);
    int n =
        db.update(
            "WITH runs AS (INSERT INTO judge_runs(submission_id,problem_version) SELECT id,$2 FROM"
                + " submissions WHERE problem_id=$1 ORDER BY id RETURNING id) INSERT INTO"
                + " judge_jobs(judge_run_id) SELECT id FROM runs",
            id,
            version);
    db.notify("p", id);
    return n;
  }

  public Map<String, Object> student(Auth.Principal p, long session, long problem) {
    Exams.role(p, false);
    var s = exams.session(p, session);
    var row =
        db.need("problem", SELECT + " WHERE p.id=$1 AND p.exam_id=$2", problem, s.get("exam_id"));
    if (Exams.state(s, java.time.Instant.now()).equals("upcoming"))
      throw new ApiError(403, "the statement is available from the session start time");
    if (exams.participant(session, p.id()) == null)
      throw new ApiError(403, "join the session first");
    return only(row, "id", "code", "title", "statement", "time_limit_ms", "memory_limit_mb");
  }
}
