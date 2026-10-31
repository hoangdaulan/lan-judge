package vn.lanlab;

import static vn.lanlab.Db.*;
import static vn.lanlab.Wire.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

public final class Worker implements AutoCloseable {
  private final Config cfg;
  private final JudgeQueue queue;
  private final CppJudge judge;
  private final ObjectMapper json;
  private volatile boolean running = true, pushed = false;
  private volatile Wire connection;
  private final ExecutorService loops = Executors.newCachedThreadPool();
  private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
  private final Semaphore slots;
  private final BlockingQueue<Boolean> wake = new ArrayBlockingQueue<>(64);
  private final ConcurrentMap<Long, CompletableFuture<String>> receipts = new ConcurrentHashMap<>();

  public Worker(Config cfg, JudgeQueue queue, CppJudge judge, ObjectMapper json) {
    this.cfg = cfg;
    this.queue = queue;
    this.judge = judge;
    this.json = json;
    slots = new Semaphore(cfg.slots());
    cfg.lease();
    long recover = Math.max(1000, Math.min(30000, cfg.lease().toMillis() / 2));
    timers.scheduleWithFixedDelay(
        () -> {
          try {
            queue.recover();
          } catch (Exception e) {
            LoggerFactory.getLogger(Worker.class).warn("lease recovery failed", e);
          }
        },
        0,
        recover,
        TimeUnit.MILLISECONDS);
    if (!judge.enabled()) {
      LoggerFactory.getLogger(Worker.class)
          .warn("sandbox unavailable: jobs stay queued; run worker as root in its Linux container");
      return;
    }
    for (int i = 0; i < cfg.slots(); i++) loops.submit(this::poll);
    if (!cfg.get("WORKER_DISPATCH_ADDR").isEmpty()) loops.submit(this::dispatchLoop);
  }

  public void wake() {
    for (int i = 0; i < cfg.slots(); i++) wake.offer(true);
  }

  private void poll() {
    while (running) {
      boolean worked = false;
      try {
        if (!pushed) {
          slots.acquire();
          try {
            if (!pushed) {
              var job = queue.claim(cfg.workerId());
              if (job != null) {
                worked = true;
                execute(job, null);
              }
            }
          } finally {
            slots.release();
          }
        }
        if (!worked)
          wake.poll(cfg.duration("WORKER_POLL_INTERVAL", "1s").toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        LoggerFactory.getLogger(Worker.class).warn("polling failed", e);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ignored) {
          return;
        }
      }
    }
  }

