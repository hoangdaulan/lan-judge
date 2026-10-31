package vn.lanlab;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LanTest {
  @Test
  void discoveryProbeReadsTheExistingJsonDatagram() throws Exception {
    int port;
    try (DatagramSocket reserve = new DatagramSocket(0)) {
      port = reserve.getLocalPort();
    }
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Config cfg =
        new Config(
            Map.of(
                "DISCOVERY_PORT",
                "" + port,
                "ADVERTISE_HOST",
                "127.0.0.1",
                "HTTP_ADDR",
                ":8080",
                "DISPATCH_ADDR",
                ":7070"));
    try (Lan lan = new Lan(cfg, mapper)) {
      var found = Lan.discover(mapper, port, Duration.ofMillis(500), List.of("127.0.0.1:" + port));
      assertFalse(found.isEmpty());
      assertEquals("LANLAB/1", found.get(0).path("magic").asText());
      assertEquals("http://127.0.0.1:8080", found.get(0).path("http").asText());
      assertEquals("127.0.0.1:7070", found.get(0).path("dispatch").asText());
    }
  }

  @Test
  void rejectsUnicastAnnouncementGroups() {
    assertThrows(
        java.io.IOException.class,
        () ->
            new Lan(
                new Config(Map.of("ANNOUNCE_MULTICAST_ADDR", "127.0.0.1:9998")),
                new ObjectMapper()));
  }
}
