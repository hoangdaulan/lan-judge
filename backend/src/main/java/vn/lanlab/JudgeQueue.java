package vn.lanlab;

import static vn.lanlab.Db.*;

import java.time.*;
import java.util.*;

public final class JudgeQueue {
  // Field names deliberately match the existing TCP snapshot protocol, including case.
  public static class TestCase {
    public long ID;
    public String InputPath, OutputPath;

    public TestCase() {}

    public TestCase(Map<String, Object> t) {
      ID = num(t, "id");
      InputPath = str(t, "input");
      OutputPath = str(t, "output");
    }
  }

  public static class Job {
    public long JobID, RunID, SubmissionID, ProblemID;
    public int ProblemVersion, Attempt;
    public String LeaseToken, SourcePath;
    public int TimeLimitMs, MemoryLimitMb;
    public List<TestCase> Tests = new ArrayList<>();
    public Instant QueuedAt;
  }

  public static class TestOutcome {
    public long TestCaseID;
    public String Verdict;
    public int TimeMs;
    public Long MemoryKB;
  }

  public static class Outcome {
    public String Verdict = "", CompilerInfo = "", CompileOutput = "";
    public int PassedTests, TotalTests;
    public List<TestOutcome> Tests = new ArrayList<>();

    public void validate(Job job) {
      // Go encodes a nil test slice as null, including legitimate CE outcomes.
      if (Tests == null) Tests = new ArrayList<>();
      if (TotalTests != job.Tests.size()
          || TotalTests < 1
          || PassedTests < 0
          || PassedTests > TotalTests)
        throw new IllegalArgumentException("outcome counts do not match snapshot");
      if (Verdict.equals("CE")) {
        if (PassedTests != 0 || !Tests.isEmpty())
          throw new IllegalArgumentException("CE has test results");
        return;
      }
      if (Verdict.equals("AC")) {
        if (PassedTests != TotalTests || Tests.size() != TotalTests)
          throw new IllegalArgumentException("AC must pass every test");
      } else if (!List.of("WA", "TLE", "MLE", "RE").contains(Verdict)
          || PassedTests >= TotalTests
          || Tests.size() != PassedTests + 1
          || !Verdict.equals(Tests.get(Tests.size() - 1).Verdict))
        throw new IllegalArgumentException("must stop at first failure");
      for (int i = 0; i < Tests.size(); i++) {
        var t = Tests.get(i);
        if (t.TestCaseID != job.Tests.get(i).ID
            || t.TimeMs < 0
            || (t.MemoryKB != null && t.MemoryKB < 0)
            || (i < PassedTests && !"AC".equals(t.Verdict)))
          throw new IllegalArgumentException("invalid test result");
      }
    }
  }

  private final Db db;
  private final Config cfg;

  public JudgeQueue(Db db, Config cfg) {
    this.db = db;
    this.cfg = cfg;
  }

  private Map<String, Object> lock(long id, boolean skip) {
    var ids =
        db.one(
            "SELECT r.id AS run_id,s.problem_id FROM judge_jobs j JOIN judge_runs r ON"
                + " r.id=j.judge_run_id JOIN submissions s ON s.id=r.submission_id WHERE j.id=$1",
            id);
    if (ids == null) return null;
    var p =
        db.need(
            "problem", "SELECT version FROM problems WHERE id=$1 FOR SHARE", ids.get("problem_id"));
    var run =
        db.need(
            "run",
            "SELECT status AS run_status,problem_version FROM judge_runs WHERE id=$1 FOR UPDATE",
            ids.get("run_id"));
    var job =
        db.one(
            "SELECT status,lease_token::text AS lease_token,lease_expires_at<=clock_timestamp() AS"
                + " expired,available_at<=clock_timestamp() AS available,attempt_count FROM"
                + " judge_jobs WHERE id=$1 FOR UPDATE"
                + (skip ? " SKIP LOCKED" : ""),
            id);
    if (job == null) return null;
    job.putAll(ids);
    job.putAll(run);
    job.put("version", p.get("version"));
    return job;
  }