  private void execute(JudgeQueue.Job job, Wire wire) {
    Thread judging = Thread.currentThread();
    var heartbeat =
        timers.scheduleAtFixedRate(
            () -> {
              try {
                if (!queue.heartbeat(job)) judging.interrupt();
              } catch (Exception e) {
                LoggerFactory.getLogger(Worker.class).warn("heartbeat failed: {}", e.getMessage());
              }
            },
            cfg.lease().toMillis() / 3,
            cfg.lease().toMillis() / 3,
            TimeUnit.MILLISECONDS);
    JudgeQueue.Outcome outcome = null;
    String error = null;
    long start = System.nanoTime();
    try {
      outcome = judge.judge(job);
    } catch (Exception e) {
      error = e.toString();
    } finally {
      heartbeat.cancel(false);
      Thread.interrupted();
    }
    if (wire != null && running) {
      var receipt = new CompletableFuture<String>();
      receipts.put(job.JobID, receipt);
      try {
        wire.send(
            RESULT,
            outcome == null
                ? map(
                    "job_id",
                    job.JobID,
                    "error",
                    error,
                    "judge_ms",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                : map(
                    "job_id",
                    job.JobID,
                    "outcome",
                    outcome,
                    "judge_ms",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
        String status = receipt.get(15, TimeUnit.SECONDS);
        if (List.of("saved", "dropped").contains(status)) return;
      } catch (Exception ignored) {
      } finally {
        receipts.remove(job.JobID, receipt);
      }
    }
    try {
      boolean saved = outcome != null ? queue.complete(job, outcome) : queue.fail(job, error);
      LoggerFactory.getLogger(Worker.class)
          .info(
              "job {} result {}: {}",
              job.JobID,
              saved ? "saved" : "dropped",
              outcome == null ? error : outcome.Verdict);
    } catch (Exception e) {
      LoggerFactory.getLogger(Worker.class)
          .error("record result failed; lease recovery will retry", e);
    }
  }

  private void dispatchLoop() {
    int backoff = 1;
    while (running) {
      ScheduledFuture<?> ping = null;
      try {
        String target = cfg.get("WORKER_DISPATCH_ADDR");
        if (target.equals("auto")) {
          var found =
              Lan.discover(
                  json,
                  cfg.integer("DISCOVERY_PORT", 9999, 0, 65535) == 0
                      ? 9999
                      : cfg.integer("DISCOVERY_PORT", 9999, 0, 65535),
                  Duration.ofSeconds(2),
                  List.of());
          target =
              found.stream()
                  .map(d -> d.path("dispatch").asText())
                  .filter(s -> !s.isEmpty())
                  .findFirst()
                  .orElseThrow(() -> new IOException("no dispatcher found"));
        }
        Socket socket = new Socket();
        socket.connect(Config.address(target), 5000);
        try (Wire wire = new Wire(socket, json)) {
          connection = wire;
          wire.timeout(5000);
          wire.send(
              HELLO,
              map(
                  "worker_id",
                  cfg.workerId(),
                  "slots",
                  cfg.slots(),
                  "version",
                  1,
                  "token",
                  cfg.get("DISPATCH_TOKEN")));
          if (wire.read().type() != WELCOME) throw new IOException("dispatcher rejected HELLO");
          wire.timeout(15000);
          pushed = true;
          backoff = 1;
          ping =
              timers.scheduleAtFixedRate(
                  () -> {
                    try {
                      wire.send(PING, null);
                    } catch (IOException e) {
                      wire.close();
                    }
                  },
                  5,
                  5,
                  TimeUnit.SECONDS);
          while (running) {
            var frame = wire.read();
            var data = frame.json(json);
            switch (frame.type()) {
              case PING -> wire.send(PONG, null);
              case PONG -> {}
              case JOB -> {
                var job = json.treeToValue(data, JudgeQueue.Job.class);
                wire.send(ACK, map("job_id", job.JobID, "accepted", true));
                loops.submit(
                    () -> {
                      boolean acquired = false;
                      try {
                        slots.acquire();
                        acquired = true;
                        execute(job, wire);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      } finally {
                        if (acquired) slots.release();
                      }
                    });
              }
              case RESULT_OK -> {
                var receipt = receipts.get(data.path("job_id").asLong());
                if (receipt != null) receipt.complete(data.path("status").asText());
              }
              default -> throw new IOException("unexpected dispatch message");
            }
          }
        }
      } catch (Exception e) {
        if (running)
          LoggerFactory.getLogger(Worker.class)
              .info("dispatcher disconnected: {}; polling resumes", e.getMessage());
      } finally {
        pushed = false;
        connection = null;
        wake();
        if (ping != null) ping.cancel(false);
        receipts.values().forEach(f -> f.complete("unknown"));
      }
      if (running)
        try {
          Thread.sleep(backoff * 1000L);
          backoff = Math.min(15, backoff * 2);
        } catch (InterruptedException e) {
          return;
        }
    }
  }

  @Override
  public void close() {
    running = false;
    if (connection != null) connection.close();
    wake();
    loops.shutdown();
    try {
      if (!loops.awaitTermination(25, TimeUnit.SECONDS)) loops.shutdownNow();
    } catch (InterruptedException e) {
      loops.shutdownNow();
      Thread.currentThread().interrupt();
    }
    timers.shutdownNow();
  }
}
