package vn.lanlab;

import static vn.lanlab.Db.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Command-line clients use the public HTTP/WebSocket contracts, independent of the server
 * implementation.
 */
public final class ClientTools {
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final String base;
  private final AtomicLong requests = new AtomicLong(),
      bytes = new AtomicLong(),
      wsMessages = new AtomicLong(),
      wsBytes = new AtomicLong();
  private final ConcurrentMap<String, AtomicLong> statuses = new ConcurrentHashMap<>();

  private void count(int status) {
    statuses.computeIfAbsent("" + status, k -> new AtomicLong()).incrementAndGet();
  }

  private ClientTools(ObjectMapper json, String base) {
    this.json = json;
    this.base = base.replaceAll("/+$", "");
  }

  private JsonNode request(String method, String path, String token, Object body) throws Exception {
    byte[] data = body == null ? new byte[0] : json.writeValueAsBytes(body);
    var b =
        HttpRequest.newBuilder(URI.create(base + "/api/v1" + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json");
    if (token != null) b.header("Authorization", "Bearer " + token);
    var r =
        http.send(
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(data)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    requests.incrementAndGet();
    count(r.statusCode());
    bytes.addAndGet(data.length + r.body().length + 400);
    if (r.statusCode() >= 300)
      throw new IOException(
          method
              + " "
              + path
              + ": "
              + r.statusCode()
              + " "
              + new String(r.body(), StandardCharsets.UTF_8));
    return r.body().length == 0 ? json.nullNode() : json.readTree(r.body());
  }

  private String login(String code, String password) throws Exception {
    return request("POST", "/auth/login", null, map("code", code, "password", password))
        .path("access_token")
        .asText();
  }

  private JsonNode upload(String path, String token, String filename, byte[] data)
      throws Exception {
    String boundary = "lanlab" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(data);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var r =
        http.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    requests.incrementAndGet();
    count(r.statusCode());
    bytes.addAndGet(body.size() + r.body().length + 400);
    if (r.statusCode() >= 300)
      throw new IOException(
          "upload: " + r.statusCode() + " " + new String(r.body(), StandardCharsets.UTF_8));
    return json.readTree(r.body());
  }

  private WebSocket websocket(
      String token, long session, java.util.function.Consumer<JsonNode> message) throws Exception {
    var ready = new CompletableFuture<Void>();
    WebSocket socket =
        http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(
                URI.create("ws" + base.substring(4) + "/api/v1/ws"),
                new WebSocket.Listener() {
                  final StringBuilder text = new StringBuilder();

                  @Override
                  public void onOpen(WebSocket ws) {
                    try {
                      ws.sendText(
                              json.writeValueAsString(
                                  map("type", "auth", "data", map("token", token))),
                              true)
                          .join();
                      if (session > 0)
                        ws.sendText(
                                json.writeValueAsString(
                                    map("type", "subscribe", "data", map("session_id", session))),
                                true)
                            .join();
                      ws.request(1);
                    } catch (Exception e) {
                      throw new CompletionException(e);
                    }
                  }

                  @Override
                  public CompletionStage<?> onText(WebSocket ws, CharSequence part, boolean last) {
                    text.append(part);
                    if (last) {
                      try {
                        wsMessages.incrementAndGet();
                        wsBytes.addAndGet(
                            text.toString().getBytes(StandardCharsets.UTF_8).length + 4);
                        var parsed = json.readTree(text.toString());
                        if (parsed
                            .path("type")
                            .asText()
                            .equals(session > 0 ? "subscribed" : "auth.ok")) ready.complete(null);
                        message.accept(parsed);
                      } catch (Exception e) {
                        throw new CompletionException(e);
                      } finally {
                        text.setLength(0);
                      }
                    }
                    ws.request(1);
                    return null;
                  }

                  @Override
                  public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer data) {
                    ws.request(1);
                    return ws.sendPong(data);
                  }

                  @Override
                  public void onError(WebSocket ws, Throwable e) {
                    System.err.println("WebSocket: " + e.getMessage());
                  }
                })
            .get(15, TimeUnit.SECONDS);
    try {
      ready.get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      socket.abort();
      throw e;
    }
    return socket;
  }

  public static void run(String mode, String[] args, ObjectMapper json) throws Exception {
    Map<String, String> flags = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      if (i + 1 >= args.length) throw new IllegalArgumentException("missing flag value");
      flags.put(args[i], args[i + 1]);
    }
    var client = new ClientTools(json, flags.getOrDefault("-base", "http://localhost:8080"));
    if (mode.equals("wsprobe")) {
      String token =
          client.login(
              flags.getOrDefault("-code", ""),
              flags.getOrDefault(
                  "-password", System.getenv().getOrDefault("WSPROBE_PASSWORD", "")));
      WebSocket ws =
          client.websocket(
              token,
              Long.parseLong(flags.getOrDefault("-session", "0")),
              m -> System.out.println(Instant.now() + " " + m));
      try {
        Thread.sleep(Config.parseDuration(flags.getOrDefault("-duration", "30s")).toMillis());
      } finally {
        ws.sendClose(1000, "").get(5, TimeUnit.SECONDS);
      }
      return;
    }
    client.load(flags);
  }

  private static final String PROGRAM =
      "#include <cstdio>\n"
          + "int main(){long long a,b;if(scanf(\"%lld %lld\",&a,&b)!=2)return 0;printf(\"%lld\\n"
          + "\",a+b);}\n";

  private void load(Map<String, String> f) throws Exception {
    int n = Integer.parseInt(f.getOrDefault("-students", "50")),
        tests = Integer.parseInt(f.getOrDefault("-tests", "15")),
        submit = Integer.parseInt(f.getOrDefault("-submit", "0"));
    if (n < 1 || n > 1000 || tests < 1 || tests > 500)
      throw new IllegalArgumentException("invalid students/tests");
    if (submit <= 0 || submit > n) submit = n;
    String mode = f.getOrDefault("-mode", "ws");
    if (!List.of("ws", "poll").contains(mode))
      throw new IllegalArgumentException("-mode must be ws or poll");
    Duration timeout = Config.parseDuration(f.getOrDefault("-timeout", "10m"));
    String teacher =
        login(
            f.getOrDefault("-teacher-code", System.getenv().getOrDefault("SMOKE_TEACHER_CODE", "")),
            f.getOrDefault(
                "-teacher-password", System.getenv().getOrDefault("SMOKE_TEACHER_PASSWORD", "")));
    String run = "L" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    long exam =
        request("POST", "/exams", teacher, map("title", "Load " + run, "description", ""))
            .path("id")
            .asLong();
    long problem =
        request(
                "POST",
                "/exams/" + exam + "/problems",
                teacher,
                map(
                    "code",
                    "A",
                    "title",
                    "Sum",
                    "statement",
                    "a+b",
                    "time_limit_ms",
                    1000,
                    "memory_limit_mb",
                    256))
            .path("id")
            .asLong();
    Path dir = Path.of(f.getOrDefault("-data", "./data")).resolve(Problems.testDir(problem));
    Files.createDirectories(dir);
    for (int i = 1; i <= tests; i++) {
      Files.writeString(dir.resolve(i + ".in"), i + " " + i + "\n");
      Files.writeString(dir.resolve(i + ".out"), (2 * i) + "\n");
    }
    request("POST", "/problems/" + problem + "/rejudge", teacher, null);
    long session =
        request(
                "POST",
                "/exams/" + exam + "/sessions",
                teacher,
                map(
                    "name",
                    "load",
                    "starts_at",
                    Instant.now().minusSeconds(60).toString(),
                    "ends_at",
                    Instant.now().plusSeconds(3600).toString()))
            .path("id")
            .asLong();
    StringBuilder csv = new StringBuilder("full_name,code,date_of_birth\n");
    for (int i = 0; i < n; i++)
      csv.append("Load ").append(i).append(',').append(run).append(i).append(",01/01/2004\n");
    upload(
        "/students/import",
        teacher,
        "students.csv",
        csv.toString().getBytes(StandardCharsets.UTF_8));
    requests.set(0);
    bytes.set(0);
    statuses.clear();
    List<Double> loginTimes = Collections.synchronizedList(new ArrayList<>()),
        submitTimes = Collections.synchronizedList(new ArrayList<>()),
        e2e = Collections.synchronizedList(new ArrayList<>());
    List<WebSocket> sockets = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch ready = new CountDownLatch(n), go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<?>> tasks = new ArrayList<>();
    int total = submit;
    List<String> errors = new CopyOnWriteArrayList<>();
    List<Long> submissionIds = new CopyOnWriteArrayList<>();
    ConcurrentMap<String, AtomicLong> verdicts = new ConcurrentHashMap<>();
    long began = System.nanoTime(), requestsBefore = 0, bytesBefore = 0;
    Map<String, Object> dbBefore = Map.of();
    Db metrics = metrics(f.getOrDefault("-db", System.getenv().getOrDefault("LOADTEST_DB", "")));
    try {
      for (int i = 0; i < n; i++) {
        int student = i;
        tasks.add(
            pool.submit(
                () -> {
                  boolean signaled = false;
                  try {
                    long start = System.nanoTime();
                    String token = login(run + student, "01012004");
                    loginTimes.add(ms(start));
                    request("POST", "/sessions/" + session + "/join", token, null);
                    CompletableFuture<JsonNode> done = new CompletableFuture<>();
                    if (mode.equals("ws"))
                      sockets.add(
                          websocket(
                              token,
                              session,
                              m -> {
                                if (m.path("type").asText().equals("run.updated")
                                    && List.of("completed", "system_error")
                                        .contains(
                                            m.path("data").path("result").path("status").asText()))
                                  done.complete(m.path("data"));
                              }));
                    ready.countDown();
                    signaled = true;
                    go.await();
                    if (student >= total) return;
                    start = System.nanoTime();
                    var response =
                        upload(
                            "/sessions/" + session + "/problems/" + problem + "/submissions",
                            token,
                            "main.cpp",
                            PROGRAM.getBytes(StandardCharsets.UTF_8));
                    submitTimes.add(ms(start));
                    submissionIds.add(response.path("id").asLong());
                    JsonNode finalResult;
                    if (mode.equals("ws")) {
                      finalResult = done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    } else {
                      long deadline = System.nanoTime() + timeout.toNanos();
                      while (true) {
                        try {
                          var sub =
                              request(
                                  "GET",
                                  "/submissions/" + response.path("id").asLong(),
                                  token,
                                  null);
                          if (List.of("completed", "system_error")
                              .contains(sub.path("result").path("status").asText())) {
                            finalResult = sub;
                            break;
                          }
                        } catch (IOException ignored) {
                          // The restart experiment polls through temporary API outages.
                        }
                        if (System.nanoTime() > deadline) throw new TimeoutException();
                        Thread.sleep(
                            Config.parseDuration(f.getOrDefault("-poll-interval", "1s"))
                                .toMillis());
                      }
                    }
                    e2e.add(ms(start));
                    String verdict =
                        finalResult.path("result").path("verdict").asText("system_error");
                    verdicts.computeIfAbsent(verdict, k -> new AtomicLong()).incrementAndGet();
                  } catch (Exception e) {
                    errors.add(e.toString());
                  } finally {
                    if (!signaled) ready.countDown();
                  }
                }));
      }
      if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
        throw new TimeoutException("students failed to connect");
      requestsBefore = requests.get();
      bytesBefore = bytes.get();
      dbBefore = dbCounters(metrics);
      began = System.nanoTime();
      go.countDown();
      for (Future<?> task : tasks) task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } finally {
      go.countDown();
      pool.shutdownNow();
      for (WebSocket ws : sockets) ws.sendClose(1000, "");
    }
    double makespan = ms(began);
    var result =
        map(
            "label",
            f.getOrDefault("-label", ""),
            "mode",
            mode,
            "students",
            n,
            "submissions",
            submit,
            "tests",
            tests,
            "at",
            Instant.now(),
            "login",
            stats(loginTimes),
            "submit_202",
            stats(submitTimes),
            "submit_to_result",
            stats(e2e),
            "makespan_ms",
            makespan,
            "throughput_per_s",
            submit / (makespan / 1000),
            "wait_phase_requests",
            requests.get() - requestsBefore,
            "wait_phase_bytes",
            bytes.get() - bytesBefore + wsBytes.get(),
            "ws_messages",
            wsMessages.get(),
            "http_status",
            atomicMap(statuses),
            "verdicts",
            atomicMap(verdicts));
    if (!errors.isEmpty()) result.put("errors", errors);
    addServerMetrics(result, metrics, dbBefore, submissionIds);
    String line = json.writeValueAsString(result);
    System.out.println(line);
    if (f.containsKey("-out")) {
      Path out = Path.of(f.get("-out"));
      if (out.getParent() != null) Files.createDirectories(out.getParent());
      Files.writeString(out, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
  }

  private static Map<String, Long> atomicMap(Map<String, AtomicLong> values) {
    Map<String, Long> out = new TreeMap<>();
    values.forEach((k, v) -> out.put(k, v.get()));
    return out;
  }

  private Db metrics(String url) {
    if (url.isEmpty()) return null;
    try {
      var props = new Config(Map.of("DATABASE_URL", url)).datasource();
      return new Db(
          new org.springframework.jdbc.datasource.DriverManagerDataSource(
              (String) props.get("spring.datasource.url"),
              (String) props.getOrDefault("spring.datasource.username", ""),
              (String) props.getOrDefault("spring.datasource.password", "")));
    } catch (Exception e) {
      return null;
    }
  }

  private Map<String, Object> dbCounters(Db db) {
    if (db == null) return Map.of();
    try {
      return db.one(
          "SELECT xact_commit+xact_rollback AS xact,tup_returned AS tuples FROM pg_stat_database"
              + " WHERE datname=current_database()");
    } catch (Exception e) {
      return Map.of();
    }
  }

  private void addServerMetrics(
      Map<String, Object> result, Db db, Map<String, Object> before, List<Long> ids) {
    if (db == null) return;
    try {
      var after = dbCounters(db);
      if (!before.isEmpty() && !after.isEmpty()) {
        result.put("db_xact_during_wait", num(after, "xact") - num(before, "xact"));
        result.put("db_tuples_returned_during_wait", num(after, "tuples") - num(before, "tuples"));
      }
      List<Double> wait = new ArrayList<>(), judge = new ArrayList<>();
      for (long id : ids)
        for (var row :
            db.rows(
                "SELECT EXTRACT(EPOCH FROM started_at-created_at)*1000 AS wait,EXTRACT(EPOCH FROM"
                    + " finished_at-started_at)*1000 AS judge FROM judge_runs WHERE"
                    + " submission_id=$1 AND status='completed'",
                id)) {
          wait.add(((Number) row.get("wait")).doubleValue());
          judge.add(((Number) row.get("judge")).doubleValue());
        }
      result.put("server_queue_wait", stats(wait));
      result.put("server_judge", stats(judge));
    } catch (Exception e) {
      System.err.println("server metrics unavailable: " + e.getMessage());
    }
  }

  private static double ms(long start) {
    return (System.nanoTime() - start) / 1e6;
  }

  static Map<String, Object> stats(List<Double> values) {
    if (values.isEmpty()) return map("n", 0, "p50_ms", 0, "p95_ms", 0, "max_ms", 0, "mean_ms", 0);
    var sorted = values.stream().sorted().toList();
    return map(
        "n",
        sorted.size(),
        "p50_ms",
        sorted.get(sorted.size() / 2),
        "p95_ms",
        sorted.get(sorted.size() * 95 / 100),
        "max_ms",
        sorted.get(sorted.size() - 1),
        "mean_ms",
        sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0));
  }
}
