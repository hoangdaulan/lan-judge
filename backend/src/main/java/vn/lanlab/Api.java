package vn.lanlab;

import static vn.lanlab.Db.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@ConditionalOnWebApplication
public final class Api {
  private final Auth auth;
  private final Students students;
  private final Exams exams;
  private final Problems problems;
  private final Submissions subs;
  private final Board board;
  private final Db db;

  public Api(
      Auth auth,
      Students students,
      Exams exams,
      Problems problems,
      Submissions subs,
      Board board,
      Db db) {
    this.auth = auth;
    this.students = students;
    this.exams = exams;
    this.problems = problems;
    this.subs = subs;
    this.board = board;
    this.db = db;
  }

  static Auth.Principal p(HttpServletRequest r) {
    return (Auth.Principal) r.getAttribute("principal");
  }

  static long id(long n) {
    if (n < 1) throw ApiError.bad("invalid id");
    return n;
  }

  static String text(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (v != null && !(v instanceof String)) throw ApiError.bad("invalid " + key);
    return v == null ? "" : (String) v;
  }

  static void keys(Map<String, Object> body, String... keys) {
    Set<String> allowed = Set.of(keys);
    for (String key : body.keySet())
      if (!allowed.contains(key))
        throw ApiError.bad("invalid JSON body: json: unknown field \"" + key + "\"");
  }

  static Map<String, Object> paged(List<?> rows, HttpServletRequest r) {
    return paged(rows, r, Map.of());
  }

  static Map<String, Object> paged(List<?> rows, HttpServletRequest r, Map<String, Object> extra) {
    int page = positive(r, "page", 1), size = Math.min(200, positive(r, "pageSize", 20));
    long start = (long) (page - 1) * size;
    int a = (int) Math.min(start, rows.size()), b = (int) Math.min(start + size, rows.size());
    var out =
        map(
            "data",
            rows.subList(a, b),
            "page",
            page,
            "pageSize",
            size,
            "totalPages",
            (rows.size() + size - 1) / size);
    out.putAll(extra);
    return out;
  }

  static int positive(HttpServletRequest r, String name, int fallback) {
    String[] values = r.getParameterValues(name);
    if (values == null) return fallback;
    if (values.length != 1) throw ApiError.bad(name + " must be a positive integer");
    String s = values[0];
    try {
      int n = Integer.parseInt(s);
      if (n > 0) return n;
    } catch (NumberFormatException ignored) {
    }
    throw ApiError.bad(name + " must be a positive integer");
  }

  static long filter(HttpServletRequest r, String name) {
    String s = r.getParameter(name);
    if (s == null || s.isEmpty()) return 0;
    try {
      long n = Long.parseLong(s);
      if (n > 0) return n;
    } catch (NumberFormatException ignored) {
    }
    throw ApiError.bad("invalid " + name);
  }

  static ResponseEntity<Object> status(int status, Object body) {
    return ResponseEntity.status(status).body(body);
  }

  @GetMapping("/healthz")
  public ResponseEntity<Object> health() {
    try {
      db.one("SELECT 1");
      return status(200, map("status", "ok", "db", "up"));
    } catch (Exception e) {
      return status(503, map("status", "unavailable", "db", "down"));
    }
  }

  @GetMapping(value = "/swagger/doc.json", produces = "application/json")
  public Object swagger() {
    return new org.springframework.core.io.ClassPathResource("static/swagger/swagger.json");
  }

  @GetMapping(value = "/swagger/swagger-initializer.js", produces = "application/javascript")
  public String swaggerInitializer() {
    return "window.onload=function(){window.ui=SwaggerUIBundle({url:'/swagger/doc.json',dom_id:'#swagger-ui',presets:[SwaggerUIBundle.presets.apis,SwaggerUIStandalonePreset],layout:'StandaloneLayout',persistAuthorization:false});};";
  }

  @PostMapping("/api/v1/auth/login")
  public Object login(@RequestBody Map<String, Object> b) {
    keys(b, "code", "password");
    return auth.login(text(b, "code"), text(b, "password"));
  }

  @PostMapping("/api/v1/auth/refresh")
  public Object refresh(@RequestBody Map<String, Object> b) {
    keys(b, "refresh_token");
    return auth.refresh(text(b, "refresh_token"));
  }

  @PostMapping("/api/v1/auth/logout")
  public Object logout(@RequestBody Map<String, Object> b) {
    keys(b, "refresh_token");
    auth.logout(text(b, "refresh_token"));
    return status(204, null);
  }

  @GetMapping("/api/v1/me")
  public Object me(HttpServletRequest r) {
    return auth.user(p(r).id());
  }

