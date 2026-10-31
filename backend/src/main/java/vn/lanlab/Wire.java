package vn.lanlab;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;

public final class Wire implements AutoCloseable {
  public static final int HELLO = 1,
      WELCOME = 2,
      JOB = 3,
      ACK = 4,
      RESULT = 5,
      RESULT_OK = 6,
      PING = 7,
      PONG = 8,
      ERROR = 9,
      MAX_FRAME = 4 << 20;

  public record Frame(int type, byte[] payload) {
    public JsonNode json(ObjectMapper mapper) throws IOException {
      return payload.length == 0 ? mapper.createObjectNode() : mapper.readTree(payload);
    }
  }

  private final Socket socket;
  private final DataInputStream in;
  private final OutputStream out;
  private final ObjectMapper json;
  private static final ScheduledExecutorService TIMEOUT =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "tcp-write-deadline");
            t.setDaemon(true);
            return t;
          });

  public Wire(Socket socket, ObjectMapper json) throws IOException {
    this.socket = socket;
    this.json = json;
    in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    out = socket.getOutputStream();
    socket.setTcpNoDelay(true);
  }

  public static Frame read(DataInputStream in) throws IOException {
    long length = Integer.toUnsignedLong(in.readInt());
    if (length < 1 || length > MAX_FRAME) throw new IOException("invalid frame length");
    byte[] body = new byte[(int) length];
    in.readFully(body);
    return new Frame(
        Byte.toUnsignedInt(body[0]), java.util.Arrays.copyOfRange(body, 1, body.length));
  }

  public Frame read() throws IOException {
    return read(in);
  }

  public static byte[] encode(int type, byte[] payload) throws IOException {
    int length = payload.length + 1;
    if (length > MAX_FRAME) throw new IOException("frame too large");
    ByteArrayOutputStream b = new ByteArrayOutputStream(length + 4);
    DataOutputStream d = new DataOutputStream(b);
    d.writeInt(length);
    d.writeByte(type);
    d.write(payload);
    return b.toByteArray();
  }

  public synchronized void send(int type, Object value) throws IOException {
    byte[] frame = encode(type, value == null ? new byte[0] : json.writeValueAsBytes(value));
    var timeout =
        TIMEOUT.schedule(
            () -> {
              try {
                socket.close();
              } catch (IOException ignored) {
              }
            },
            5,
            TimeUnit.SECONDS);
    try {
      out.write(frame);
      out.flush();
    } finally {
      timeout.cancel(false);
    }
  }

  public void timeout(int milliseconds) throws IOException {
    socket.setSoTimeout(milliseconds);
  }

  @Override
  public void close() {
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
}
