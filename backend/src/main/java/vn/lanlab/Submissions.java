package vn.lanlab;

import static vn.lanlab.Db.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class Submissions {
  private final Db db;
  private final Exams exams;
  private final Storage storage;
  static final String SELECT =
      """
SELECT s.id,ep.exam_session_id AS session_id,e.teacher_id,s.problem_id,pr.code AS problem_code,pr.version AS problem_version,
u.id AS student_id,u.code AS student_code,u.fullname AS student_fullname,s.source_path,s.received_at,
r.id AS run_id,r.problem_version AS run_version,r.status,r.verdict,r.passed_tests,r.total_tests,
r.compiler_info,r.compile_output,r.system_error,r.created_at,r.started_at,r.finished_at
FROM submissions s JOIN exam_participants ep ON ep.id=s.exam_participant_id JOIN users u ON u.id=ep.student_id
JOIN problems pr ON pr.id=s.problem_id JOIN exams e ON e.id=pr.exam_id
LEFT JOIN judge_runs r ON r.submission_id=s.id AND r.problem_version=pr.version AND r.status<>'obsolete'
""";

  public Submissions(Db db, Exams exams, Storage storage) {
    this.db = db;
    this.exams = exams;
    this.storage = storage;
  }

  public Map<String, Object> raw(long id) {
    return db.need("submission", SELECT + " WHERE s.id=$1", id);
  }

  public Map<String, Object> owned(Auth.Principal p, long id) {
    var s = raw(id);
    if (num(s, p.teacher() ? "teacher_id" : "student_id") != p.id())
      throw ApiError.missing("submission");
    return s;
  }

  public static Map<String, Object> response(
      Map<String, Object> s, boolean detail, boolean teacher) {
    var out =
        only(
            s,
            "id",
            "session_id",
            "problem_id",
            "problem_code",
            "student_id",
            "student_code",
            "student_fullname",
            "received_at",
            "problem_version");
    Map<String, Object> result = null;
    if (s.get("run_id") != null) {
      result =
          only(
              s,
              "run_id",
              "status",
              "verdict",
              "passed_tests",
              "total_tests",
              "created_at",
              "started_at",
              "finished_at");
      result.put("problem_version", s.get("run_version"));
      if (detail) {
        result.put("compiler_info", s.get("compiler_info"));
        result.put("compile_output", s.get("compile_output"));
        if (teacher) result.put("system_error", s.get("system_error"));
      }
    }
    if (detail && result != null)
      for (String optional : List.of("compiler_info", "compile_output", "system_error"))
        if (result.get(optional) == null) result.remove(optional);
    out.put("result", result);
    return out;
  }

  public List<Map<String, Object>> list(
      Auth.Principal p, long session, long student, long problem) {
    if (p.teacher()) exams.session(p, session);
    return db
        .rows(
            SELECT
                + " WHERE ep.exam_session_id=$1 AND ($2::bigint=0 OR ep.student_id=$2) AND"
                + " ($3::bigint=0 OR s.problem_id=$3) ORDER BY s.received_at DESC,s.id DESC",
            session,
            p.teacher() ? student : p.id(),
            problem)
        .stream()
        .map(s -> response(s, false, p.teacher()))
        .toList();
  }

  public Map<String, Object> submit(
      Auth.Principal p, long session, long problem, String filename, byte[] content)
      throws IOException {
    Exams.role(p, false);
    if (content.length > 65536) throw new ApiError(413, "the file is larger than 64 KB");
    if (content.length == 0 || !filename.toLowerCase(Locale.ROOT).endsWith(".cpp"))
      throw ApiError.bad("a non-empty .cpp file is required");
    Instant received = Instant.now();
    String path = storage.save(content, received);
    try {
      long id =
          db.tx(
              () -> {
                var pr =
                    db.need(
                        "problem",
                        "SELECT exam_id,version FROM problems WHERE id=$1 FOR SHARE",
                        problem);
                var s =
                    db.need(
                        "session",
                        "SELECT exam_id,starts_at,ends_at FROM exam_sessions WHERE id=$1 FOR SHARE",
                        session);
                if (num(pr, "exam_id") != num(s, "exam_id")) throw ApiError.missing("problem");
                var part =
                    db.one(
                        "SELECT id,status FROM exam_participants WHERE exam_session_id=$1 AND"
                            + " student_id=$2 FOR UPDATE",
                        session,
                        p.id());
                if (part == null) throw ApiError.conflict("join the session before submitting");
                if (!str(part, "status").equals("in_progress"))
                  throw ApiError.conflict("you have finished this session");
                if (received.isBefore((Instant) s.get("starts_at")))
                  throw ApiError.conflict("the session has not started");
                if (!received.isBefore((Instant) s.get("ends_at")))
                  throw ApiError.conflict("the session has ended");
                if (num(
                        db.one("SELECT count(*) AS n FROM test_cases WHERE problem_id=$1", problem),
                        "n")
                    == 0) throw ApiError.conflict("the problem has no tests yet");
                long sub =
                    num(
                        db.need(
                            "submission",
                            "INSERT INTO"
                                + " submissions(exam_participant_id,problem_id,source_path,received_at)"
                                + " VALUES($1,$2,$3,$4) RETURNING id",
                            part.get("id"),
                            problem,
                            path,
                            received),
                        "id");
                enqueue(sub, num(pr, "version"));
                db.notify("s", sub);
                return sub;
              });
      return response(raw(id), false, false);
    } catch (ApiError e) {
      storage.remove(path);
      throw e;
    }
  }

  long enqueue(long id, long version) {
    long run =
        num(
            db.need(
                "run",
                "INSERT INTO judge_runs(submission_id,problem_version) VALUES($1,$2) RETURNING id",
                id,
                version),
            "id");
    db.update("INSERT INTO judge_jobs(judge_run_id) VALUES($1)", run);
    return run;
  }

  public Map<String, Object> rejudge(Auth.Principal p, long id) {
    Exams.role(p, true);
    var sub = owned(p, id);
    db.tx(
        () -> {
          var pr =
              db.need(
                  "problem",
                  "SELECT version FROM problems WHERE id=$1 FOR SHARE",
                  sub.get("problem_id"));
          db.rows(
              "SELECT id FROM judge_runs WHERE submission_id=$1 AND status<>'obsolete' ORDER BY id"
                  + " FOR UPDATE",
              id);
          db.rows(
              "SELECT j.id FROM judge_jobs j JOIN judge_runs r ON r.id=j.judge_run_id WHERE"
                  + " r.submission_id=$1 AND j.status<>'obsolete' ORDER BY j.id FOR UPDATE OF j",
              id);
          db.update(
              "UPDATE judge_jobs j SET"
                  + " status='obsolete',worker_id=NULL,lease_token=NULL,lease_expires_at=NULL,updated_at=CURRENT_TIMESTAMP"
                  + " FROM judge_runs r WHERE r.id=j.judge_run_id AND r.submission_id=$1 AND"
                  + " j.status IN('queued','running')",
              id);
          db.update(
              "UPDATE judge_runs SET status='obsolete',verdict=NULL WHERE submission_id=$1 AND"
                  + " status<>'obsolete'",
              id);
          enqueue(id, num(pr, "version"));
          db.notify("s", id);
          return null;
        });
    return response(raw(id), true, true);
  }

  public byte[] source(Auth.Principal p, long id) throws IOException {
    return storage.read(str(owned(p, id), "source_path"));
  }

  public List<Map<String, Object>> tests(Auth.Principal p, long id) {
    Exams.role(p, true);
    var s = owned(p, id);
    return s.get("run_id") == null
        ? List.of()
        : db.rows(
            "SELECT test_case_id,verdict,execution_time_ms,memory_used_kb FROM"
                + " submission_test_results WHERE judge_run_id=$1 ORDER BY test_case_id",
            s.get("run_id"));
  }

  public void cleanup() throws IOException {
    Path root = storage.resolve("submissions");
    if (!Files.exists(root)) return;
    try (var files = Files.walk(root)) {
      for (Path f :
          files
              .filter(
                  p ->
                      Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                          && p.toString().endsWith(".cpp"))
              .toList()) {
        if (Files.getLastModifiedTime(f).toInstant().isAfter(Instant.now().minusSeconds(3600)))
          continue;
        String rel =
            "submissions/" + root.relativize(f).toString().replace(File.separatorChar, '/');
        if (db.one("SELECT id FROM submissions WHERE source_path=$1 LIMIT 1", rel) == null)
          storage.remove(rel);
      }
    }
  }
}