  @PutMapping("/api/v1/me/password")
  public Object password(HttpServletRequest r, @RequestBody Map<String, Object> b) {
    keys(b, "current_password", "new_password");
    auth.changePassword(p(r), text(b, "current_password"), text(b, "new_password"));
    return status(204, null);
  }

  @GetMapping("/api/v1/students")
  public Object students(HttpServletRequest r, @RequestParam(defaultValue = "") String query) {
    Exams.role(p(r), true);
    return paged(students.list(query), r);
  }

  @PostMapping("/api/v1/students")
  public Object student(HttpServletRequest r, @RequestBody Map<String, Object> b) {
    Exams.role(p(r), true);
    keys(b, "code", "fullname", "date_of_birth");
    return status(
        201, students.create(text(b, "code"), text(b, "fullname"), text(b, "date_of_birth")));
  }

  @PostMapping("/api/v1/students/import")
  public Object importStudents(HttpServletRequest r, @RequestParam("file") MultipartFile f)
      throws IOException {
    Exams.role(p(r), true);
    return students.importCsv(f.getBytes());
  }

  @PostMapping("/api/v1/students/{id}/reset-password")
  public Object reset(HttpServletRequest r, @PathVariable long id) {
    Exams.role(p(r), true);
    students.reset(id(id));
    return status(204, null);
  }

  @GetMapping("/api/v1/exams")
  public Object exams(HttpServletRequest r) {
    return paged(exams.list(p(r)), r);
  }

  @PostMapping("/api/v1/exams")
  public Object createExam(HttpServletRequest r, @RequestBody Map<String, Object> b) {
    keys(b, "title", "description");
    return status(201, exams.save(p(r), null, text(b, "title"), text(b, "description")));
  }

  @GetMapping("/api/v1/exams/{id}")
  public Object exam(HttpServletRequest r, @PathVariable long id) {
    return exams.detail(p(r), id(id));
  }

  @PutMapping("/api/v1/exams/{id}")
  public Object updateExam(
      HttpServletRequest r, @PathVariable long id, @RequestBody Map<String, Object> b) {
    keys(b, "title", "description");
    return exams.save(p(r), id(id), text(b, "title"), text(b, "description"));
  }

  @DeleteMapping("/api/v1/exams/{id}")
  public Object deleteExam(HttpServletRequest r, @PathVariable long id) {
    exams.delete(p(r), id(id));
    return status(204, null);
  }

  @GetMapping("/api/v1/exams/{id}/sessions")
  public Object sessions(HttpServletRequest r, @PathVariable long id) {
    return paged(exams.sessions(p(r), id(id)), r);
  }

  @PostMapping("/api/v1/exams/{id}/sessions")
  public Object createSession(
      HttpServletRequest r, @PathVariable long id, @RequestBody Map<String, Object> b) {
    keys(b, "name", "starts_at", "ends_at");
    return status(
        201,
        exams.saveSession(
            p(r), id(id), null, text(b, "name"), text(b, "starts_at"), text(b, "ends_at")));
  }

  @PutMapping("/api/v1/sessions/{id}")
  public Object updateSession(
      HttpServletRequest r, @PathVariable long id, @RequestBody Map<String, Object> b) {
    keys(b, "name", "starts_at", "ends_at");
    return exams.saveSession(
        p(r), null, id(id), text(b, "name"), text(b, "starts_at"), text(b, "ends_at"));
  }

  @DeleteMapping("/api/v1/sessions/{id}")
  public Object deleteSession(HttpServletRequest r, @PathVariable long id) {
    exams.deleteSession(p(r), id(id));
    return status(204, null);
  }

  @GetMapping("/api/v1/sessions/{id}/participants")
  public Object participants(HttpServletRequest r, @PathVariable long id) {
    return paged(exams.participants(p(r), id(id)), r);
  }

  @GetMapping("/api/v1/me/sessions")
  public Object mine(HttpServletRequest r) {
    return paged(exams.sessions(p(r), null), r, map("server_time", java.time.Instant.now()));
  }

  @GetMapping("/api/v1/sessions/{id}")
  public Object session(HttpServletRequest r, @PathVariable long id) {
    return exams.view(p(r), id(id));
  }

  @PostMapping("/api/v1/sessions/{id}/join")
  public Object join(HttpServletRequest r, @PathVariable long id) {
    return exams.join(p(r), id(id));
  }

  @PostMapping("/api/v1/sessions/{id}/finish")
  public Object finish(HttpServletRequest r, @PathVariable long id) {
    return exams.finish(p(r), id(id));
  }

  @GetMapping("/api/v1/exams/{id}/problems")
  public Object problems(HttpServletRequest r, @PathVariable long id) {
    return paged(problems.list(p(r), id(id)), r);
  }

