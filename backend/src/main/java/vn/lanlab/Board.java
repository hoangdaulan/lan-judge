package vn.lanlab;

import static vn.lanlab.Db.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.csv.*;

public final class Board {
  public interface Presence {
    Set<Long> online(long session);

    void announce(Map<String, Object> message);
  }

  private final Db db;
  private final Exams exams;
  private final Submissions submissions;
  private Presence presence;
  private final Map<Long, Deque<Map<String, Object>>> announcements = new HashMap<>();
  private final AtomicLong next = new AtomicLong();

  public Board(Db db, Exams exams, Submissions submissions) {
    this.db = db;
    this.exams = exams;
    this.submissions = submissions;
  }

  public void presence(Presence p) {
    presence = p;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> summary(Auth.Principal p, long id) {
    var session = exams.session(p, id);
    if (!p.teacher() && exams.participant(id, p.id()) == null)
      throw new ApiError(403, "join the session first");
    var problems = exams.problems(num(session, "exam_id"));
    Set<Long> online = presence == null ? Set.of() : presence.online(id);
    List<Map<String, Object>> rows = new ArrayList<>();
    var parts =
        db.rows(
            Exams.PARTICIPANTS
                + " WHERE p.exam_session_id=$1 AND ($2::bigint=0 OR p.student_id=$2) ORDER BY"
                + " u.code",
            id,
            p.teacher() ? 0 : p.id());
    var subs =
        db.rows(
            Submissions.SELECT
                + " WHERE ep.exam_session_id=$1 AND ($2::bigint=0 OR ep.student_id=$2) ORDER BY"
                + " s.received_at DESC,s.id DESC",
            id,
            p.teacher() ? 0 : p.id());
    for (var part : parts) {
      long student = num(part, "student_id");
      List<Map<String, Object>> cells = new ArrayList<>();
      int ac = 0;
      for (var problem : problems) {
        var attempts =
            subs.stream()
                .filter(
                    s ->
                        num(s, "student_id") == student
                            && num(s, "problem_id") == num(problem, "id"))
                .toList();
        boolean solved =
            attempts.stream()
                .anyMatch(
                    s -> str(s, "status").equals("completed") && str(s, "verdict").equals("AC"));
        boolean pending =
            attempts.stream()
                .anyMatch(
                    s ->
                        s.get("run_id") == null
                            || List.of("queued", "judging").contains(str(s, "status")));
        String state =
            solved ? "ac" : pending ? "pending" : attempts.isEmpty() ? "none" : "attempted";
        Map<String, Object> latest = null;
        if (!attempts.isEmpty()) {
          var first = attempts.get(0);
          latest = only(first, "status", "verdict", "passed_tests", "total_tests");
          latest.put("submission_id", first.get("id"));
          if (latest.get("passed_tests") == null) latest.put("passed_tests", 0);
          if (latest.get("total_tests") == null) latest.put("total_tests", 0);
        }
        cells.add(
            map(
                "problem_id",
                problem.get("id"),
                "state",
                state,
                "submissions",
                attempts.size(),
                "latest",
                latest));
        if (solved) ac++;
      }
      if (!p.teacher() && Exams.state(session, Instant.now()).equals("upcoming")) cells.clear();
      rows.add(
          map(
              "student_id",
              student,
              "student_code",
              part.get("student_code"),
              "student_fullname",
              part.get("student_fullname"),
              "status",
              part.get("status"),
              "online",
              online.contains(student),
              "ac_count",
              ac,
              "cells",
              cells));
    }
    return map(
        "session_id",
        id,
        "problems_total",
        problems.size(),
        "problems",
        !p.teacher() && Exams.state(session, Instant.now()).equals("upcoming")
            ? List.of()
            : problems,
        "rows",
        rows,
        "online_count",
        p.teacher() ? online.size() : 0,
        "queue",
        db.one(
            "SELECT count(*) FILTER(WHERE status='queued') AS queued,count(*) FILTER(WHERE"
                + " status='running') AS running FROM judge_jobs"),
        "generated_at",
        Instant.now());
  }

  public synchronized Map<String, Object> announce(Auth.Principal p, long session, String raw) {
    Exams.role(p, true);
    exams.session(p, session);
    String text = Text.strip(raw);
    if (text.isEmpty() || text.codePointCount(0, text.length()) > 1000)
      throw ApiError.bad("text must be 1-1000 characters");
    var a =
        map(
            "id",
            next.incrementAndGet(),
            "session_id",
            session,
            "text",
            text,
            "created_at",
            Instant.now());
    var list = announcements.computeIfAbsent(session, k -> new ArrayDeque<>());
    list.addFirst(a);
    while (list.size() > 50) list.removeLast();
    if (presence != null) presence.announce(a);
    return a;
  }

  public synchronized List<Map<String, Object>> announcements(Auth.Principal p, long id) {
    exams.session(p, id);
    return new ArrayList<>(announcements.getOrDefault(id, new ArrayDeque<>()));
  }

  public Map<String, Object> queue() {
    var jobs = map("queued", 0, "running", 0, "done", 0, "failed", 0, "obsolete", 0);
    for (var r : db.rows("SELECT status,count(*) AS n FROM judge_jobs GROUP BY status"))
      jobs.put(str(r, "status"), r.get("n"));
    var stats =
        db.one(
            "SELECT count(*) AS recent_runs,COALESCE(avg(EXTRACT(EPOCH FROM"
                + " started_at-created_at)*1000),0) AS avg_queue_wait_ms,COALESCE(avg(EXTRACT(EPOCH"
                + " FROM finished_at-started_at)*1000) FILTER(WHERE status='completed'),0) AS"
                + " avg_judge_ms FROM judge_runs WHERE started_at>clock_timestamp()-interval '10"
                + " minutes'");
    stats.put("jobs", jobs);
    stats.put(
        "active_workers",
        db
            .rows(
                "SELECT DISTINCT worker_id FROM judge_jobs WHERE status='running' AND"
                    + " lease_expires_at>clock_timestamp() ORDER BY 1")
            .stream()
            .map(r -> r.get("worker_id"))
            .toList());
    stats.put(
        "oldest_queued_ms",
        db.one(
                "SELECT COALESCE(EXTRACT(EPOCH FROM"
                    + " clock_timestamp()-min(created_at))*1000,0)::bigint AS n FROM judge_jobs"
                    + " WHERE status='queued' AND available_at<=clock_timestamp()")
            .get("n"));
    stats.put("generated_at", Instant.now());
    return stats;
  }

  static String safeCell(String s) {
    return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
  }

  @SuppressWarnings("unchecked")
  public byte[] export(Auth.Principal p, long session) throws IOException {
    Exams.role(p, true);
    var summary = summary(p, session);
    StringWriter out = new StringWriter();
    out.write('\uFEFF');
    try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.RFC4180)) {
      List<Object> header = new ArrayList<>(List.of("student_code", "fullname", "status"));
      for (var pr : (List<Map<String, Object>>) summary.get("problems"))
        header.add(safeCell(str(pr, "code")));
      header.add("ac");
      csv.printRecord(header);
      for (var row : (List<Map<String, Object>>) summary.get("rows")) {
        List<Object> fields =
            new ArrayList<>(
                List.of(
                    safeCell(str(row, "student_code")),
                    safeCell(str(row, "student_fullname")),
                    row.get("status")));
        for (var cell : (List<Map<String, Object>>) row.get("cells")) {
          String value =
              switch (str(cell, "state")) {
                case "ac" -> "AC";
                case "none" -> "";
                case "pending" -> "pending";
                default -> {
                  var latest = (Map<String, Object>) cell.get("latest");
                  yield str(latest, "status").equals("system_error")
                      ? "system_error"
                      : latest.get("verdict") == null
                          ? "attempted"
                          : latest.get("passed_tests")
                              + "/"
                              + latest.get("total_tests")
                              + " "
                              + latest.get("verdict");
                }
              };
          fields.add(value);
        }
        fields.add(row.get("ac_count") + "/" + summary.get("problems_total") + " AC");
        csv.printRecord(fields);
      }
    }
    return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
