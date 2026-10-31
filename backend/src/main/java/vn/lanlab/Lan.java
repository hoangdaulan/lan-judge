package vn.lanlab;

import static vn.lanlab.Db.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Lan implements AutoCloseable {
  public static final String MAGIC = "LANLAB/1";
  private final Config cfg;
  private final ObjectMapper json;
  private DatagramSocket discovery;
  private MulticastSocket multicast;
  private InetSocketAddress group;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "lan-broadcast");
            t.setDaemon(true);
            return t;
          });
  private final AtomicLong sequence = new AtomicLong(), multicastSequence = new AtomicLong();

  public Lan(Config cfg, ObjectMapper json) throws IOException {
    this.cfg = cfg;
    this.json = json;
    String multicastAddr = cfg.get("ANNOUNCE_MULTICAST_ADDR");
    if (!multicastAddr.isEmpty()) {
      group = Config.address(multicastAddr);
      if (!group.getAddress().isMulticastAddress())
        throw new IOException("ANNOUNCE_MULTICAST_ADDR must be multicast");
      multicast = new MulticastSocket();
      multicast.setTimeToLive(1);
    }
    int port = cfg.integer("DISCOVERY_PORT", 0, 0, 65535);
    if (port == 0) return;
    discovery = socket(port);
    long interval = cfg.duration("DISCOVERY_INTERVAL", "5s").toMillis();
    if (interval < 500)
      throw new IllegalArgumentException("DISCOVERY_INTERVAL must be at least 500ms");
    var reader =
        new Thread(
            () -> {
              byte[] bytes = new byte[2049];
              while (!discovery.isClosed()) {
                try {
                  DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                  discovery.receive(packet);
                  if (packet.getLength() > 2048) continue;
                  var data = json.readTree(bytes, 0, packet.getLength());
                  if (MAGIC.equals(data.path("magic").asText())
                      && "discover".equals(data.path("type").asText()))
                    send(
                        discovery,
                        packet.getSocketAddress(),
                        announcement(localFor(packet.getAddress())));
                } catch (IOException e) {
                  if (!discovery.isClosed())
                    org.slf4j.LoggerFactory.getLogger(Lan.class).debug("discovery receive", e);
                }
              }
            },
            "lan-discovery");
    reader.setDaemon(true);
    reader.start();
    scheduler.scheduleAtFixedRate(
        () -> {
          try {
            for (var iface : interfaces())
              for (var address : iface.getInterfaceAddresses())
                if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null)
                  send(
                      discovery,
                      new InetSocketAddress(address.getBroadcast(), port),
                      announcement(address.getAddress().getHostAddress()));
          } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(Lan.class).warn("broadcast failed", e);
          }
        },
        0,
        interval,
        TimeUnit.MILLISECONDS);
  }

  static DatagramSocket socket(int port) throws SocketException {
    DatagramSocket s = new DatagramSocket(null);
    s.setReuseAddress(true);
    s.setBroadcast(true);
    s.bind(new InetSocketAddress(port));
    return s;
  }

  static List<NetworkInterface> interfaces() throws SocketException {
    return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
        .filter(
            i -> {
              try {
                return i.isUp();
              } catch (SocketException e) {
                return false;
              }
            })
        .toList();
  }

  static String localFor(InetAddress peer) throws SocketException {
    try (DatagramSocket s = new DatagramSocket()) {
      s.connect(peer, 9);
      return s.getLocalAddress().getHostAddress();
    }
  }

  private Map<String, Object> announcement(String iface) {
    String host =
        cfg.get(
            "ADVERTISE_HOST",
            cfg.list("TLS_HOSTS").isEmpty() ? iface : cfg.list("TLS_HOSTS").get(0));
    var a =
        map(
            "magic",
            MAGIC,
            "type",
            "announce",
            "name",
            cfg.workerId(),
            "seq",
            sequence.incrementAndGet(),
            "http",
            "http://" + authority(host, Config.address(cfg.get("HTTP_ADDR", ":8080")).getPort()));
    if (!cfg.get("HTTPS_ADDR").isEmpty())
      a.put(
          "https",
          "https://" + authority(host, Integer.parseInt(cfg.get("HTTPS_PUBLIC_PORT", "8443"))));
    if (!cfg.get("DISPATCH_ADDR").isEmpty())
      a.put("dispatch", authority(host, Config.address(cfg.get("DISPATCH_ADDR")).getPort()));
    if (group != null) a.put("multicast", cfg.get("ANNOUNCE_MULTICAST_ADDR"));
    return a;
  }

  static String authority(String host, int port) {
    return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
  }

  private void send(DatagramSocket s, SocketAddress target, Object value) throws IOException {
    byte[] b = json.writeValueAsBytes(value);
    if (b.length > (s == multicast ? 8192 : 2048)) throw new IOException("datagram too large");
    s.send(new DatagramPacket(b, b.length, target));
  }

  public void announce(Map<String, Object> announcement) {
    if (multicast == null) return;
    var data = new LinkedHashMap<>(announcement);
    data.putAll(
        map(
            "magic",
            MAGIC,
            "type",
            "announcement",
            "server",
            cfg.workerId(),
            "seq",
            multicastSequence.incrementAndGet()));
    try {
      send(multicast, group, data);
    } catch (IOException e) {
      org.slf4j.LoggerFactory.getLogger(Lan.class).warn("multicast announcement failed", e);
    }
  }

  public static List<JsonNode> discover(
      ObjectMapper json, int port, Duration wait, List<String> extra) throws IOException {
    try (DatagramSocket s = socket(0)) {
      s.setSoTimeout(200);
      Set<SocketAddress> targets = new HashSet<>();
      targets.add(new InetSocketAddress("255.255.255.255", port));
      for (var i : interfaces())
        for (var a : i.getInterfaceAddresses())
          if (a.getBroadcast() != null) targets.add(new InetSocketAddress(a.getBroadcast(), port));
      for (var e : extra) if (!e.isBlank()) targets.add(Config.address(e));
      byte[] probe = json.writeValueAsBytes(map("magic", MAGIC, "type", "discover"));
      for (var t : targets)
        try {
          s.send(new DatagramPacket(probe, probe.length, t));
        } catch (IOException ignored) {
        }
      Map<String, JsonNode> found = new LinkedHashMap<>();
      long deadline = System.nanoTime() + wait.toNanos();
      while (System.nanoTime() < deadline) {
        byte[] b = new byte[2049];
        DatagramPacket p = new DatagramPacket(b, b.length);
        try {
          s.receive(p);
          if (p.getLength() > 2048) continue;
          var d = json.readTree(b, 0, p.getLength());
          if (MAGIC.equals(d.path("magic").asText()) && "announce".equals(d.path("type").asText()))
            found.put(d.path("name").asText() + d.path("http").asText(), d);
        } catch (SocketTimeoutException ignored) {
        } catch (com.fasterxml.jackson.core.JacksonException ignored) {
        }
      }
      return new ArrayList<>(found.values());
    }
  }

  public static void cli(String[] args, ObjectMapper json) throws Exception {
    if (args.length < 1) throw new IllegalArgumentException("lannet discover|watch|announcements");
    Map<String, String> flags = new HashMap<>();
    for (int i = 1; i + 1 < args.length; i += 2) flags.put(args[i], args[i + 1]);
    int port = Integer.parseInt(flags.getOrDefault("-port", "9999"));
    if (args[0].equals("discover")) {
      var found =
          discover(
              json,
              port,
              Config.parseDuration(flags.getOrDefault("-wait", "2s")),
              Arrays.asList(flags.getOrDefault("-to", "").split(",")));
      if (found.isEmpty()) throw new IOException("no server answered on UDP port " + port);
      for (var d : found) System.out.println(json.writeValueAsString(d));
      return;
    }
    if (args[0].equals("watch")) {
      try (DatagramSocket socket = socket(port)) {
        watch(json, socket, "announce");
      }
      return;
    }
    if (!args[0].equals("announcements"))
      throw new IllegalArgumentException("unknown lannet command");
    var group = Config.address(flags.getOrDefault("-group", "239.255.42.99:9998"));
    if (!group.getAddress().isMulticastAddress())
      throw new IllegalArgumentException("expected multicast group");
    try (MulticastSocket socket = new MulticastSocket(null)) {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress(group.getPort()));
      NetworkInterface iface =
          flags.containsKey("-iface")
              ? NetworkInterface.getByName(flags.get("-iface"))
              : interfaces().stream()
                  .filter(
                      i -> {
                        try {
                          return i.supportsMulticast() && !i.isLoopback();
                        } catch (Exception e) {
                          return false;
                        }
                      })
                  .findFirst()
                  .orElse(null);
      if (iface == null) throw new IOException("no multicast interface");
      socket.joinGroup(group, iface);
      watch(json, socket, "announcement");
    }
  }

  static void watch(ObjectMapper json, DatagramSocket socket, String type) throws IOException {
    long last = 0;
    int max = type.equals("announcement") ? 8192 : 2048;
    while (true) {
      byte[] b = new byte[max + 1];
      DatagramPacket packet = new DatagramPacket(b, b.length);
      socket.receive(packet);
      if (packet.getLength() > max) continue;
      try {
        var d = json.readTree(b, 0, packet.getLength());
        if (MAGIC.equals(d.path("magic").asText()) && type.equals(d.path("type").asText())) {
          long seq = d.path("seq").asLong();
          if (last > 0 && seq > last + 1)
            System.err.println("missed " + (seq - last - 1) + " datagram(s)");
          last = seq;
          System.out.println(json.writeValueAsString(d));
        }
      } catch (com.fasterxml.jackson.core.JacksonException ignored) {
      }
    }
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
    if (discovery != null) discovery.close();
    if (multicast != null) multicast.close();
  }
}
