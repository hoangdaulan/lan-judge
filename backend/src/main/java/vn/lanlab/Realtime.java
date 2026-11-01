package vn.lanlab;

import static vn.lanlab.Db.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public final class Realtime extends TextWebSocketHandler implements Board.Presence, AutoCloseable {
  private final Auth auth;
  private final Exams exams;
  private final Submissions subs;
  private final Board board;
  private final Db db;
  private final Lan lan;
  private final ObjectMapper json;
  private final Map<String, Client> clients = new LinkedHashMap<>();
  private final Map<Long, View> views = new HashMap<>();
  private final AtomicLong sequence = new AtomicLong(), nextView = new AtomicLong();
  private final Set<Long> dirty = new HashSet<>();
  private long lastSummary = 0;
  private final ScheduledExecutorService clock =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "websocket-heartbeat");
            t.setDaemon(true);
            return t;
          });

  private static final class Client {
    final WebSocketSession ws;
    final long seq;
    final Instant opened = Instant.now();
    Auth.Principal principal;
    long session;
    boolean sharing;
    long ping = System.nanoTime();
    boolean waitingPong;
    final BlockingQueue<WebSocketMessage<?>> outgoing = new ArrayBlockingQueue<>(256);

    Client(WebSocketSession ws, long seq) {
      this.ws = ws;
      this.seq = seq;
    }
  }

  private record View(long id, Client teacher, Client student) {}

  public Realtime(
      Auth auth, Exams exams, Submissions subs, Board board, Db db, Lan lan, ObjectMapper json) {
    this.auth = auth;
    this.exams = exams;
    this.subs = subs;
    this.board = board;
    this.db = db;
    this.lan = lan;
    this.json = json;
    board.presence(this);
    subs.screenGate(this::sharing);
    clock.scheduleWithFixedDelay(this::tick, 300, 300, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void afterConnectionEstablished(WebSocketSession session) {
    session.setTextMessageSizeLimit(65536);
    var c = new Client(session, sequence.incrementAndGet());
    clients.put(session.getId(), c);
    var writer =
        new Thread(
            () -> {
              try {
                while (c.ws.isOpen()) {
                  var message = c.outgoing.poll(1, TimeUnit.SECONDS);
                  if (message != null) c.ws.sendMessage(message);
                }
              } catch (Exception e) {
                close(c, 1008, "client is too slow");
              }
            },
            "websocket-writer");
    writer.setDaemon(true);
    writer.start();
  }

  private void send(Client c, String type, Object data) {
    try {
      if (!c.outgoing.offer(
          new TextMessage(json.writeValueAsBytes(map("type", type, "data", data)))))
        close(c, 1008, "client is too slow");
    } catch (Exception e) {
      close(c, 1011, "send failed");
    }
  }

  private void close(Client c, int code, String reason) {
    try {
      c.ws.close(new CloseStatus(code, reason));
    } catch (IOException ignored) {
    }
    synchronized (this) {
      remove(c);
    }
  }

  private void error(Client c, String message) {
    send(c, "error", map("message", message));
  }

  @Override
  protected synchronized void handleTextMessage(WebSocketSession ws, TextMessage message) {
    Client c = clients.get(ws.getId());
    if (c == null) return;
    try {
      var frame = json.readTree(message.getPayload());
      String type = frame.path("type").asText();
      var data = frame.path("data");
      if (c.principal == null) {
        if (!type.equals("auth")) throw new ApiError(401, "authentication required");
        authenticate(c, data);
        return;
      }
      if (!type.equals("auth") && !Instant.now().isBefore(c.principal.expires())) {
        close(c, 4001, "token expired");
        return;
      }
      switch (type) {
        case "auth" -> authenticate(c, data);
        case "subscribe" -> {
          long id = data.path("session_id").asLong();
          if (id < 1) throw ApiError.bad("subscribe needs session_id");
          exams.session(c.principal, id);
          long previous = c.session;
          if (previous != id) {
            endViews(c, "session changed");
            c.session = id;
            c.sharing = false;
          }
          dirty.add(previous);
          dirty.add(id);
          send(c, "subscribed", map("session_id", id));
          screenList(previous);
          screenList(id);
        }
        case "screen.state" -> {
          student(c);
          subscribed(c);
          if (!data.path("sharing").isBoolean()) throw ApiError.bad("screen.state needs sharing");
          boolean sharing = data.path("sharing").asBoolean();
          if (sharing) {
            var session = exams.session(c.principal, c.session);
            var participant = exams.participant(c.session, c.principal.id());
            if (!Exams.state(session, Instant.now()).equals("running")
                || participant == null
                || !str(participant, "status").equals("in_progress"))
              throw ApiError.conflict("screen sharing requires an active participation");
          }
          c.sharing = sharing;
          if (!c.sharing) endViews(c, "student stopped sharing");
          send(c, "screen.state", map("session_id", c.session, "sharing", c.sharing));
          screenList(c.session);
        }
        case "screen.view" -> startView(c, data.path("student_id").asLong());
        case "webrtc.signal" -> signal(c, data);
        case "screen.stop" -> {
          View v = views.get(data.path("view_id").asLong());
          if (v == null || (v.student != c && v.teacher != c)) throw ApiError.bad("unknown view");
          endView(v, "stopped");
        }
        default -> error(c, "unknown message type");
      }
    } catch (Exception e) {
      if (c.principal == null) close(c, 4001, "invalid or expired token");
      else error(c, e instanceof ApiError ? e.getMessage() : "invalid JSON");
    }
  }

  private void authenticate(Client c, JsonNode data) {
    var principal = auth.verify(data.path("token").asText());
    if (c.principal != null
        && (c.principal.id() != principal.id() || !c.principal.role().equals(principal.role())))
      throw ApiError.bad("invalid token for this connection");
    if (c.principal == null
        && clients.values().stream()
                .filter(x -> x.principal != null && x.principal.id() == principal.id())
                .count()
            >= 8) {
      close(c, 1008, "too many connections");
      return;
    }
    c.principal = principal;
    send(
        c,
        "auth.ok",
        map(
            "user_id",
            principal.id(),
            "role",
            principal.role(),
            "expires_at",
            principal.expires()));
  }

  @Override
  protected synchronized void handlePongMessage(WebSocketSession ws, PongMessage message) {
    var c = clients.get(ws.getId());
    if (c != null) c.waitingPong = false;
  }

  @Override
  public synchronized void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
    Client c = clients.get(ws.getId());
    if (c != null) remove(c);
  }

  @Override
  public void handleTransportError(WebSocketSession ws, Throwable e) {
    Client c;
    synchronized (this) {
      c = clients.get(ws.getId());
    }
    if (c != null) close(c, 1008, "connection failed");
  }

  private void remove(Client c) {
    if (clients.remove(c.ws.getId()) == null) return;
    long old = c.session;
    endViews(c, "disconnected");
    dirty.add(old);
    screenList(old);
  }

  private void subscribed(Client c) {
    if (c.session == 0) throw ApiError.bad("subscribe to the session first");
  }

  private void student(Client c) {
    if (c.principal.teacher()) throw ApiError.bad("only students share their screen");
  }

  private void startView(Client teacher, long student) {
    if (!teacher.principal.teacher()) throw ApiError.bad("only teachers can view screens");
    subscribed(teacher);
    Client target =
        clients.values().stream()
            .filter(
                c ->
                    c.principal != null
                        && !c.principal.teacher()
                        && c.principal.id() == student
                        && c.session == teacher.session
                        && c.sharing)
            .max(Comparator.comparingLong(c -> c.seq))
            .orElseThrow(() -> ApiError.bad("the student is not sharing their screen"));
    for (var v : new ArrayList<>(views.values()))
      if (v.teacher == teacher && v.student == target) endView(v, "restarted");
    if (views.values().stream().filter(v -> v.teacher == teacher).count() >= 15)
      throw ApiError.bad("too many screens open; close one first");
    long id = nextView.incrementAndGet();
    views.put(id, new View(id, teacher, target));
    send(target, "screen.view", map("view_id", id, "teacher_id", teacher.principal.id()));
    send(teacher, "screen.view.started", map("view_id", id, "student_id", student));
  }

  private void signal(Client from, JsonNode data) {
    String kind = data.path("kind").asText();
    if (!List.of("offer", "answer", "ice", "quality").contains(kind))
      throw ApiError.bad("kind must be offer, answer, ice or quality");
    var v = views.get(data.path("view_id").asLong());
    if (v == null || (v.teacher != from && v.student != from)) throw ApiError.bad("unknown view");
    if (kind.equals("quality") && v.teacher != from)
      throw ApiError.bad("only the viewer sets the quality");
    send(
        v.teacher == from ? v.student : v.teacher,
        "webrtc.signal",
        map("view_id", v.id, "kind", kind, "payload", data.get("payload")));
  }

  private void endViews(Client c, String reason) {
    for (var v : new ArrayList<>(views.values()))
      if (v.teacher == c || v.student == c) endView(v, reason);
  }

  private void endView(View v, String reason) {
    views.remove(v.id);
    var data = map("view_id", v.id, "reason", reason);
    send(v.teacher, "screen.stop", data);
    send(v.student, "screen.stop", data);
  }

  private void screenList(long session) {
    if (session == 0) return;
    Map<Long, Boolean> state = new TreeMap<>();
    for (var c : clients.values())
      if (c.principal != null && !c.principal.teacher() && c.session == session)
        state.merge(c.principal.id(), c.sharing, Boolean::logicalOr);
    var students =
        state.entrySet().stream()
            .map(e -> map("student_id", e.getKey(), "sharing", e.getValue()))
            .toList();
    for (var c : new ArrayList<>(clients.values()))
      if (c.principal != null && c.principal.teacher() && c.session == session)
        send(c, "screen.list", map("session_id", session, "students", students));
  }

  @Override
  public synchronized Set<Long> online(long session) {
    Set<Long> ids = new HashSet<>();
    for (var c : clients.values())
      if (c.principal != null && !c.principal.teacher() && c.session == session)
        ids.add(c.principal.id());
    return ids;
  }

  public synchronized boolean sharing(Auth.Principal student, long session) {
    Instant now = Instant.now();
    return clients.values().stream()
        .anyMatch(c -> c.principal != null && !c.principal.teacher()
            && c.principal.id() == student.id() && c.session == session && c.sharing
            && c.ws.isOpen() && now.isBefore(c.principal.expires()));
  }

  @Override
  public synchronized void announce(Map<String, Object> announcement) {
    long session = num(announcement, "session_id");
    for (var c : new ArrayList<>(clients.values()))
      if (c.principal != null && c.session == session) send(c, "announcement", announcement);
    lan.announce(announcement);
  }

  public synchronized void resync() {
    for (var c : new ArrayList<>(clients.values()))
      if (c.principal != null) send(c, "resync", Map.of());
  }

  public void event(String payload) {
    String[] parts = payload.split(":", 2);
    if (parts.length == 2 && parts[0].equals("session")) {
      long session = Long.parseLong(parts[1]);
      synchronized (this) {
        for (var c : new ArrayList<>(clients.values()))
          if (c.principal != null && c.session == session)
            send(c, "session.updated", map("session_id", session));
        dirty.add(session);
      }
      return;
    }
    if (parts.length != 2 || !List.of("s", "p").contains(parts[0])) return;
    long id = Long.parseLong(parts[1]);
    if (id < 1) return;
    var ids =
        parts[0].equals("s")
            ? List.of(map("id", id))
            : db.rows("SELECT id FROM submissions WHERE problem_id=$1 ORDER BY id", id);
    for (var item : ids) {
      var sub = subs.raw(num(item, "id"));
      var response = Submissions.response(sub, false, false);
      synchronized (this) {
        for (var c : new ArrayList<>(clients.values()))
          if (c.principal != null
              && ((c.principal.teacher()
                      && c.principal.id() == num(sub, "teacher_id")
                      && c.session == num(sub, "session_id"))
                  || (!c.principal.teacher() && c.principal.id() == num(sub, "student_id"))))
            send(c, "run.updated", response);
        dirty.add(num(sub, "session_id"));
      }
    }
  }

  private void tick() {
    try {
      List<Client> teachers;
      Set<Long> sessions;
      synchronized (this) {
        Instant now = Instant.now();
        for (var c : new ArrayList<>(clients.values())) {
          if (c.principal == null) {
            if (now.isAfter(c.opened.plusSeconds(5))) close(c, 4001, "authentication required");
            continue;
          }
          if (!now.isBefore(c.principal.expires())) {
            close(c, 4001, "token expired");
            continue;
          }
          if (System.nanoTime() - c.ping > TimeUnit.SECONDS.toNanos(20)) {
            if (c.waitingPong) {
              close(c, 1008, "pong timeout");
              continue;
            }
            c.ping = System.nanoTime();
            c.waitingPong = true;
            if (!c.outgoing.offer(new PingMessage(ByteBuffer.wrap(new byte[] {1}))))
              close(c, 1008, "client is too slow");
          }
        }
        teachers =
            clients.values().stream()
                .filter(c -> c.principal != null && c.principal.teacher() && c.session > 0)
                .toList();
        sessions = new HashSet<>(dirty);
        dirty.clear();
        if (System.nanoTime() - lastSummary > TimeUnit.SECONDS.toNanos(5)) {
          teachers.forEach(c -> sessions.add(c.session));
          lastSummary = System.nanoTime();
        }
      }
      Map<Long, Object> summaries = new HashMap<>();
      for (var c : teachers)
        if (sessions.contains(c.session)) {
          Object summary =
              summaries.computeIfAbsent(c.session, id -> board.summary(c.principal, id));
          send(c, "summary.updated", summary);
        }
    } catch (Exception e) {
      LoggerFactory.getLogger(Realtime.class).warn("realtime refresh failed", e);
    }
  }

  @Override
  public synchronized void close() {
    clock.shutdownNow();
    for (var c : new ArrayList<>(clients.values())) close(c, 1000, "server shutting down");
  }
}
