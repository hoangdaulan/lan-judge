package vn.lanlab;

import static vn.lanlab.Db.*;
import static vn.lanlab.Wire.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

public final class Dispatch implements AutoCloseable {
  private final Config cfg;
  private final JudgeQueue queue;
  private final ObjectMapper json;
  private final ServerSocket server;
  private final ConcurrentMap<String, Peer> peers = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(
          2,
          r -> {
            var t = new Thread(r, "dispatcher");
            t.setDaemon(true);
            return t;
          });

  private static final class Pending {
    final JudgeQueue.Job job;
    final long sent = System.nanoTime();
    volatile boolean ack;

    Pending(JudgeQueue.Job j) {
      job = j;
    }
  }

  private final class Peer {
    final Wire wire;
    final String id;
    final int slots;
    final ConcurrentMap<Long, Pending> jobs = new ConcurrentHashMap<>();
    volatile boolean open = true;

    Peer(Wire w, String id, int slots) {
      wire = w;
      this.id = id;
      this.slots = slots;
    }
  }

  public Dispatch(Config cfg, JudgeQueue queue, ObjectMapper json) throws IOException {
    this.cfg = cfg;
    this.queue = queue;
    this.json = json;
    server = new ServerSocket();
    server.bind(Config.address(cfg.get("DISPATCH_ADDR")));
    Thread accept =
        new Thread(
            () -> {
              while (!server.isClosed()) {
                try {
                  Socket s = server.accept();
                  Thread t = new Thread(() -> serve(s), "dispatch-peer");
                  t.setDaemon(true);
                  t.start();
                } catch (IOException e) {
                  if (!server.isClosed())
                    LoggerFactory.getLogger(Dispatch.class).warn("accept failed", e);
                }
              }
            },
            "dispatch-accept");
    accept.setDaemon(true);
    accept.start();
    scheduler.scheduleWithFixedDelay(
        this::tick,
        0,
        Math.max(10, cfg.duration("WORKER_POLL_INTERVAL", "1s").toMillis()),
        TimeUnit.MILLISECONDS);
    scheduler.scheduleAtFixedRate(
        () -> {
          for (var p : peers.values()) {
            try {
              p.wire.send(PING, null);
            } catch (IOException e) {
              closePeer(p);
            }
          }
        },
        5,
        5,
        TimeUnit.SECONDS);
  }

  private void serve(Socket socket) {
    Peer peer = null;
    try (Wire wire = new Wire(socket, json)) {
      wire.timeout(5000);
      var first = wire.read();
      var h = first.json(json);
      String id = h.path("worker_id").asText();
      int slots = h.path("slots").asInt();
      if (first.type() != HELLO
          || h.path("version").asInt() != 1
          || id.isEmpty()
          || id.length() > 100
          || slots < 1
          || slots > 64
          || (!cfg.get("DISPATCH_TOKEN").isEmpty()
              && !MessageDigest.isEqual(
                  cfg.get("DISPATCH_TOKEN").getBytes(StandardCharsets.UTF_8),
                  h.path("token").asText().getBytes(StandardCharsets.UTF_8)))) {
        wire.send(ERROR, map("message", "invalid HELLO"));
        return;
      }
      peer = new Peer(wire, id, slots);
      wire.send(WELCOME, map("server", cfg.workerId(), "heartbeat_ms", 5000));
      wire.timeout(15000);
      var old = peers.put(id, peer);
      if (old != null) closePeer(old);
      while (peer.open) {
        var frame = wire.read();
        var data = frame.json(json);
        switch (frame.type()) {
          case PING -> wire.send(PONG, null);
          case PONG -> {}
          case ACK -> {
            var p = peer.jobs.get(data.path("job_id").asLong());
            if (p != null) {
              if (data.path("accepted").asBoolean()) p.ack = true;
              else {
                queue.fail(p.job, "worker rejected job");
                peer.jobs.remove(p.job.JobID);
              }
            }
          }
          case RESULT -> {
            long job = data.path("job_id").asLong();
            var pending = peer.jobs.get(job);
            String status = "unknown";
            if (pending != null) {
              try {
                boolean saved;
                if (data.hasNonNull("outcome"))
                  saved =
                      queue.complete(
                          pending.job,
                          json.treeToValue(data.get("outcome"), JudgeQueue.Outcome.class));
                else
                  saved =
                      queue.fail(
                          pending.job, data.path("error").asText("worker infrastructure failure"));
                status = saved ? "saved" : "dropped";
                peer.jobs.remove(job);
              } catch (Exception e) {
                status = "error";
                LoggerFactory.getLogger(Dispatch.class).warn("save TCP result failed", e);
              }
            }
            wire.send(RESULT_OK, map("job_id", job, "status", status));
          }
          default -> throw new IOException("unexpected message");
        }
      }
    } catch (Exception e) {
      LoggerFactory.getLogger(Dispatch.class).debug("worker disconnected: {}", e.getMessage());
    } finally {
      if (peer != null) closePeer(peer);
      else
        try {
          socket.close();
        } catch (IOException ignored) {
        }
    }
  }

  private synchronized void tick() {
    try {
      for (var p : peers.values())
        for (var pending : p.jobs.values())
          if (!pending.ack && System.nanoTime() - pending.sent > TimeUnit.SECONDS.toNanos(10)) {
            closePeer(p);
            break;
          }
      while (true) {
        Peer best =
            peers.values().stream()
                .filter(p -> p.open && p.jobs.size() < p.slots)
                .max(Comparator.comparingInt(p -> p.slots - p.jobs.size()))
                .orElse(null);
        if (best == null) return;
        var job = queue.claim(best.id);
        if (job == null) return;
        best.jobs.put(job.JobID, new Pending(job));
        try {
          best.wire.send(JOB, job);
        } catch (IOException e) {
          closePeer(best);
        }
      }
    } catch (Exception e) {
      LoggerFactory.getLogger(Dispatch.class).warn("dispatch failed", e);
    }
  }

  public void wake() {
    if (!scheduler.isShutdown()) scheduler.execute(this::tick);
  }

  int port() {
    return server.getLocalPort();
  }

  private synchronized void closePeer(Peer peer) {
    if (!peer.open) return;
    peer.open = false;
    peers.remove(peer.id, peer);
    peer.wire.close();
    for (var pending : peer.jobs.values())
      if (!pending.ack) {
        try {
          queue.fail(pending.job, "connection lost before ACK");
        } catch (Exception e) {
          LoggerFactory.getLogger(Dispatch.class).warn("return unacknowledged job failed", e);
        }
      }
    peer.jobs.clear();
  }

  @Override
  public void close() {
    try {
      server.close();
    } catch (IOException ignored) {
    }
    scheduler.shutdownNow();
    for (var p : peers.values()) closePeer(p);
  }
}
