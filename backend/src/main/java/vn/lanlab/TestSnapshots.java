package vn.lanlab;

import static vn.lanlab.Db.*;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;

/** Published test files are never rewritten; jobs only reference these copies. */
public final class TestSnapshots {
  private final Storage storage;

  public TestSnapshots(Storage storage) {
    this.storage = storage;
  }

  static String hash(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[65536];
        for (int n; (n = in.read(buffer)) != -1; ) digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean published(long problem, Map<String, Object> test) {
    String prefix = "problems/" + problem + "/testsets/";
    return str(test, "input").startsWith(prefix) && str(test, "output").startsWith(prefix);
  }

  public List<Map<String, Object>> publish(long problem, List<Map<String, Object>> tests)
      throws IOException {
    String parent = "problems/" + problem + "/testsets";
    Files.createDirectories(storage.resolve(parent));
    Path staging = Files.createTempDirectory(storage.resolve(parent), ".staging-");
    List<Map<String, Object>> copied = new ArrayList<>();
    String relative = parent + "/" + UUID.randomUUID();
    try {
      for (var test : tests) {
        var out = new LinkedHashMap<String, Object>();
        for (String key : List.of("input", "output")) {
          Path source = storage.resolve(str(test, key));
          if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw ApiError.conflict("test file is not a regular file: " + test.get(key));
          String expected = test.containsKey(key + "_sha256")
              ? str(test, key + "_sha256") : hash(source);
          Path target = staging.resolve(source.getFileName());
          try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
              OutputStream stream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            in.transferTo(stream);
          }
          try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
            channel.force(true);
          }
          if (!hash(target).equals(expected))
            throw ApiError.conflict("test files changed during scan; scan again");
          Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r-----"));
          out.put(key, relative + "/" + source.getFileName());
          out.put(key + "_sha256", expected);
        }
        copied.add(out);
      }
      // Detect edits to earlier files while later pairs were being copied.
      for (int i = 0; i < tests.size(); i++)
        for (String key : List.of("input", "output"))
          if (!hash(storage.resolve(str(tests.get(i), key)))
              .equals(copied.get(i).get(key + "_sha256")))
            throw ApiError.conflict("test files changed during scan; scan again");
      Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rwxr-x---"));
      try (FileChannel dir = FileChannel.open(staging, StandardOpenOption.READ)) {
        dir.force(true);
      }
      Files.move(staging, storage.resolve(relative), StandardCopyOption.ATOMIC_MOVE);
      try (FileChannel dir = FileChannel.open(storage.resolve(parent), StandardOpenOption.READ)) {
        dir.force(true);
      }
      return copied;
    } finally {
      if (Files.exists(staging)) {
        try (var files = Files.walk(staging)) {
          for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
      }
    }
  }

  /** Upgrade legacy registrations without changing test IDs. Caller holds an exclusive problem lock. */
  public boolean freezeLegacy(Db db, long problem) {
    var tests = db.rows("SELECT id,input,output FROM test_cases WHERE problem_id=$1 ORDER BY id", problem);
    if (tests.stream().allMatch(t -> published(problem, t))) return false;
    try {
      var copied = publish(problem, tests);
      for (int i = 0; i < tests.size(); i++)
        db.update("UPDATE test_cases SET input=$2,output=$3 WHERE id=$1",
            tests.get(i).get("id"), copied.get(i).get("input"), copied.get(i).get("output"));
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
