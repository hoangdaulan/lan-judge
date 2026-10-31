package vn.lanlab;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.*;
import java.time.*;
import java.util.*;

public final class Tls {
  public static Path ensure(Config cfg) throws Exception {
    Path dir = Path.of(cfg.get("TLS_DIR", cfg.data().resolve("tls").toString()));
    Path cert = dir.resolve("cert.pem"), key = dir.resolve("key.pem");
    Set<String> required = new LinkedHashSet<>(List.of("localhost", "127.0.0.1", "::1"));
    required.addAll(cfg.list("TLS_HOSTS"));
    if (Files.isRegularFile(cert) && Files.isRegularFile(key)) {
      try (InputStream in = Files.newInputStream(cert)) {
        X509Certificate c =
            (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        Set<String> names = new HashSet<>();
        if (c.getSubjectAlternativeNames() != null)
          for (var alt : c.getSubjectAlternativeNames()) {
            String name = alt.get(1).toString();
            names.add(name);
            if (((Integer) alt.get(0)) == 7)
              names.add(InetAddress.getByName(name).getHostAddress());
          }
        boolean covered = true;
        for (String name : required) {
          String normalized = literalIp(name) ? InetAddress.getByName(name).getHostAddress() : name;
          if (!names.contains(name) && !names.contains(normalized)) covered = false;
        }
        new org.apache.tomcat.util.net.jsse.PEMFile(key.toString()).getPrivateKey();
        if (covered && c.getNotAfter().toInstant().isAfter(Instant.now().plus(Duration.ofDays(30))))
          return dir;
      } catch (java.security.GeneralSecurityException ignored) {
      }
    }
    Files.createDirectories(dir);
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    Set<String> hosts = new LinkedHashSet<>(required);
    for (var iface : Lan.interfaces())
      for (var a : iface.getInterfaceAddresses())
        hosts.add(a.getAddress().getHostAddress().split("%")[0]);
    List<String> san = new ArrayList<>();
    for (String host : hosts) {
      if (!host.matches("[a-zA-Z0-9_.:%-]+"))
        throw new IllegalArgumentException("invalid TLS_HOSTS entry");
      san.add((literalIp(host) ? "IP:" : "DNS:") + host);
    }
    Path newKey = Files.createTempFile(dir, "key-", ".pem"),
        newCert = Files.createTempFile(dir, "cert-", ".pem");
    try {
      Process p =
          new ProcessBuilder(
                  "openssl",
                  "req",
                  "-x509",
                  "-newkey",
                  "ec",
                  "-pkeyopt",
                  "ec_paramgen_curve:P-256",
                  "-pkeyopt",
                  "ec_param_enc:named_curve",
                  "-nodes",
                  "-keyout",
                  newKey.toString(),
                  "-out",
                  newCert.toString(),
                  "-days",
                  "730",
                  "-subj",
                  "/CN=LAN Judge",
                  "-addext",
                  "subjectAltName=" + String.join(",", san))
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("TLS generation timed out");
      }
      if (p.exitValue() != 0) throw new IOException("TLS generation failed");
      Files.setPosixFilePermissions(newKey, PosixFilePermissions.fromString("rw-------"));
      Files.setPosixFilePermissions(newCert, PosixFilePermissions.fromString("rw-r--r--"));
      Files.move(newKey, key, StandardCopyOption.REPLACE_EXISTING);
      Files.move(newCert, cert, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(newKey);
      Files.deleteIfExists(newCert);
    }
    return dir;
  }

  static boolean literalIp(String host) {
    return host.contains(":") || host.matches("[0-9]+(?:\\.[0-9]+){3}");
  }
}