  private void obsolete(long run) {
    db.update("UPDATE judge_runs SET status='obsolete',verdict=NULL WHERE id=$1", run);
    db.update(
        "UPDATE judge_jobs SET"
            + " status='obsolete',worker_id=NULL,lease_token=NULL,lease_expires_at=NULL,updated_at=clock_timestamp()"
            + " WHERE judge_run_id=$1",
        run);
  }

  private void reset(long run) {
    db.update("DELETE FROM submission_test_results WHERE judge_run_id=$1", run);
    db.update(
        "UPDATE judge_runs SET"
            + " status='queued',verdict=NULL,passed_tests=0,total_tests=0,compiler_info=NULL,compile_output=NULL,system_error=NULL,started_at=NULL,finished_at=NULL"
            + " WHERE id=$1",
        run);
  }

  private void notifyRun(long run) {
    db.one("SELECT pg_notify('judge_events','s:'||submission_id) FROM judge_runs WHERE id=$1", run);
  }

  public Job claim(String worker) {
    for (var candidate :
        db.rows(
            "SELECT id FROM judge_jobs WHERE status='queued' AND available_at<=clock_timestamp()"
                + " ORDER BY available_at,id LIMIT 16")) {
      Job job =
          db.tx(
              () -> {
                long id = num(candidate, "id");
                var st = lock(id, true);
                if (st == null
                    || !str(st, "status").equals("queued")
                    || !Boolean.TRUE.equals(st.get("available"))) return null;
                if (num(st, "version") != num(st, "problem_version")
                    || str(st, "run_status").equals("obsolete")) {
                  obsolete(num(st, "run_id"));
                  return null;
                }
                Job j = new Job();
                j.JobID = id;
                j.RunID = num(st, "run_id");
                j.LeaseToken = UUID.randomUUID().toString();
                reset(j.RunID);
                var claimed =
                    db.need(
                        "job",
                        "UPDATE judge_jobs SET"
                            + " status='running',worker_id=$2,lease_token=$3::uuid,lease_expires_at=clock_timestamp()+make_interval(secs=>$4),attempt_count=attempt_count+1,updated_at=clock_timestamp()"
                            + " WHERE id=$1 RETURNING attempt_count,created_at",
                        id,
                        worker,
                        j.LeaseToken,
                        cfg.lease().toMillis() / 1000.0);
                j.Attempt = (int) num(claimed, "attempt_count");
                j.QueuedAt = (Instant) claimed.get("created_at");
                db.update(
                    "UPDATE judge_runs SET"
                        + " status='judging',started_at=GREATEST(clock_timestamp(),created_at)"
                        + " WHERE id=$1",
                    j.RunID);
                notifyRun(j.RunID);
                var snapshot =
                    db.need(
                        "run",
                        "SELECT s.id,s.source_path,p.id AS"
                            + " problem_id,p.version,p.time_limit_ms,p.memory_limit_mb FROM"
                            + " judge_runs r JOIN submissions s ON s.id=r.submission_id JOIN"
                            + " problems p ON p.id=s.problem_id WHERE r.id=$1",
                        j.RunID);
                j.SubmissionID = num(snapshot, "id");
                j.ProblemID = num(snapshot, "problem_id");
                j.ProblemVersion = (int) num(snapshot, "version");
                j.SourcePath = str(snapshot, "source_path");
                j.TimeLimitMs = (int) num(snapshot, "time_limit_ms");
                j.MemoryLimitMb = (int) num(snapshot, "memory_limit_mb");
                j.Tests =
                    db
                        .rows(
                            "SELECT id,input,output FROM test_cases WHERE problem_id=$1 ORDER BY"
                                + " id",
                            j.ProblemID)
                        .stream()
                        .map(TestCase::new)
                        .toList();
                return j;
              });
      if (job != null) return job;
    }
    return null;
  }

  public boolean heartbeat(Job j) {
    return db.update(
            "UPDATE judge_jobs SET"
                + " lease_expires_at=clock_timestamp()+make_interval(secs=>$3),updated_at=clock_timestamp()"
                + " WHERE id=$1 AND status='running' AND lease_token=$2::uuid AND"
                + " lease_expires_at>clock_timestamp()",
            j.JobID,
            j.LeaseToken,
            cfg.lease().toMillis() / 1000.0)
        > 0;
  }

