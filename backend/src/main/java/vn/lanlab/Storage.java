package vn.lanlab;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class Storage {
  private final Path root;

  public Storage(Path root) {
    this.root = root;
  }

  public Path resolve(String relative) throws IOException {
    Path rel = Path.of(relative);
    Path full = root.resolve(rel).normalize();
    if (rel.isAbsolute() || !rel.normalize().equals(rel) || !full.startsWith(root))
      throw new IOException("invalid data path");
    Path current = root;
    for (Path p : rel) {
      current = current.resolve(p);
      if (Files.isSymbolicLink(current)) throw new IOException("symbolic link in data path");
    }
    return full;
  }

  public String save(byte[] content, Instant now) throws IOException {
    String rel =
        "submissions/"
            + DateTimeFormatter.ofPattern("uuuu/MM").withZone(ZoneOffset.UTC).format(now)
            + "/"
            + UUID.randomUUID().toString().replace("-", "")
            + ".cpp";
    Path full = resolve(rel);
    Files.createDirectories(full.getParent());
    try (FileChannel f =
        FileChannel.open(full, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ByteBuffer b = ByteBuffer.wrap(content);
      while (b.hasRemaining()) f.write(b);
      f.force(true);
    } catch (IOException e) {
      Files.deleteIfExists(full);
      throw e;
    }
    try (FileChannel dir = FileChannel.open(full.getParent(), StandardOpenOption.READ)) {
      dir.force(true);
    }
    return rel;
  }

  public byte[] read(String rel) throws IOException {
    return Files.readAllBytes(resolve(rel));
  }

  public void remove(String rel) throws IOException {
    Files.deleteIfExists(resolve(rel));
  }
}
