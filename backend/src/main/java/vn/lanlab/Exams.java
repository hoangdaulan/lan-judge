package vn.lanlab;

import static vn.lanlab.Db.*;

import java.time.*;
import java.util.*;

public final class Exams {
  private final Db db;
  static final String SESSIONS =
      "SELECT s.*,e.teacher_id,e.title AS exam_title FROM exam_sessions s JOIN exams e ON"
          + " e.id=s.exam_id";
  static final String PARTICIPANTS =
      "SELECT p.id,p.exam_session_id AS session_id,p.student_id,u.code AS student_code,u.fullname"
          + " AS student_fullname,p.status,p.joined_at,p.finished_at FROM exam_participants p JOIN"
          + " users u ON u.id=p.student_id";

  public Exams(Db db) {
    this.db = db;
  }

  public static void role(Auth.Principal p, boolean teacher) {
    if (p.teacher() != teacher) throw new ApiError(403, "forbidden");
  }

  public static String title(String value, String name) {
    String s = Text.strip(value);
    if (s.isEmpty() || s.codePointCount(0, s.length()) > 255)
      throw ApiError.bad(name + " must be 1-255 characters");
    return s;
  }

  public Map<String, Object> ownedExam(Auth.Principal p, long id) {
    role(p, true);
    var e = db.need("exam", "SELECT * FROM exams WHERE id=$1 AND teacher_id=$2", id, p.id());
    return e;
  }

  public Map<String, Object> session(Auth.Principal p, long id) {
    var s = db.need("session", SESSIONS + " WHERE s.id=$1", id);
    if (p.teacher() && num(s, "teacher_id") != p.id()) throw ApiError.missing("session");
    return s;
  }

  public static String state(Map<String, Object> s, Instant now) {
    return now.isBefore((Instant) s.get("starts_at"))
        ? "upcoming"
        : now.isBefore((Instant) s.get("ends_at")) ? "running" : "ended";
  }

  public static Map<String, Object> publicSession(Map<String, Object> s) {
    var out = only(s, "id", "exam_id", "exam_title", "name", "starts_at", "ends_at");
    out.put("state", state(s, Instant.now()));
    return out;
  }

  public static Map<String, Object> publicExam(Map<String, Object> e) {
    return only(e, "id", "title", "description", "created_at", "updated_at");
  }

  public List<Map<String, Object>> problems(long exam) {
    return db.rows("SELECT id,code,title FROM problems WHERE exam_id=$1 ORDER BY code,id", exam);
  }

  public List<Map<String, Object>> list(Auth.Principal p) {
    role(p, true);
    return db.rows(
        "SELECT id,title,description,created_at,updated_at FROM exams WHERE teacher_id=$1 ORDER BY"
            + " id DESC",
        p.id());
  }

  public Map<String, Object> detail(Auth.Principal p, long id) {
    var e = publicExam(ownedExam(p, id));
    e.put("problems", problems(id));
    return e;
  }

  public Map<String, Object> save(Auth.Principal p, Long id, String title, String description) {
    role(p, true);
    if (id != null) ownedExam(p, id);
    String clean = title(title, "title");
    return publicExam(
        id == null
            ? db.need(
                "exam",
                "INSERT INTO exams(teacher_id,title,description) VALUES($1,$2,$3) RETURNING *",
                p.id(),
                clean,
                description)
            : db.need(
                "exam",
                "UPDATE exams SET title=$2,description=$3,updated_at=CURRENT_TIMESTAMP WHERE id=$1"
                    + " RETURNING *",
                id,
                clean,
                description));
  }

  public void delete(Auth.Principal p, long id) {
    ownedExam(p, id);
    db.tx(
        () -> {
          db.need("exam", "SELECT id FROM exams WHERE id=$1 FOR UPDATE", id);
          if (Boolean.TRUE.equals(
              db.one(
                      "SELECT EXISTS(SELECT 1 FROM problems WHERE exam_id=$1) OR EXISTS(SELECT 1"
                          + " FROM exam_sessions WHERE exam_id=$1) AS present",
                      id)
                  .get("present")))
            throw ApiError.conflict("delete the exam's problems and sessions first");
          db.update("DELETE FROM exams WHERE id=$1", id);
          return null;
        });
  }

