package vn.lanlab;

import static org.junit.jupiter.api.Assertions.*;
import static vn.lanlab.Db.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class ContractsTest {
  @TempDir Path temp;
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void wireMatchesGoGoldenBytesAndHandlesFragmentation() throws Exception {
    byte[] bytes = Wire.encode(1, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertArrayEquals(new byte[] {0, 0, 0, 3, 1, 123, 125}, bytes);
    InputStream fragmented =
        new ByteArrayInputStream(bytes) {
          @Override
          public synchronized int read(byte[] b, int off, int len) {
            return super.read(b, off, Math.min(1, len));
          }
        };
    var frame = Wire.read(new DataInputStream(fragmented));
    assertEquals(1, frame.type());
    assertEquals("{}", frame.json(json).toString());
    assertEquals(5, Wire.encode(7, new byte[0]).length);
    for (byte[] invalid :
        List.of(
            new byte[] {0, 0, 0, 0},
            new byte[] {0, 64, 0, 1},
            new byte[] {-1, -1, -1, -1},
            new byte[] {0, 0, 0, 2, 1}))
      assertThrows(
          IOException.class,
          () -> Wire.read(new DataInputStream(new ByteArrayInputStream(invalid))));
  }

  @Test
  void tcpSnapshotsPreserveCase() throws Exception {
    var job = new JudgeQueue.Job();
    job.JobID = 7;
    job.LeaseToken = "uuid";
    var tree = json.valueToTree(job);
    assertTrue(tree.has("JobID"));
    assertFalse(tree.has("jobID"));
    assertEquals(7, json.treeToValue(tree, JudgeQueue.Job.class).JobID);
  }

  @Test
  void comparesTokensWithoutChangingByteSemantics() throws Exception {
    assertTrue(tokens(" 123\t456\n", "123 456"));
    assertFalse(tokens("1 23", "12 3"));
    assertFalse(tokens("", "x"));
    assertTrue(tokens("\n\t", ""));
    assertTrue(tokens("a", "a\n "));
    String large = "x".repeat(150000);
    assertTrue(tokens(large + "\n", large));
    assertFalse(tokens(large, large + "x"));
    assertFalse(tokens("a\u00a0b", "a b"));
  }

  private boolean tokens(String a, String b) throws Exception {
    return CppJudge.sameTokens(
        new ByteArrayInputStream(a.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        new ByteArrayInputStream(b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  void testScanIsOrderedAndRejectsSymlinksAndIncompletePairs() throws Exception {
    Storage storage = new Storage(temp);
    var problems = new Problems(null, null, storage);
    Path dir = storage.resolve(Problems.testDir(1));
    Files.createDirectories(dir);
    for (String name : List.of("10", "2", "alpha")) {
      Files.writeString(dir.resolve(name + ".in"), "");
      Files.writeString(dir.resolve(name + ".out"), "");
    }
    assertEquals(
        List.of("2", "10", "alpha"), problems.scan(1).stream().map(r -> r.get("name")).toList());
    Files.writeString(dir.resolve("3.in"), "");
    assertThrows(ApiError.class, () -> problems.scan(1));
    Files.delete(dir.resolve("3.in"));
    Files.createSymbolicLink(dir.resolve("4.in"), dir.resolve("2.in"));
    assertThrows(ApiError.class, () -> problems.scan(1));
  }

  @Test
  void snapshotRejectsFilesEditedAfterScan() throws Exception {
    var storage = new Storage(temp);
    var problems = new Problems(null, null, storage);
    Path dir = storage.resolve(Problems.testDir(1));
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("1.in"), "1 2");
    Files.writeString(dir.resolve("1.out"), "3");
    var scanned = problems.scan(1);
    Files.writeString(dir.resolve("1.out"), "4");
    assertThrows(ApiError.class, () -> new TestSnapshots(storage).publish(1, scanned));
    try (var files = Files.list(temp.resolve("problems/1/testsets"))) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void sourcesRemainDurableAndInsideDataRoot() throws Exception {
    var storage = new Storage(temp);
    String rel = storage.save(new byte[] {0, -1, 3}, Instant.parse("2026-10-31T20:00:00Z"));
    assertTrue(rel.matches("submissions/2026/10/[a-f0-9]{32}\\.cpp"));
    assertArrayEquals(new byte[] {0, -1, 3}, storage.read(rel));
    assertThrows(IOException.class, () -> storage.resolve("../outside"));
    Files.createSymbolicLink(temp.resolve("escape"), temp.getParent());
    assertThrows(IOException.class, () -> storage.resolve("escape/secret"));
  }

  @Test
  void dateOfBirthAndPaginationKeepExistingRules() {
    for (String s : List.of("5/9/2004", "05-09-2004", "5.9.2004", "2004-9-5"))
      assertEquals(LocalDate.of(2004, 9, 5), Students.birth(s));
    for (String s : List.of("", "29/02/2003", "01/01/0205", "2050-1-1"))
      assertThrows(ApiError.class, () -> Students.birth(s));
    MockHttpServletRequest request = new MockHttpServletRequest();
    assertEquals(0, Api.paged(List.of(), request).get("totalPages"));
    request.addParameter("pageSize", "999");
    assertEquals(200, Api.paged(List.of(1, 2), request).get("pageSize"));
    request.addParameter("page", "5");
    assertEquals(List.of(), Api.paged(List.of(1), request).get("data"));
    request.addParameter("page", "6");
    assertThrows(ApiError.class, () -> Api.paged(List.of(), request));
  }

  @Test
  void sqlBindsRepeatedPostgresParameters() {
    var q = Db.bind("SELECT $2, $1::uuid, $2", "one", "two");
    assertEquals("SELECT ?, ?::uuid, ?", q.sql());
    assertArrayEquals(new Object[] {"two", "one", "two"}, q.args());
  }

  @Test
  void goStyleDurationsAndDatabaseUrls() {
    assertEquals(Duration.ofMillis(1500), Config.parseDuration("1s500ms"));
    assertThrows(IllegalArgumentException.class, () -> Config.parseDuration("3bad"));
    var cfg =
        new Config(
            Map.of("DATABASE_URL", "postgres://lab:p%40ss@localhost:5433/judge?sslmode=disable"));
    assertEquals("p@ss", cfg.datasource().get("spring.datasource.password"));
    assertEquals(
        "jdbc:postgresql://localhost:5433/judge?sslmode=disable",
        cfg.datasource().get("spring.datasource.url"));
  }

  private String jwt(String header, Map<String, Object> claims, String secret) throws Exception {
    var b64 = Base64.getUrlEncoder().withoutPadding();
    String body =
        b64.encodeToString(header.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "."
            + b64.encodeToString(json.writeValueAsBytes(claims));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    return body
        + "."
        + b64.encodeToString(
            mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  }

  @Test
  void verifiesHs256AndRejectsAlgorithmConfusionAndExpiredTokens() throws Exception {
    String secret = "contract-test-secret";
    var auth = new Auth(null, new Config(Map.of("JWT_SECRET", secret)), json);
    var claims =
        map(
            "sub",
            "12",
            "role",
            "student",
            "exp",
            Instant.now().plusSeconds(3600).getEpochSecond());
    String token = jwt("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", claims, secret);
    assertEquals(12, auth.verify(token).id());
    assertThrows(ApiError.class, () -> auth.verify(jwt("{\"alg\":\"none\"}", claims, secret)));
    assertThrows(ApiError.class, () -> auth.verify(token + "="));
    claims.put("exp", 1);
    assertThrows(ApiError.class, () -> auth.verify(jwt("{\"alg\":\"HS256\"}", claims, secret)));
    assertThrows(ApiError.class, () -> auth.hash("ắ".repeat(30)));
  }

  @Test
  void outcomeMustStopAtFirstFailingTest() {
    var job = new JudgeQueue.Job();
    var tc = new JudgeQueue.TestCase();
    tc.ID = 1;
    job.Tests = List.of(tc);
    var out = new JudgeQueue.Outcome();
    out.TotalTests = 1;
    out.Verdict = "AC";
    assertThrows(IllegalArgumentException.class, () -> out.validate(job));
    out.Verdict = "CE";
    out.validate(job);
    out.PassedTests = 1;
    assertThrows(IllegalArgumentException.class, () -> out.validate(job));
    out.PassedTests = 0;
    out.Tests = null;
    assertDoesNotThrow(() -> out.validate(job));
  }

  @Test
  void csvFormulaProtectionPreservesVietnamese() {
    assertEquals("'=@SUM(1)", Board.safeCell("=@SUM(1)"));
    assertEquals("Nguyễn Văn An", Board.safeCell("Nguyễn Văn An"));
  }

  @Test
  void unicodeAccountCodesKeepSimpleUppercaseAndWhitespaceRules() {
    assertEquals("SV01", Auth.code("\u00a0sv01\u0085"));
    assertEquals("STRAßE", Auth.code("straße"));
    assertThrows(ApiError.class, () -> Students.validate("SV\u00a001", "Name", "5/9/2004"));
  }

  @Test
  void everyDocumentedRestOperationStillHasAJavaRoute() throws Exception {
    Set<String> routes = new HashSet<>();
    for (var method : Api.class.getDeclaredMethods()) {
      var mapping =
          org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
              method, org.springframework.web.bind.annotation.RequestMapping.class);
      if (mapping != null)
        for (var verb : mapping.method())
          for (String path : mapping.value())
            routes.add(verb.name() + " " + path.replaceAll("\\{[^}]+}", "{}"));
    }
    var swagger = json.readTree(getClass().getResourceAsStream("/static/swagger/swagger.json"));
    for (var paths = swagger.path("paths").fields(); paths.hasNext(); ) {
      var path = paths.next();
      for (var verbs = path.getValue().fieldNames(); verbs.hasNext(); ) {
        String verb = verbs.next();
        if (Set.of("get", "post", "put", "delete", "patch").contains(verb))
          assertTrue(
              routes.contains(
                  verb.toUpperCase(Locale.ROOT)
                      + " "
                      + path.getKey().replaceAll("\\{[^}]+}", "{}")),
              path.getKey());
      }
    }
  }

  @Test
  void jsonBodyLimitAppliesBeforeDecoding() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.setContentType("application/json");
    request.setContent(new byte[(1 << 20) + 1]);
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    new Api.AccessFilter(null, json, new Config(Map.of()))
        .doFilter(request, response, (r, s) -> fail("oversize body reached the handler"));
    assertEquals(400, response.getStatus());
  }
}