  @PostMapping("/api/v1/exams/{id}/problems")
  public Object problemCreate(
      HttpServletRequest r, @PathVariable long id, @RequestBody Map<String, Object> b)
      throws IOException {
    keys(b, "code", "title", "statement", "time_limit_ms", "memory_limit_mb");
    return status(201, problems.create(p(r), id(id), b));
  }

  @GetMapping("/api/v1/problems/{id}")
  public Object problem(HttpServletRequest r, @PathVariable long id) {
    return Problems.publicProblem(problems.owned(p(r), id(id)));
  }

  @PutMapping("/api/v1/problems/{id}")
  public Object problemUpdate(
      HttpServletRequest r, @PathVariable long id, @RequestBody Map<String, Object> b) {
    keys(b, "code", "title", "statement", "time_limit_ms", "memory_limit_mb");
    return problems.update(p(r), id(id), b);
  }

  @DeleteMapping("/api/v1/problems/{id}")
  public Object problemDelete(HttpServletRequest r, @PathVariable long id) {
    problems.delete(p(r), id(id));
    return status(204, null);
  }

  @GetMapping("/api/v1/problems/{id}/tests")
  public Object tests(HttpServletRequest r, @PathVariable long id) throws IOException {
    problems.owned(p(r), id(id));
    var tests = problems.tests(id);
    return paged(tests, r, map("test_dir", Problems.testDir(id), "disk", problems.disk(id, tests)));
  }

  @PostMapping("/api/v1/problems/{id}/rejudge")
  public Object problemRejudge(HttpServletRequest r, @PathVariable long id) throws IOException {
    return problems.rejudge(p(r), id(id));
  }

  @GetMapping("/api/v1/sessions/{id}/problems/{pid}")
  public Object studentProblem(
      HttpServletRequest r, @PathVariable long id, @PathVariable long pid) {
    return problems.student(p(r), id(id), id(pid));
  }

  @PostMapping("/api/v1/sessions/{id}/problems/{pid}/submissions")
  public Object submit(
      HttpServletRequest r,
      @PathVariable long id,
      @PathVariable long pid,
      @RequestParam("file") MultipartFile f)
      throws IOException {
    Exams.role(p(r), false);
    if (f.getSize() > 65536) throw new ApiError(413, "the file is larger than 64 KB");
    return status(
        202,
        subs.submit(
            p(r), id(id), id(pid), Objects.toString(f.getOriginalFilename(), ""), f.getBytes()));
  }

  @GetMapping("/api/v1/sessions/{id}/submissions")
  public Object submissions(HttpServletRequest r, @PathVariable long id) {
    return paged(subs.list(p(r), id(id), filter(r, "student_id"), filter(r, "problem_id")), r);
  }

  @GetMapping("/api/v1/submissions/{id}")
  public Object submission(HttpServletRequest r, @PathVariable long id) {
    return Submissions.response(subs.owned(p(r), id(id)), true, p(r).teacher());
  }