  public List<Map<String, Object>> sessions(Auth.Principal p, Long exam) {
    if (exam != null) ownedExam(p, exam);
    else role(p, false);
    var rows =
        db.rows(
            SESSIONS
                + (exam == null ? "" : " WHERE s.exam_id=$1")
                + " ORDER BY s.starts_at DESC,s.id DESC",
            exam == null ? new Object[] {} : new Object[] {exam});
    return rows.stream()
        .map(
            s -> {
              var out = publicSession(s);
              if (exam == null) out.put("participation", participant(num(s, "id"), p.id()));
              return out;
            })
        .toList();
  }

  public Map<String, Object> saveSession(
      Auth.Principal p, Long exam, Long id, String name, String start, String end) {
    role(p, true);
    if (exam != null) ownedExam(p, exam);
    else session(p, id);
    String clean = title(name, "name");
    Instant a, b;
    try {
      a = Instant.parse(start);
      b = Instant.parse(end);
    } catch (Exception e) {
      throw ApiError.bad("starts_at and ends_at must be RFC3339 timestamps");
    }
    if (!b.isAfter(a)) throw ApiError.bad("ends_at must be after starts_at");
    long sid =
        id == null
            ? num(
                db.need(
                    "session",
                    "INSERT INTO exam_sessions(exam_id,name,starts_at,ends_at) VALUES($1,$2,$3,$4)"
                        + " RETURNING id",
                    exam,
                    clean,
                    a,
                    b),
                "id")
            : id;
    if (id != null)
      db.update(
          "UPDATE exam_sessions SET name=$2,starts_at=$3,ends_at=$4,updated_at=CURRENT_TIMESTAMP"
              + " WHERE id=$1",
          sid,
          clean,
          a,
          b);
    db.notify("session", sid);
    return publicSession(session(p, sid));
  }

  public void deleteSession(Auth.Principal p, long id) {
    role(p, true);
    session(p, id);
    db.tx(
        () -> {
          db.need("session", "SELECT id FROM exam_sessions WHERE id=$1 FOR UPDATE", id);
          if (Boolean.TRUE.equals(
              db.one(
                      "SELECT EXISTS(SELECT 1 FROM submissions s JOIN exam_participants p ON"
                          + " p.id=s.exam_participant_id WHERE p.exam_session_id=$1) AS present",
                      id)
                  .get("present")))
            throw ApiError.conflict("session has submissions and cannot be deleted");
          db.update("DELETE FROM exam_participants WHERE exam_session_id=$1", id);
          db.update("DELETE FROM exam_sessions WHERE id=$1", id);
          return null;
        });
  }

  public Map<String, Object> participant(long session, long student) {
    return db.one(
        PARTICIPANTS + " WHERE p.exam_session_id=$1 AND p.student_id=$2", session, student);
  }

  public List<Map<String, Object>> participants(Auth.Principal p, long id) {
    role(p, true);
    session(p, id);
    return db.rows(PARTICIPANTS + " WHERE p.exam_session_id=$1 ORDER BY u.code", id);
  }

  public Map<String, Object> view(Auth.Principal p, long id) {
    var s = session(p, id);
    Instant now = Instant.now();
    boolean hidden = !p.teacher() && state(s, now).equals("upcoming");
    var result =
        map(
            "session",
            publicSession(s),
            "server_time",
            now,
            "problems",
            hidden ? List.of() : problems(num(s, "exam_id")),
            "problems_hidden",
            hidden);
    if (!p.teacher()) result.put("participation", participant(id, p.id()));
    return result;
  }

  public Map<String, Object> join(Auth.Principal p, long id) {
    role(p, false);
    var s = session(p, id);
    var existing = participant(id, p.id());
    if (existing != null) return existing;
    String state = state(s, Instant.now());
    if (!state.equals("running"))
      throw ApiError.conflict(
          state.equals("upcoming") ? "session has not started" : "session has ended");
    db.update(
        "INSERT INTO exam_participants(exam_session_id,student_id,joined_at) VALUES($1,$2,$3) ON"
            + " CONFLICT(exam_session_id,student_id) DO NOTHING",
        id,
        p.id(),
        Instant.now());
    db.notify("session", id);
    return participant(id, p.id());
  }

  public Map<String, Object> finish(Auth.Principal p, long id) {
    role(p, false);
    session(p, id);
    if (participant(id, p.id()) == null) throw ApiError.conflict("join the session first");
    db.update(
        "UPDATE exam_participants SET status='finished',finished_at=GREATEST($3,joined_at) WHERE"
            + " exam_session_id=$1 AND student_id=$2 AND status='in_progress'",
        id,
        p.id(),
        Instant.now());
    db.notify("session", id);
    return participant(id, p.id());
  }
}
