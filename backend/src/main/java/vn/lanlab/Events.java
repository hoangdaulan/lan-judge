package vn.lanlab;

import java.sql.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.LoggerFactory;

public final class Events implements AutoCloseable {
  private volatile boolean running = true;
  private volatile Connection connection;
  private final Thread thread;

  public Events(DataSource source, Consumer<String> event, Runnable resync) {
    thread =
        new Thread(
            () -> {
              int backoff = 1;
              boolean first = true;
              while (running) {
                try (Connection c = source.getConnection()) {
                  connection = c;
                  c.setAutoCommit(true);
                  try (Statement st = c.createStatement()) {
                    st.execute("LISTEN judge_events");
                  }
                  if (!first) resync.run();
                  first = false;
                  backoff = 1;
                  PGConnection pg = c.unwrap(PGConnection.class);
                  while (running) {
                    var notifications = pg.getNotifications(1000);
                    if (notifications != null)
                      for (var n : notifications) {
                        try {
                          event.accept(n.getParameter());
                        } catch (Exception e) {
                          LoggerFactory.getLogger(Events.class).warn("event handling failed", e);
                          resync.run();
                        }
                      }
                  }
                } catch (Exception e) {
                  if (running) {
                    LoggerFactory.getLogger(Events.class)
                        .warn("event listener reconnecting: {}", e.getMessage());
                    try {
                      Thread.sleep(backoff * 1000L);
                    } catch (InterruptedException ignored) {
                    }
                    backoff = Math.min(10, backoff * 2);
                    first = false;
                  }
                } finally {
                  connection = null;
                }
              }
            },
            "judge-events");
    thread.setDaemon(true);
    thread.start();
  }

  @Override
  public void close() {
    running = false;
    thread.interrupt();
    try {
      if (connection != null) connection.close();
    } catch (SQLException ignored) {
    }
  }
}