  @GetMapping("/api/v1/submissions/{id}/source")
  public Object source(HttpServletRequest r, @PathVariable long id) throws IOException {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/plain; charset=utf-8"))
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition", "attachment; filename=\"submission-" + id + ".cpp\"")
        .body(subs.source(p(r), id(id)));
  }

  @GetMapping("/api/v1/submissions/{id}/tests")
  public Object submissionTests(HttpServletRequest r, @PathVariable long id) {
    var results = subs.tests(p(r), id(id));
    return paged(
        results, r, map("submission", Submissions.response(subs.owned(p(r), id), true, true)));
  }

  @PostMapping("/api/v1/submissions/{id}/rejudge")
  public Object rejudge(HttpServletRequest r, @PathVariable long id) {
    return status(202, subs.rejudge(p(r), id(id)));
  }

  @GetMapping("/api/v1/sessions/{id}/summary")
  public Object summary(HttpServletRequest r, @PathVariable long id) {
    Exams.role(p(r), true);
    return board.summary(p(r), id(id));
  }

  @GetMapping("/api/v1/sessions/{id}/summary/me")
  public Object studentSummary(HttpServletRequest r, @PathVariable long id) {
    Exams.role(p(r), false);
    return board.summary(p(r), id(id));
  }

  @GetMapping("/api/v1/sessions/{id}/announcements")
  public Object announcements(HttpServletRequest r, @PathVariable long id) {
    return map("data", board.announcements(p(r), id(id)));
  }

  @PostMapping("/api/v1/sessions/{id}/announcements")
  public Object announce(
      HttpServletRequest r, @PathVariable long id, @RequestBody Map<String, Object> b) {
    keys(b, "text");
    return status(201, board.announce(p(r), id(id), text(b, "text")));
  }

  @GetMapping("/api/v1/sessions/{id}/export.csv")
  public Object export(HttpServletRequest r, @PathVariable long id) throws IOException {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
        .header("Content-Disposition", "attachment; filename=\"session-" + id + "-summary.csv\"")
        .body(board.export(p(r), id(id)));
  }

  @GetMapping("/api/v1/admin/queue")
  public Object queue(HttpServletRequest r) {
    Exams.role(p(r), true);
    return board.queue();
  }

  public static final class AccessFilter implements Filter {
    private final Auth auth;
    private final ObjectMapper json;
    private final Config cfg;

    public AccessFilter(Auth auth, ObjectMapper json, Config cfg) {
      this.auth = auth;
      this.json = json;
      this.cfg = cfg;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      var r = (HttpServletRequest) request;
      var w = (HttpServletResponse) response;
      String path = r.getRequestURI();
      try {
        if (path.equals("/") || path.equals("/index.html"))
          w.setHeader("Cache-Control", "no-cache");
        if (path.equals("/")
            && r.getMethod().equals("GET")
            && !r.isSecure()
            && !cfg.get("HTTPS_ADDR").isEmpty()) {
          String host = r.getServerName();
          if (!host.equals("localhost")
              && !(Tls.literalIp(host) && InetAddress.getByName(host).isLoopbackAddress())) {
            w.sendRedirect(
                "https://"
                    + (host.contains(":") ? "[" + host + "]" : host)
                    + ":"
                    + cfg.get("HTTPS_PUBLIC_PORT", "8443")
                    + "/");
            return;
          }
        }
        if (path.startsWith("/api/v1/")
            && !path.equals("/api/v1/ws")
            && !Set.of("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")
                .contains(path)) {
          String header = r.getHeader("Authorization");
          if (header == null
              || header.length() < 8
              || !header.substring(0, 7).equalsIgnoreCase("Bearer "))
            throw new ApiError(401, "missing bearer token");
          r.setAttribute("principal", auth.verify(header.substring(7)));
        }
        if (path.startsWith("/api/v1/")
            && Objects.toString(r.getContentType(), "").startsWith("application/json")) {
          if (r.getContentLengthLong() > (1 << 20))
            throw ApiError.bad("invalid JSON body: request body too large");
          r = limitedJson(r);
        }
        chain.doFilter(r, w);
      } catch (ApiError e) {
        w.setStatus(e.status);
        w.setContentType("application/json");
        json.writeValue(w.getOutputStream(), e.body);
      }
    }

    private static HttpServletRequest limitedJson(HttpServletRequest request) {
      return new HttpServletRequestWrapper(request) {
        private ServletInputStream input;

        @Override
        public ServletInputStream getInputStream() throws IOException {
          if (input == null) {
            ServletInputStream source = super.getInputStream();
            input =
                new ServletInputStream() {
                  private int count;

                  private void count(int n) {
                    if (n > 0 && (count += n) > (1 << 20))
                      throw ApiError.bad("invalid JSON body: request body too large");
                  }

                  @Override
                  public int read() throws IOException {
                    int b = source.read();
                    count(b < 0 ? 0 : 1);
                    return b;
                  }

                  @Override
                  public int read(byte[] b, int off, int len) throws IOException {
                    int n = source.read(b, off, Math.min(len, (1 << 20) - count + 1));
                    count(n);
                    return n;
                  }

                  @Override
                  public boolean isFinished() {
                    return source.isFinished();
                  }

                  @Override
                  public boolean isReady() {
                    return source.isReady();
                  }

                  @Override
                  public void setReadListener(ReadListener listener) {
                    source.setReadListener(listener);
                  }
                };
          }
          return input;
        }
      };
    }
  }
}

@RestControllerAdvice
@ConditionalOnWebApplication
class Errors {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> api(ApiError e) {
    return ResponseEntity.status(e.status).body(e.body);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<?> duplicate(Exception e) {
    return ResponseEntity.status(409).body(Map.of("error", "already exists"));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class
  })
  ResponseEntity<?> bad(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("error", "invalid request"));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> large(Exception e) {
    return ResponseEntity.status(413).body(Map.of("error", "file too large"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> failure(Exception e) {
    if (e instanceof org.springframework.web.ErrorResponse response)
      return ResponseEntity.status(response.getStatusCode())
          .body(
              Map.of("error", Objects.toString(response.getBody().getDetail(), "invalid request")));
    if (e instanceof org.springframework.web.multipart.MultipartException)
      return ResponseEntity.badRequest().body(Map.of("error", "invalid multipart body"));
    org.slf4j.LoggerFactory.getLogger(Errors.class).error("request failed", e);
    return ResponseEntity.internalServerError().body(Map.of("error", "internal error"));
  }
}
