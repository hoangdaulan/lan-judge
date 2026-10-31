package vn.lanlab;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

public final class CppJudge {
  private final Config cfg;
  private final Storage storage;
  private final ObjectMapper json;
  private final Path work;
  private final String helper, compiler;
  private final BlockingQueue<Integer> uids = new ArrayBlockingQueue<>(64);
  private String compilerInfo;

  public CppJudge(Config cfg, Storage storage, ObjectMapper json) throws Exception {
    this.cfg = cfg;
    this.storage = storage;
    this.json = json;
    work = Path.of(cfg.get("JUDGE_WORK_DIR", "/var/judge")).toAbsolutePath();
    helper = cfg.get("SANDBOX_HELPER", "/app/sandbox-helper");
    compiler = cfg.get("CXX", "/usr/bin/g++");
    if (!enabled()) return;
    if (work.normalize().startsWith(cfg.data()))
      throw new IOException("JUDGE_WORK_DIR must be outside DATA_DIR");
    Files.createDirectories(cfg.data());
    Files.setPosixFilePermissions(cfg.data(), PosixFilePermissions.fromString("rwxr-x---"));
    Files.createDirectories(work);
    Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwx--x--x"));
    try (var files = Files.list(work)) {
      for (Path path : files.toList()) delete(path);
    }
    Process version = new ProcessBuilder(compiler, "--version").start();
    compilerInfo =
        new String(version.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .lines()
            .findFirst()
            .orElse("");
    if (version.waitFor() != 0) throw new IOException("compiler unavailable");
    for (int i = 0; i < cfg.slots(); i++) uids.add(20000 + i);
  }

  public boolean enabled() {
    try {
      return System.getProperty("os.name").equals("Linux")
          && ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue() == 0
          && Files.isExecutable(Path.of(helper));
    } catch (Exception e) {
      return false;
    }
  }

  static void delete(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    try (var files = Files.walk(path)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
    }
  }

  private void own(Path path, int uid) throws IOException {
    Files.setAttribute(path, "unix:uid", uid);
    Files.setAttribute(path, "unix:gid", uid);
  }

  private void reap(int uid) throws Exception {
    Process p = new ProcessBuilder(helper, "reap", "" + uid).start();
    if (!p.waitFor(5, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IOException("sandbox reap timed out");
    }
    if (p.exitValue() != 0) throw new IOException("sandbox reap failed");
  }

  private JsonNode run(
      Path dir,
      int uid,
      long wall,
      long cpu,
      long memory,
      long stack,
      long size,
      int processes,
      int files,
      Path input,
      Path output,
      Path error,
      String... command)
      throws Exception {
    Path result = Files.createTempFile(work, "result-", ".json");
    try {
      List<String> args =
          new ArrayList<>(
              List.of(
                  helper,
                  "" + uid,
                  "" + wall,
                  "" + cpu,
                  "" + memory,
                  "" + stack,
                  "" + size,
                  "" + processes,
                  "" + files,
                  result.toString()));
      args.addAll(Arrays.asList(command));
      ProcessBuilder b = new ProcessBuilder(args).directory(dir.toFile());
      b.environment().clear();
      b.environment()
          .putAll(Map.of("PATH", "/usr/bin:/bin", "LANG", "C", "TMPDIR", dir.toString()));
      b.redirectInput(input == null ? new File("/dev/null") : input.toFile());
      b.redirectOutput(output.toFile());
      if (output.equals(error)) b.redirectErrorStream(true);
      else b.redirectError(error.toFile());
      Process p = b.start();
      try {
        if (!p.waitFor(wall + 10000, TimeUnit.MILLISECONDS)) {
          p.destroy();
          if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
          throw new IOException("sandbox supervisor timed out");
        }
        if (p.exitValue() != 0) throw new IOException("sandbox setup failed");
        return json.readTree(Files.readAllBytes(result));
      } catch (InterruptedException e) {
        p.destroy();
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
        throw e;
      }
    } finally {
      Files.deleteIfExists(result);
    }
  }

  static String limited(Path path, int limit) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      byte[] bytes = in.readNBytes(limit + 1);
      return new String(
              bytes, 0, Math.min(bytes.length, limit), java.nio.charset.StandardCharsets.UTF_8)
          + (bytes.length > limit ? "\n... (truncated)" : "");
    }
  }

