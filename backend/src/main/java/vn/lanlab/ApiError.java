package vn.lanlab;

import java.util.Map;

public final class ApiError extends RuntimeException {
  public final int status;
  public final Map<String, Object> body;

  public ApiError(int status, String message) {
    this(status, Db.map("error", message));
  }

  public ApiError(int status, Map<String, Object> body) {
    super(String.valueOf(body.get("error")));
    this.status = status;
    this.body = body;
  }

  public static ApiError bad(String s) {
    return new ApiError(400, s);
  }

  public static ApiError missing(String s) {
    return new ApiError(404, s + " not found");
  }

  public static ApiError conflict(String s) {
    return new ApiError(409, s);
  }
}
