package vn.lanlab;

import static org.junit.jupiter.api.Assertions.*;
import static vn.lanlab.Db.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class DatabaseTest {
  @TempDir Path temp;
  private Db db;
  private DriverManagerDataSource admin;
  private String name;
  private Config cfg;
  private Auth auth;
  private Exams exams;
  private Problems problems;
  private Students students;
  private Submissions subs;
  private JudgeQueue queue;
  private Auth.Principal teacher, student;
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @BeforeEach
  void setup() {
    cfg =
        new Config(
            Map.of(
                "DATABASE_URL",
                System.getenv("TEST_DATABASE_URL"),
                "JWT_SECRET",
                "database-test-secret",
                "DATA_DIR",
                temp.toString(),
                "WORKER_LEASE",
                "3s"));
    var props = cfg.datasource();
    admin =
        new DriverManagerDataSource(
            (String) props.get("spring.datasource.url"),
            (String) props.getOrDefault("spring.datasource.username", ""),
            (String) props.getOrDefault("spring.datasource.password", ""));
    name = "lan_java_test_" + UUID.randomUUID().toString().replace("-", "");
    new Db(admin).jdbc.execute("CREATE DATABASE " + name);
    String url =
        ((String) props.get("spring.datasource.url")).replaceFirst("/[^/?]+(?=\\?|$)", "/" + name);
    db = new Db(new DriverManagerDataSource(url, admin.getUsername(), admin.getPassword()));
    new Migrations(db).run("up");
    new Migrations(db).run("up");
    auth = new Auth(db, cfg, json);
    exams = new Exams(db);
    var storage = new Storage(temp);
    problems = new Problems(db, exams, storage);
    subs = new Submissions(db, exams, storage);
    students = new Students(db, auth);
    queue = new JudgeQueue(db, cfg);
    teacher =
        new Auth.Principal(
            num(
                db.need(
                    "teacher",
                    "INSERT INTO users(code,fullname,role,password_hash)"
                        + " VALUES('GV','Teacher','teacher',$1) RETURNING id",
                    auth.hash("secret")),
                "id"),
            "teacher",
            Instant.now().plusSeconds(3600));
    student =
        new Auth.Principal(
            num(students.create("SV", "Nguyễn Văn An", "5/9/2004"), "id"),
            "student",
            teacher.expires());
  }

  @AfterEach
  void cleanup() {
    if (admin != null && name != null)
      new Db(admin).jdbc.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
  }

  record Fixture(long exam, long problem, long session) {}

  Fixture fixture() throws Exception {
    long exam = num(exams.save(teacher, null, "Exam", ""), "id");
    var problem =
        problems.create(
            teacher,
            exam,
            map(
                "code",
                "a",
                "title",
                "Sum",
                "statement",
                "a+b",
                "time_limit_ms",
                1000,
                "memory_limit_mb",
                256));
    long pid = num(problem, "id");
    Path dir = temp.resolve(Problems.testDir(pid));
    Files.writeString(dir.resolve("1.in"), "1 2");
    Files.writeString(dir.resolve("1.out"), "3");
    problems.rejudge(teacher, pid);
    long sid =
        num(
            exams.saveSession(
                teacher,
                exam,
                null,
                "Session",
                Instant.now().minusSeconds(60).toString(),
                Instant.now().plusSeconds(3600).toString()),
            "id");
    exams.join(student, sid);
    return new Fixture(exam, pid, sid);
  }

  long submit(Fixture f) throws Exception {
    return num(
        subs.submit(
            student,
            f.session,
            f.problem,
            "main.cpp",
            "int main(){}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "id");
  }

  JudgeQueue.Outcome ac(JudgeQueue.Job job) {
    var o = new JudgeQueue.Outcome();
    o.Verdict = "AC";
    o.TotalTests = o.PassedTests = job.Tests.size();
    for (var tc : job.Tests) {
      var t = new JudgeQueue.TestOutcome();
      t.TestCaseID = tc.ID;
      t.Verdict = "AC";
      o.Tests.add(t);
    }
    return o;
  }

  @Test
  void existingHashesAndRefreshRotationRemainCompatible() {
    var login = auth.login("sv", "05092004");
    String access = str(login, "access_token"), refresh = str(login, "refresh_token");
    assertEquals(student.id(), auth.verify(access).id());
    assertNotNull(auth.refresh(refresh));
    assertThrows(ApiError.class, () -> auth.refresh(refresh));
    students.reset(student.id());
    assertDoesNotThrow(() -> auth.verify(access));
    assertEquals(0, num(db.one("SELECT count(*) AS n FROM refresh_tokens"), "n"));
  }

  @Test
  void invalidCsvRollsBackAllRowsAndExistingCodesAreSkipped() {
    String csv = "full_name,code,date_of_birth\nA,NEW1,5/9/2004\nB,NEW1,5/9/2004\n";
    assertThrows(
        ApiError.class,
        () -> students.importCsv(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertNull(db.one("SELECT id FROM users WHERE code='NEW1'"));
    var result =
        students.importCsv(
            "full_name,code,date_of_birth\nA,NEW1,5/9/2004\nB,SV,5/9/2004\nC,GV,5/9/2004\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(1, result.get("created_count"));
    assertEquals(2, result.get("skipped_count"));
  }

  @Test
  void finishIsIdempotentAndRejectsLaterSubmissions() throws Exception {
    var f = fixture();
    var first = exams.finish(student, f.session);
    assertEquals(first.get("finished_at"), exams.finish(student, f.session).get("finished_at"));
    assertThrows(ApiError.class, () -> submit(f));
    assertEquals(0, num(db.one("SELECT count(*) AS n FROM submissions"), "n"));
  }

  @Test
  void staleLeasesCannotOverwriteRejudgeAndOldAcIsRemoved() throws Exception {
    var f = fixture();
    long id = submit(f);
    var old = queue.claim("w1");
    assertNotNull(old);
    problems.rejudge(teacher, f.problem);
    assertFalse(queue.complete(old, ac(old)));
    var current = queue.claim("w2");
    assertNotNull(current);
    assertNotEquals(old.RunID, current.RunID);
    assertTrue(queue.complete(current, ac(current)));
    assertEquals("AC", subs.raw(id).get("verdict"));
    assertFalse(queue.complete(current, ac(current)));
    var board = new Board(db, exams, subs);
    assertTrue(
        json.valueToTree(board.summary(teacher, f.session))
                .path("rows")
                .get(0)
                .path("ac_count")
                .asInt()
            == 1);
    subs.rejudge(teacher, id);
    assertEquals("queued", subs.raw(id).get("status"));
    assertEquals(
        0,
        json.valueToTree(board.summary(teacher, f.session))
            .path("rows")
            .get(0)
            .path("ac_count")
            .asInt());
  }

  @Test
  void concurrentClaimsNeverTakeTheSameJob() throws Exception {
    var f = fixture();
    for (int i = 0; i < 12; i++) submit(f);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    Set<Long> ids = ConcurrentHashMap.newKeySet();
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        int n = i;
        tasks.add(
            pool.submit(
                () -> {
                  while (true) {
                    var job = queue.claim("worker-" + n);
                    if (job == null) return;
                    assertTrue(ids.add(job.JobID));
                    assertTrue(queue.complete(job, ac(job)));
                  }
                }));
      }
      for (var t : tasks) t.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(12, ids.size());
    assertEquals(
        12,
        num(
            db.one("SELECT count(*) AS n FROM judge_jobs WHERE status='done' AND attempt_count=1"),
            "n"));
  }

  @Test
  void expiredJobsRetryAndEventuallyBecomeSystemError() throws Exception {
    var f = fixture();
    long id = submit(f);
    for (int attempt = 1; attempt <= 3; attempt++) {
      var job = queue.claim("worker");
      assertEquals(attempt, job.Attempt);
      db.update(
          "UPDATE judge_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE"
              + " id=$1",
          job.JobID);
      assertFalse(queue.heartbeat(job));
      queue.recover();
      db.update(
          "UPDATE judge_jobs SET available_at=clock_timestamp()-interval '1 second' WHERE"
              + " status='queued'");
    }
    assertEquals("system_error", subs.raw(id).get("status"));
    assertNull(subs.raw(id).get("verdict"));
  }

  @Test
  void teacherOwnershipDoesNotLeakResources() throws Exception {
    var f = fixture();
    var other = new Auth.Principal(teacher.id() + 999, "teacher", teacher.expires());
    assertThrows(ApiError.class, () -> exams.detail(other, f.exam));
    assertThrows(ApiError.class, () -> problems.owned(other, f.problem));
    long sub = submit(f);
    assertThrows(ApiError.class, () -> subs.owned(other, sub));
  }

  @Test
  void tcpDispatchPersistsResultUsingTheExistingProtocol() throws Exception {
    var f = fixture();
    long id = submit(f);
    var env = new HashMap<>(cfg.env());
    env.put("DISPATCH_ADDR", "127.0.0.1:0");
    env.put("DISPATCH_TOKEN", "shared-token");
    env.put("WORKER_POLL_INTERVAL", "20ms");
    try (var dispatch = new Dispatch(new Config(env), queue, json);
        var socket = new java.net.Socket("127.0.0.1", dispatch.port());
        var wire = new Wire(socket, json)) {
      wire.timeout(5000);
      wire.send(
          Wire.HELLO,
          map("worker_id", "tcp-worker", "slots", 1, "version", 1, "token", "shared-token"));
      assertEquals(Wire.WELCOME, wire.read().type());
      var frame = wire.read();
      assertEquals(Wire.JOB, frame.type());
      var job = json.treeToValue(frame.json(json), JudgeQueue.Job.class);
      assertEquals(id, job.SubmissionID);
      wire.send(Wire.ACK, map("job_id", job.JobID, "accepted", true));
      wire.send(Wire.RESULT, map("job_id", job.JobID, "outcome", ac(job)));
      var receipt = wire.read();
      assertEquals(Wire.RESULT_OK, receipt.type());
      assertEquals("saved", receipt.json(json).path("status").asText());
      assertEquals("AC", subs.raw(id).get("verdict"));
    }
  }

  @Test
  void tcpDispatchRejectsTheWrongToken() throws Exception {
    var env = new HashMap<>(cfg.env());
    env.put("DISPATCH_ADDR", "127.0.0.1:0");
    env.put("DISPATCH_TOKEN", "shared-token");
    try (var dispatch = new Dispatch(new Config(env), queue, json);
        var socket = new java.net.Socket("127.0.0.1", dispatch.port());
        var wire = new Wire(socket, json)) {
      wire.timeout(5000);
      wire.send(Wire.HELLO, map("worker_id", "rogue", "slots", 1, "version", 1, "token", "wrong"));
      assertEquals(Wire.ERROR, wire.read().type());
    }
  }
}