  public JudgeQueue.Outcome judge(JudgeQueue.Job job) throws Exception {
    if (job.Tests.isEmpty()) throw new IOException("job has no tests");
    int uid = uids.take();
    Path dir = work.resolve("job" + job.JobID + "-a" + job.Attempt);
    Path capture = Files.createTempDirectory(work, "capture-");
    try {
      reap(uid);
      delete(dir);
      Files.createDirectory(dir);
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
      own(dir, uid);
      Path src = dir.resolve("main.cpp");
      Files.write(src, storage.read(job.SourcePath));
      Files.setPosixFilePermissions(src, PosixFilePermissions.fromString("rw-------"));
      own(src, uid);
      var outcome = new JudgeQueue.Outcome();
      outcome.TotalTests = job.Tests.size();
      outcome.CompilerInfo = compilerInfo;
      Path log = capture.resolve("compile.log");
      var compilation =
          run(
              dir,
              uid,
              10000,
              10,
              1536L << 20,
              0,
              256L << 20,
              64,
              256,
              null,
              log,
              log,
              compiler,
              "-std=c++17",
              "-O2",
              "-pipe",
              "-static",
              "-DONLINE_JUDGE",
              "-o",
              "main",
              "main.cpp");
      reap(uid);
      outcome.CompileOutput = limited(log, 8192);
      if (compilation.path("wall_timeout").asBoolean() || compilation.path("signal").asInt() == 24)
        outcome.CompileOutput += "\ncompilation exceeded 10s";
      if (compilation.path("wall_timeout").asBoolean()
          || compilation.path("signal").asInt() != 0
          || compilation.path("exit").asInt() != 0
          || !Files.isRegularFile(dir.resolve("main"))) {
        outcome.Verdict = "CE";
        return outcome;
      }
      for (var test : job.Tests) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Path output = capture.resolve("out.txt"), err = capture.resolve("err.txt");
        long memory = (long) job.MemoryLimitMb << 20;
        var result =
            run(
                dir,
                uid,
                2L * job.TimeLimitMs + 1000,
                (job.TimeLimitMs + 999) / 1000,
                memory + (64L << 20),
                memory,
                64L << 20,
                1,
                16,
                storage.resolve(test.InputPath),
                output,
                err,
                "./main");
        reap(uid);
        var t = new JudgeQueue.TestOutcome();
        t.TestCaseID = test.ID;
        t.TimeMs = result.path("cpu_ms").asInt();
        long rss = result.path("rss_kb").asLong();
        t.MemoryKB = rss > 0 ? rss : null;
        t.Verdict = classify(result, job.TimeLimitMs, job.MemoryLimitMb, limited(err, 4096));
        if (t.Verdict.isEmpty())
          try (InputStream got = Files.newInputStream(output);
              InputStream expected = Files.newInputStream(storage.resolve(test.OutputPath))) {
            t.Verdict = sameTokens(got, expected) ? "AC" : "WA";
          }
        outcome.Tests.add(t);
        if (!t.Verdict.equals("AC")) {
          outcome.Verdict = t.Verdict;
          return outcome;
        }
        outcome.PassedTests++;
      }
      outcome.Verdict = "AC";
      return outcome;
    } finally {
      try {
        reap(uid);
      } finally {
        try {
          delete(dir);
          delete(capture);
        } finally {
          uids.offer(uid);
        }
      }
    }
  }

  static String classify(JsonNode r, int time, int memory, String stderr) {
    if (r.path("wall_timeout").asBoolean()
        || r.path("cpu_ms").asDouble() > time
        || r.path("signal").asInt() == 24) return "TLE";
    if (r.path("rss_kb").asLong() > memory * 1024L) return "MLE";
    if (r.path("signal").asInt() != 0 || r.path("exit").asInt() != 0)
      return stderr.contains("std::bad_alloc") ? "MLE" : "RE";
    return "";
  }

  static boolean space(int c) {
    return c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000b' || c == '\f';
  }

  public static boolean sameTokens(InputStream left, InputStream right) throws IOException {
    var a = new BufferedInputStream(left, 65536);
    var b = new BufferedInputStream(right, 65536);
    int x, y;
    while (true) {
      do {
        x = a.read();
      } while (space(x));
      do {
        y = b.read();
      } while (space(y));
      if (x < 0 || y < 0) return x == y;
      while (x >= 0 && !space(x) && y >= 0 && !space(y)) {
        if (x != y) return false;
        x = a.read();
        y = b.read();
      }
      if ((x >= 0 && !space(x)) || (y >= 0 && !space(y))) return false;
      if (x < 0 || y < 0) {
        if (x < 0 && y < 0) return true;
        if (x < 0) {
          do {
            y = b.read();
          } while (space(y));
          return y < 0;
        }
        do {
          x = a.read();
        } while (space(x));
        return x < 0;
      }
    }
  }
}