  private Map<String, Object> owned(Job j) {
    var st = lock(j.JobID, false);
    if (st == null
        || !str(st, "status").equals("running")
        || !Objects.equals(st.get("lease_token"), j.LeaseToken)
        || Boolean.TRUE.equals(st.get("expired"))) return null;
    if (num(st, "version") != num(st, "problem_version")
        || !str(st, "run_status").equals("judging")) {
      obsolete(j.RunID);
      return null;
    }
    return st;
  }

  public boolean complete(Job j, Outcome o) {
    o.validate(j);
    return db.tx(
        () -> {
          if (owned(j) == null) return false;
          for (var t : o.Tests) {
            int count =
                db.update(
                    "INSERT INTO"
                        + " submission_test_results(judge_run_id,test_case_id,verdict,execution_time_ms,memory_used_kb)"
                        + " SELECT $1,id,$3,$4,$5 FROM test_cases WHERE id=$2 AND problem_id=$6",
                    j.RunID,
                    t.TestCaseID,
                    t.Verdict,
                    t.TimeMs,
                    t.MemoryKB,
                    j.ProblemID);
            if (count != 1) throw new IllegalArgumentException("test belongs to another problem");
          }
          db.update(
              "UPDATE judge_runs SET"
                  + " status='completed',verdict=$2,passed_tests=$3,total_tests=$4,compiler_info=NULLIF($5,''),compile_output=NULLIF($6,''),finished_at=GREATEST(clock_timestamp(),started_at)"
                  + " WHERE id=$1",
              j.RunID,
              o.Verdict,
              o.PassedTests,
              o.TotalTests,
              o.CompilerInfo,
              o.CompileOutput);
          db.update(
              "UPDATE judge_jobs SET"
                  + " status='done',worker_id=NULL,lease_token=NULL,lease_expires_at=NULL,last_error=NULL,updated_at=clock_timestamp()"
                  + " WHERE id=$1",
              j.JobID);
          notifyRun(j.RunID);
          return true;
        });
  }

  public boolean fail(Job j, String cause) {
    return db.tx(
        () -> {
          var st = owned(j);
          if (st == null) return false;
          failLocked(j.JobID, j.RunID, (int) num(st, "attempt_count"), cause);
          return true;
        });
  }

  private void failLocked(long job, long run, int attempt, String cause) {
    notifyRun(run);
    if (attempt >= 3) {
      db.update("DELETE FROM submission_test_results WHERE judge_run_id=$1", run);
      db.update(
          "UPDATE judge_runs SET"
              + " status='system_error',verdict=NULL,passed_tests=0,total_tests=0,system_error=$2,started_at=COALESCE(started_at,GREATEST(clock_timestamp(),created_at)),finished_at=GREATEST(clock_timestamp(),COALESCE(started_at,created_at))"
              + " WHERE id=$1",
          run,
          cause);
      db.update(
          "UPDATE judge_jobs SET"
              + " status='failed',worker_id=NULL,lease_token=NULL,lease_expires_at=NULL,last_error=$2,updated_at=clock_timestamp()"
              + " WHERE id=$1",
          job,
          cause);
    } else {
      reset(run);
      db.update(
          "UPDATE judge_jobs SET"
              + " status='queued',worker_id=NULL,lease_token=NULL,lease_expires_at=NULL,available_at=clock_timestamp()+make_interval(secs=>$3),last_error=$2,updated_at=clock_timestamp()"
              + " WHERE id=$1",
          job,
          cause,
          attempt <= 1 ? 5 : 30);
    }
  }

  public void recover() {
    for (var candidate :
        db.rows(
            "SELECT id FROM judge_jobs WHERE status='running' AND"
                + " lease_expires_at<=clock_timestamp() ORDER BY id LIMIT 100"))
      db.tx(
          () -> {
            long id = num(candidate, "id");
            var st = lock(id, true);
            if (st == null
                || !str(st, "status").equals("running")
                || !Boolean.TRUE.equals(st.get("expired"))) return null;
            if (num(st, "version") != num(st, "problem_version")) obsolete(num(st, "run_id"));
            else
              failLocked(
                  id,
                  num(st, "run_id"),
                  (int) num(st, "attempt_count"),
                  "lease expired (worker stopped or lost connection)");
            return null;
          });
  }
}
