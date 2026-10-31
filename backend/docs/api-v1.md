# API v1

## Test bằng Swagger UI

- Mở `/swagger/index.html` trên cùng host/port với API, ví dụ `http://localhost:8080/swagger/index.html`.
- UI, JavaScript và CSS được phục vụ từ ứng dụng; không cần CDN. Đặc tả: `/swagger/doc.json`.
- `POST /api/v1/auth/login` → copy `access_token` → **Authorize** → nhập `Bearer <access_token>`.
- Khi chuyển vai trò giáo viên/sinh viên, thay token trong Authorize. Refresh token nhập riêng trong body của refresh/logout.
- Import CSV: `POST /api/v1/students/import` → **Try it out** → chọn file ở trường `file`. Mẫu ở `examples/students-import.csv`.
- Chỉnh ngày giờ trong request tạo phiên cho phù hợp lúc test; ID lấy từ response thực tế.
- Swagger mô tả 44 thao tác REST. Worker chấm thật (g++ C++17 trong sandbox); chấm lại problem/bài nộp đã hoạt động. WebSocket `/api/v1/ws` không nằm trong Swagger (không phải REST) — xem mục "Chi tiết WebSocket"; thử bằng `./bin/wsprobe`.
- Đặc tả được duy trì trong `docs/swagger.json` và `docs/swagger.yaml`, nhúng vào binary bằng `docs/docs.go`. Không chạy `swag init` ghi đè khi handler chưa có annotation đầy đủ.
- Khi sửa code/spec trong môi trường Compose, build lại API: `docker compose up -d --build --no-deps api`.

Ngày chốt: **21/10/2026**. Quyết định nền: `BACKEND_CONTEXT.md` mục 17.

- Prefix: `/api/v1` (trừ `/healthz`).
- Vai trò: **T** = giáo viên, **S** = sinh viên, **—** = công khai.
- Xác thực: `Authorization: Bearer <access JWT>` cho mọi route trừ login/refresh/healthz.
- Trạng thái cột "Làm": ✅ đã triển khai, trống = chưa.


## Danh sách và phân trang (PagedList Mesgo)

- Mọi endpoint GET danh sách dùng `?page=1&pageSize=20`: trang bắt đầu từ 1, mặc định 20 phần tử/trang, tối đa 200. Kích thước lớn hơn được giới hạn về 200 và trả lại giá trị thực tế.
- Response: `{"data": [], "page": 1, "pageSize": 20, "totalPages": 0}`. `data` luôn là array; tổng trang tính trên dữ liệu sau lọc. Trang vượt cuối trả `data: []`, giữ page yêu cầu và totalPages thực tế.
- Giá trị page/pageSize không phải số nguyên dương hoặc gây tràn offset trả 400. Không dùng `items`, `total`, `limit`, `offset` ở response danh sách nữa.
- Danh sách sinh viên tìm bằng `query` (thay `q`); các filter `student_id`, `problem_id` của bài nộp giữ nguyên. Các danh sách khác chưa có tìm kiếm bằng `query`.
- Áp dụng: students, exams, các phiên của đề, participants, me/sessions, các problem của đề, tests của problem, submissions của phiên và tests của submission.
- Metadata `server_time`, `test_dir`, `submission` giữ ở cấp ngoài cùng cạnh bốn trường PagedList. Các array bên trong response chi tiết hoặc kết quả thao tác như import/rejudge không đổi thành PagedList.
- Sinh viên phân trang tại SQL. Những danh sách còn lại hiện lấy danh sách đã sắp xếp/kiểm tra quyền rồi phân trang trong bộ nhớ; cần chuyển xuống repository nếu dữ liệu tăng lớn.

## 1. Auth

| Method | Path | Vai trò | Mục đích | Làm |
|---|---|---|---|---|
| POST | `/auth/login` | — | `{code, password}`; vai trò lấy từ DB → access + refresh token | ✅ |
| POST | `/auth/refresh` | — | `{refresh_token}` → cặp token mới; token cũ chỉ dùng được 1 lần | ✅ |
| POST | `/auth/logout` | — | `{refresh_token}` → xóa refresh token đó | ✅ |
| GET | `/me` | T/S | Thông tin tài khoản hiện tại | ✅ |
| PUT | `/me/password` | T/S | Đổi mật khẩu; xóa mọi refresh token của tài khoản | ✅ |

Giáo viên đầu tiên: `./bin/seed` (không qua API).

## 2. Sinh viên

| Method | Path | Vai trò | Mục đích | Làm |
|---|---|---|---|---|
| GET | `/students` | T | Danh sách sinh viên, tìm theo code/tên, phân trang | ✅ |
| POST | `/students` | T | Tạo 1 sinh viên, mật khẩu mặc định = ngày sinh `ddmmyyyy` | ✅ |
| POST | `/students/import` | T | Import CSV (`full_name,code,date_of_birth[,class]`) | ✅ |
| POST | `/students/{id}/reset-password` | T | Đặt lại mật khẩu về ngày sinh `ddmmyyyy`, thu hồi refresh token | ✅ |

## 3. Đề và phiên

| Method | Path | Vai trò | Mục đích | Làm |
|---|---|---|---|---|
| GET | `/exams` | T | Đề của giáo viên | ✅ |
| POST | `/exams` | T | Tạo đề | ✅ |
| GET | `/exams/{id}` | T | Chi tiết đề | ✅ |
| PUT | `/exams/{id}` | T | Sửa đề | ✅ |
| DELETE | `/exams/{id}` | T | Xóa đề (chặn nếu còn problem hoặc phiên) | ✅ |
| GET | `/exams/{id}/sessions` | T | Các phiên của đề | ✅ |
| POST | `/exams/{id}/sessions` | T | Tạo phiên (`starts_at`, `ends_at` bắt buộc) | ✅ |
| GET | `/sessions/{id}` | T/S | Thông tin phiên, giờ server, trạng thái của mình (S), danh sách problem | ✅ |
| PUT | `/sessions/{id}` | T | Sửa phiên | ✅ |
| DELETE | `/sessions/{id}` | T | Xóa phiên (chặn nếu đã có bài nộp) | ✅ |
| GET | `/sessions/{id}/participants` | T | Người tham gia và trạng thái | ✅ |
| GET | `/me/sessions` | S | Mọi phiên + trạng thái tham gia của mình | ✅ |
| POST | `/sessions/{id}/join` | S | Tham gia (chỉ trong khung giờ; vào lại dùng bản ghi cũ) | ✅ |
| POST | `/sessions/{id}/finish` | S | Kết thúc làm bài (idempotent) | ✅ |

## 4. Problem và test

| Method | Path | Vai trò | Mục đích | Làm |
|---|---|---|---|---|
| GET | `/exams/{id}/problems` | T | Problem của đề | ✅ |
| POST | `/exams/{id}/problems` | T | Tạo problem; tạo thư mục test trên đĩa | ✅ |
| GET | `/problems/{id}` | T | Chi tiết problem | ✅ |
| PUT | `/problems/{id}` | T | Sửa; đổi time/memory limit → tăng version + chấm lại | ✅ |
| DELETE | `/problems/{id}` | T | Xóa (chặn nếu đã có bài nộp) | ✅ |
| GET | `/problems/{id}/tests` | T | Danh sách test hiện có trong DB (đường dẫn tương đối) | ✅ |
| POST | `/problems/{id}/rejudge` | T | Quét thư mục test, đồng bộ `test_cases`, tăng version, chấm lại mọi bài nộp của problem ở mọi phiên | ✅ |
| GET | `/sessions/{sid}/problems/{pid}` | S | Đề bài; chỉ từ `starts_at` và sau khi đã tham gia | ✅ |

## 5. Nộp bài

| Method | Path | Vai trò | Mục đích | Làm |
|---|---|---|---|---|
| POST | `/sessions/{sid}/problems/{pid}/submissions` | S | Upload 1 file `.cpp` ≤ 64 KB; trả 202 + id | ✅ |
| GET | `/sessions/{sid}/submissions` | T/S | S: bài của mình; T: mọi bài, lọc `student_id`, `problem_id` | ✅ |
| GET | `/submissions/{id}` | T/S | Trạng thái run hiện hành, verdict, passed/total, compile output đã cắt | ✅ |
| GET | `/submissions/{id}/source` | T/S | Tải mã nguồn (S chỉ bài của mình) | ✅ |
| GET | `/submissions/{id}/tests` | T | Kết quả từng test (phúc khảo) | ✅ |
| POST | `/submissions/{id}/rejudge` | T | Chấm lại thủ công 1 bài tại version hiện hành | ✅ |

## 6. Trạng thái chấm, WebSocket, hàng đợi

| Method | Path | Vai trò | Mục đích | Làm |
|---|---|---|---|---|
| GET | `/ws` | T/S | WebSocket; message đầu `auth`, `subscribe` phiên; server đẩy `run.updated`, `summary.updated` (GV), `announcement`, `resync` | ✅ |
| POST | `/sessions/{id}/announcements` | T | Gửi thông báo tới cả phiên (đẩy qua WS) | ✅ |
| GET | `/sessions/{id}/announcements` | T/S | Thông báo của phiên (xem lại khi nối lại) | ✅ |
| GET | `/admin/queue` | T | Số job theo trạng thái, worker đang chạy, thời gian chờ/chấm trung bình | ✅ |

Message WebSocket: JSON `{"type": string, "data": object}`. Server kiểm tra Origin. Chi tiết ở cuối tài liệu.

## 7. Tổng hợp AC

| Method | Path | Vai trò | Mục đích | Làm |
|---|---|---|---|---|
| GET | `/sessions/{id}/summary` | T | Mỗi SV × problem: AC / chưa / đang chấm; tổng `x/N AC`; SV online; hàng đợi | ✅ |
| GET | `/sessions/{id}/summary/me` | S | Tổng hợp của chính mình | ✅ |

Chỉ tính run có `problem_version = problems.version`. Không có điểm số.

## 8. Export

| Method | Path | Vai trò | Mục đích | Làm |
|---|---|---|---|---|
| GET | `/sessions/{id}/export.csv` | T | Xuất bảng tổng hợp AC | ✅ |

Export zip source: để sau v1.

## 9. Hệ thống

| Method | Path | Vai trò | Mục đích | Làm |
|---|---|---|---|---|
| GET | `/healthz` | — | Ping DB | ✅ |

## Chi tiết Auth

### Lỗi

Mọi lỗi trả `{"error": "<message>"}`.

| HTTP | Khi nào |
|---|---|
| 400 | Body sai JSON, thiếu trường, mật khẩu mới không hợp lệ |
| 401 | Sai `code`/mật khẩu, access token thiếu/hết hạn/sai, refresh token không tồn tại/hết hạn/đã dùng |
| 403 | Đúng token nhưng sai vai trò cho route |

### `POST /auth/login`

```json
// request
{"code": "B21DCCN001", "password": "B21DCCN001"}
// 200
{
  "access_token": "<jwt>",
  "access_expires_at": "2026-10-21T09:15:00Z",
  "refresh_token": "<random base64url>",
  "refresh_expires_at": "2026-10-28T09:00:00Z",
  "user": {"id": 1, "role": "student", "code": "B21DCCN001", "fullname": "..."}
}
```

`code` được trim và viết hoa trước khi tra cứu (`b21dccn001` = `B21DCCN001`).

### `POST /auth/refresh`

Request `{"refresh_token": "..."}`; response giống login. Token cũ bị xóa trong cùng transaction.

### `POST /auth/logout`

Request `{"refresh_token": "..."}`; 204. Token không tồn tại vẫn trả 204.

### `GET /me`

200 `{"id": 1, "role": "teacher", "code": "GV01", "fullname": "..."}`.

### `PUT /me/password`

Request `{"current_password": "...", "new_password": "..."}`; 204. Mật khẩu mới 1–72 byte. Xóa mọi refresh token của tài khoản; access JWT đã cấp còn hiệu lực đến hết hạn.

### JWT

HS256, ký bằng `JWT_SECRET`. Claims: `sub` (id dạng chuỗi), `role`, `iat`, `exp` (15 phút).

## Chi tiết Sinh viên

Mọi route yêu cầu access token của **giáo viên** (403 nếu là sinh viên). `code` được trim + viết hoa; `fullname` được gộp khoảng trắng. Giới hạn: `code` 1–50 ký tự, không chứa khoảng trắng; `fullname` 1–200 ký tự.

**Ngày sinh và mật khẩu ban đầu.** `date_of_birth` bắt buộc khi tạo/import, nhận `dd/mm/yyyy` (cũng chấp nhận `-` hoặc `.` thay `/`, ngày/tháng 1 chữ số như `5/9/2004`) hoặc ISO `yyyy-mm-dd` (Excel hay xuất kiểu này). Ngày phải có thật, năm ≥ 1900 và không ở tương lai. Mật khẩu ban đầu = ngày sinh dạng **`ddmmyyyy`**, ví dụ `05/09/2004` → `05092004`. Tên đăng nhập là `code` (MSV, viết hoa). Sinh viên tạo trước khi có cột này có `date_of_birth: null` và mật khẩu ban đầu vẫn là `code`.

Đối tượng sinh viên:

```json
{"id": 2, "code": "B21DCCN001", "fullname": "Nguyen Van A", "date_of_birth": "2004-09-05",
 "created_at": "2026-10-21T09:00:00Z", "updated_at": "2026-10-21T09:00:00Z"}
```

### `POST /students`

Request `{"code": "b21dccn001", "fullname": "Nguyen Van A", "date_of_birth": "05/09/2004"}` → 201 + đối tượng sinh viên. 400 nếu không hợp lệ, 409 nếu `code` đã thuộc một tài khoản khác (kể cả giáo viên).

### `GET /students?query=&page=1&pageSize=20`

`query` tìm chuỗi con (không phân biệt hoa thường) trong `code` hoặc `fullname`. Phân trang theo quy ước PagedList bên dưới. Sắp theo `code`.

```json
{"data": [ ... ], "page": 1, "pageSize": 20, "totalPages": 3}
```

### `POST /students/import`

`multipart/form-data`, trường `file` là CSV UTF-8 (cho phép BOM của Excel), tối đa 1 MiB / 1000 dòng.

- Dòng đầu là header với 3 cột bắt buộc, thứ tự chuẩn **`full_name,code,date_of_birth`** (họ tên, MSV, ngày sinh). Tên cột thay thế: `fullname`, `dob`. Server đọc theo tên cột nên đổi thứ tự cũng được. Cột khác (ví dụ `class`) bị bỏ qua vì bảng `users` không có cột lớp. Mẫu: `examples/students-import.csv`.

```csv
full_name,code,date_of_birth
Nguyễn Văn An,B21DCCN001,05/09/2004
```

- **Dòng lỗi** (thiếu/quá dài, **thiếu hoặc sai ngày sinh**, `code` lặp lại trong cùng file): từ chối cả file, **không lưu gì**, trả 400:

```json
{"error": "import rejected, nothing was saved: 2 invalid row(s)",
 "row_errors": [{"line": 3, "code": "SV02", "error": "date_of_birth is required"},
                {"line": 4, "code": "SV01", "error": "duplicate of line 2 in this file"}]}
```

- **`code` đã có trong DB**: bỏ qua dòng đó và báo lại (import lại cùng danh sách là an toàn). Các dòng còn lại được thêm trong một transaction.

```json
{"created_count": 2, "skipped_count": 2,
 "created": [ ...đối tượng sinh viên... ],
 "skipped": [{"line": 3, "code": "SV02", "reason": "already exists"},
             {"line": 4, "code": "GV01", "reason": "code belongs to a teacher account"}]}
```

### `POST /students/{id}/reset-password`

204. Mật khẩu về lại ngày sinh `ddmmyyyy` (hoặc `code` nếu sinh viên không có ngày sinh); xóa mọi refresh token của sinh viên. Access token đã cấp còn hiệu lực tới hết hạn (≤ 15 phút). 404 nếu không có sinh viên.

## Chi tiết Đề, phiên và problem

### Quy tắc chung

- Giáo viên chỉ thấy và sửa đề của chính mình; đề/phiên/problem của giáo viên khác trả 404.
- Thời gian dùng RFC 3339 (`2026-10-21T09:00:00Z`). Server là nguồn thời gian; `state` của phiên là `upcoming` (trước `starts_at`), `running` (`starts_at <= now < ends_at`) hoặc `ended`.
- Lỗi: 400 dữ liệu sai, 403 sai vai trò hoặc chưa được xem, 404 không có/không thuộc quyền, 409 trạng thái không cho phép. Body `{"error": "..."}`.

### Đề

- `POST /exams`, `PUT /exams/{id}`: `{"title": "...", "description": "..."}`; `title` 1–255 ký tự.
- `GET /exams` → `PagedList<Exam>`, mới nhất trước. Exam: `{id, title, description, created_at, updated_at}`.
- `GET /exams/{id}` → thông tin đề (`id`, `title`, `description`, `created_at`, `updated_at`) kèm `problems: [{id, code, title}]`. Array đầy đủ, sắp theo `code` rồi `id`, rỗng trả `[]`, không bọc PagedList. Chỉ giáo viên sở hữu đề; đề không tồn tại/thuộc người khác trả 404.
- `DELETE /exams/{id}` → 204; 409 nếu đề còn problem hoặc phiên (xóa chúng trước).

### Phiên

- `POST /exams/{id}/sessions`, `PUT /sessions/{id}`: `{"name": "...", "starts_at": "...", "ends_at": "..."}`; bắt buộc cả hai mốc, `ends_at > starts_at`. Backend không tự đặt thời lượng.
- Session: `{id, exam_id, exam_title, name, starts_at, ends_at, state}`.
- `DELETE /sessions/{id}` → 204 (xóa luôn danh sách tham gia); 409 nếu đã có bài nộp.
- `GET /sessions/{id}/participants` (T) → `PagedList<Participant>`. Participant: `{id, session_id, student_id, student_code, student_fullname, status, joined_at, finished_at}`; `status` là `in_progress` hoặc `finished`.
- `GET /sessions/{id}` (T/S) → `{"session": ..., "server_time": ..., "problems": [{id, code, title}], "problems_hidden": bool}`; sinh viên có thêm `participation` (participant hoặc `null`). Với sinh viên, trước `starts_at` thì `problems` rỗng và `problems_hidden = true`.
- `GET /me/sessions` (S) → `PagedList<Session + participation>` kèm `server_time`: mọi phiên của mọi đề.
- `POST /sessions/{id}/join` (S) → 200 participant. Chưa tham gia: chỉ được khi `running`, ngoài ra 409 (`session has not started` / `session has ended`). Đã tham gia: trả bản ghi cũ ở mọi trạng thái (vào lại sau mất mạng).
- `POST /sessions/{id}/finish` (S) → 200 participant `finished`. Gọi lại trả kết quả cũ, không đổi `finished_at`. 409 nếu chưa tham gia. Không có API mở lại.

### Problem

- `POST /exams/{id}/problems`, `PUT /problems/{id}` (cập nhật toàn bộ):

```json
{"code": "A", "title": "Tong hai so", "statement": "...", "time_limit_ms": 1000, "memory_limit_mb": 256}
```

  `code` trim + viết hoa, 1–20 ký tự, không khoảng trắng, duy nhất trong đề (409 nếu trùng). `time_limit_ms` 1–10000, `memory_limit_mb` 1–1024 (chặn nhập nhầm, chưa phải giới hạn chính thức).
- Problem: `{id, exam_id, code, title, statement, time_limit_ms, memory_limit_mb, version, test_dir, created_at, updated_at}`. `test_dir` là thư mục test tương đối với `DATA_DIR` (trên máy server: `./data/problems/<id>/tests`), được tạo khi tạo problem.
- `PUT /problems/{id}` → `{"problem": ..., "rejudge_queued": bool, "requeued_submissions": n}`. Đổi `time_limit_ms` hoặc `memory_limit_mb` → `version + 1` và chấm lại mọi bài nộp (`rejudge_queued: true`); chỉ sửa chữ thì không.
- `DELETE /problems/{id}` → 204 (xóa luôn các dòng `test_cases`; file test trên đĩa giữ nguyên); 409 nếu đã có bài nộp.
- `GET /problems/{id}/tests` → `PagedList<{id, input, output}>` kèm `test_dir` theo thứ tự chấm và `disk: {tests, error?, in_sync}` — trạng thái thư mục test trên đĩa (chỉ đọc): số cặp hợp lệ, lý do quét sẽ lỗi (thiếu `.out`...), và có trùng danh sách test đã đăng ký không (không so nội dung). `in_sync = false` nghĩa là đã chép file nhưng chưa bấm chấm lại.
- `POST /problems/{id}/rejudge` → quét `test_dir`:
  - mỗi `N.in` phải có `N.out` và ngược lại; file rỗng hợp lệ; file khác đuôi và file ẩn bị bỏ qua; symlink/thư mục con bị từ chối; tối đa 500 test;
  - thứ tự: tên là số thì theo số (`2` trước `10`), còn lại theo tên;
  - lỗi file → 409, không đổi gì;
  - thành công → thay `test_cases`, `version + 1`, chấm lại mọi bài nộp của problem ở mọi phiên → `{"problem": ..., "tests": [{name, input, output, input_size, output_size}], "rejudge_queued": true, "requeued_submissions": n}`.
  - Chấm lại (cả khi đổi limit): trong cùng transaction, mọi run/job hiện hành của problem chuyển `obsolete` (worker đang chấm version cũ bị loại kết quả), tạo run + job `queued` ở version mới cho mọi bài nộp, mọi phiên. Trong lúc chờ, `result` của bài nộp là run mới `queued`; kết quả cũ không còn là hiện hành (AC cũ có thể mất).
- `GET /sessions/{id}/problems/{pid}` (S) → `{id, code, title, statement, time_limit_ms, memory_limit_mb}`. 403 trước `starts_at` hoặc khi chưa tham gia phiên; 404 nếu problem không thuộc đề của phiên. Sau khi phiên kết thúc vẫn xem được.

## Chi tiết Nộp bài

### `POST /sessions/{id}/problems/{pid}/submissions` (S)

`multipart/form-data`, trường `file`: tên đuôi `.cpp` (không phân biệt hoa thường), 1 byte – 64 KB, không bắt buộc UTF-8. Tên gốc không được lưu; server lưu `DATA_DIR/submissions/<năm>/<tháng>/<32 hex>.cpp` và `fsync` trước khi ghi DB.

- `received_at` = giờ server khi đã nhận đủ file.
- Trong một transaction (khóa problem → phiên → participant): problem thuộc đề của phiên (404), đã join (409), chưa `finished` (409), `starts_at <= received_at < ends_at` (409), problem có test (409). Tạo submission + run `queued` (version hiện tại) + job `queued`, commit rồi trả **202**.
- Nộp và finish cùng lúc: thao tác nào giữ khóa participant và commit trước thì thắng.
- Lỗi: 400 (thiếu `file`, sai đuôi, rỗng), 413 (> 64 KB), 404, 409. Bị từ chối theo quy tắc → file vừa lưu bị xóa; lỗi DB khác → file giữ lại chờ dọn.

Đối tượng bài nộp:

```json
{"id": 12, "session_id": 3, "problem_id": 5, "problem_code": "A",
 "student_id": 7, "student_code": "B21DCCN001", "student_fullname": "Nguyen Van A",
 "received_at": "2026-10-22T04:10:00Z", "problem_version": 2,
 "result": {"run_id": 30, "problem_version": 2, "status": "queued", "verdict": null,
            "passed_tests": 0, "total_tests": 0, "created_at": "...", "started_at": null, "finished_at": null}}
```

`result` là run hiện hành (cùng version với problem, không `obsolete`); `null` khi problem vừa đổi version và chưa có lượt chấm mới. `status`: `queued`, `judging`, `completed`, `system_error`. FE hiển thị `passed_tests/total_tests verdict`, ví dụ `10/15 TLE`.

### `GET /sessions/{id}/submissions?student_id=&problem_id=` (T/S)

`PagedList<Submission>`, mới nhất trước. SV chỉ thấy bài của mình (`student_id` bị bỏ qua); GV phải sở hữu đề (404 nếu không).

### `GET /submissions/{id}` (T/S)

Bài nộp + `compiler_info`, `compile_output` trong `result`; `system_error` chỉ trả cho GV. SV khác / GV khác → 404.

### `GET /submissions/{id}/source` (T/S)

`text/plain`, tải về dạng `submission-<id>.cpp`. Quyền như trên.

### `GET /submissions/{id}/tests` (T)

`PagedList<{test_case_id, verdict, execution_time_ms, memory_used_kb}>` kèm `submission` của run hiện hành. Khi dừng ở test sai đầu tiên, chỉ các test đã chạy có dòng.

### `POST /submissions/{id}/rejudge` (T)

Chấm lại một bài: run hiện hành và job của nó chuyển `obsolete`, tạo run + job mới ở version hiện tại; bài khác không đổi. Trả **202** + đối tượng bài nộp (`result.status = "queued"`). GV không sở hữu đề → 404. Mỗi (bài nộp, version) có tối đa một run không `obsolete` (unique index một phần `judge_runs_current_idx`).

## Chi tiết Tổng hợp và thông báo

### `GET /sessions/{id}/summary` (T) — `GET /sessions/{id}/summary/me` (S)

```json
{"session_id": 3, "problems_total": 2,
 "problems": [{"id": 5, "code": "A", "title": "Tong"}, {"id": 6, "code": "B", "title": "Hieu"}],
 "rows": [{"student_id": 7, "student_code": "B21DCCN001", "student_fullname": "Nguyen Van A",
           "status": "in_progress", "online": true, "ac_count": 1,
           "cells": [{"problem_id": 5, "state": "ac", "submissions": 2,
                      "latest": {"submission_id": 41, "status": "completed", "verdict": "WA", "passed_tests": 10, "total_tests": 15}},
                     {"problem_id": 6, "state": "none", "submissions": 0, "latest": null}]}],
 "online_count": 1, "queue": {"queued": 3, "running": 2}, "generated_at": "..."}
```

- `state`: `ac` (có run hiện hành AC — bài sai nộp sau **không** làm mất AC cùng version), `pending` (đang chờ/chấm, hoặc chờ chấm lại), `attempted` (đã chấm, không AC, kể cả `system_error`), `none`. FE hiện `ac_count/problems_total AC`.
- `latest`: kết quả hiện hành của bài nộp mới nhất trong ô (dạng `10/15 TLE`).
- `online`/`online_count`: SV đang mở WebSocket và subscribe phiên này. `queue`: toàn hệ thống.
- SV (`/summary/me`): chỉ dòng của mình, phải đã tham gia (403), trước `starts_at` không có `problems`/`cells`; `online_count` = 0.

### Thông báo

- `POST /sessions/{id}/announcements` (T, chủ đề) `{"text": "Còn 10 phút"}` (1–1000 ký tự) → 201 `{id, session_id, text, created_at}` và đẩy ngay `announcement` qua WebSocket tới mọi client đang subscribe phiên.
- `GET /sessions/{id}/announcements` (T/S) → `{"data": [...]}` mới nhất trước, tối đa 50.
- Lưu **trong bộ nhớ API** (không có bảng; mất khi API khởi động lại). Chọn REST + WS thay vì gửi qua WS: có kiểm tra quyền/validation/mã lỗi như mọi API, GV gửi được kể cả khi không mở WS, dễ test.

## Chi tiết WebSocket

`GET /api/v1/ws` (HTTP Upgrade). Không dùng header Authorization: token gửi trong message đầu để không lộ trên URL/log.

- **Origin:** chỉ cùng origin với API, cộng các mẫu trong biến môi trường `WS_ALLOWED_ORIGINS` (vd. `localhost:5173` cho FE dev). Origin lạ → 403, không upgrade.
- **Message:** JSON text `{"type": string, "data": object}`, tối đa 64 KB từ client.

Client → server:

| type | data | Ghi chú |
|---|---|---|
| `auth` | `{"token": "<access JWT>"}` | Bắt buộc là message đầu, trong 5 s. Gửi lại token mới (cùng tài khoản) trước khi hết hạn để giữ kết nối. |
| `subscribe` | `{"session_id": 3}` | Một phiên mỗi lúc. GV chỉ phiên của mình; SV phiên bất kỳ (chỉ nhận bài của mình). |

Server → client:

| type | data | Ai nhận |
|---|---|---|
| `auth.ok` | `{user_id, role, expires_at}` | người vừa xác thực |
| `subscribed` | `{session_id}` | người vừa subscribe |
| `run.updated` | bài nộp + `result` hiện hành (giống REST) | SV chủ bài (mọi tab); GV chủ đề đang subscribe phiên đó |
| `summary.updated` | như `GET /sessions/{id}/summary` | GV đang subscribe phiên (gộp ~300 ms, làm mới mỗi 5 s) |
| `announcement` | `{id, session_id, text, created_at}` | mọi client đang subscribe phiên |
| `resync` | `{}` | mọi client, khi server có thể đã lỡ sự kiện → tải lại bằng REST |
| `error` | `{message}` | message không hợp lệ / subscribe bị từ chối |
| `screen.list` | `{session_id, students: [{student_id, sharing}]}` | GV đang subscribe phiên: sau `subscribed` và mỗi khi SV vào/ra/bật/tắt chia sẻ |
| `screen.view` | `{view_id, teacher_id}` | SV đang chia sẻ: có GV muốn xem → tạo offer |
| `screen.view.started` | `{view_id, student_id}` | GV vừa yêu cầu xem |
| `webrtc.signal` | `{view_id, kind, payload}` | đầu kia của đúng view đó |
| `screen.stop` | `{view_id, reason}` | cả GV và SV của view |

Vòng đời một bài: nộp (REST 202) → `run.updated` `queued` → `judging` → `completed` (`passed_tests/total_tests verdict`). Chấm lại (problem hoặc 1 bài) → mọi SV bị ảnh hưởng nhận `queued` ở version mới rồi kết quả mới.

- **Mã đóng:** `4001` xác thực sai / không gửi auth kịp / token hết hạn; `1008` client đọc quá chậm (bộ đệm đầy) hoặc quá 8 kết nối/tài khoản; `1000` bình thường.
- **Ping/pong** mỗi 20 s; không có pong → server đóng (phát hiện mất mạng).
- **Nối lại:** client mở lại kết nối, `auth` + `subscribe`, rồi tải trạng thái bằng REST (`/submissions/{id}`, `/sessions/{id}/summary`, `/announcements`). Server không gửi lại sự kiện đã lỡ.
### Chia sẻ màn hình (phase 16)

SV trong phiên đang mở phải chia sẻ **toàn bộ màn hình** mới làm bài được (giao diện khóa). GV của phiên thấy ai đang chia sẻ và xem trực tiếp màn hình một SV. Video đi **ngang hàng qua LAN** bằng WebRTC (`iceServers: []`, không STUN/TURN, không qua server); server chỉ giữ trạng thái và chuyển tín hiệu.

Client → server (thêm):

| type | data | Ai gửi / kiểm tra |
|---|---|---|
| `screen.state` | `{sharing: bool}` | SV đã subscribe phiên; gửi lại sau mỗi `subscribed` (server quên khi nối lại/đổi phiên) |
| `screen.view` | `{student_id}` | GV đã subscribe phiên của mình; SV phải đang chia sẻ trong **cùng phiên**; tối đa 15 view/kết nối GV (lưới 5×3) |
| `webrtc.signal` | `{view_id, kind: "offer"\|"answer"\|"ice", payload}` | chỉ hai kết nối của view; server chuyển nguyên văn cho đầu kia |
| `webrtc.signal` | `{view_id, kind: "quality", payload: {fps, bitrate}}` | chỉ GV của view; SV giới hạn fps (khung/giây) và bitrate (bit/s) bộ mã hóa cho view đó, giữ nguyên độ phân giải |
| `screen.stop` | `{view_id}` | một trong hai đầu |

Luồng: GV `screen.view` → server tạo `view_id`, gửi `screen.view` cho kết nối SV đang chia sẻ (tab mới nhất) và `screen.view.started` cho GV → SV tạo `RTCPeerConnection`, gắn track màn hình, gửi `offer` → GV trả `answer` → hai bên trao `ice` → video chạy. View kết thúc (`screen.stop` cho cả hai) khi một bên gửi `screen.stop`, SV tắt chia sẻ, một bên mất kết nối hoặc đổi phiên.

- Trình duyệt chỉ cho `getDisplayMedia` trong *secure context* → giao diện chạy trên HTTPS `https://<IP>:8443` (chứng chỉ tự ký, server tự tạo trong `DATA_DIR/tls`, phủ `localhost` + `TLS_HOSTS`). `http://<IP>:8080/` từ máy khác chuyển hướng sang HTTPS; HTTP vẫn dùng cho script.
- Giới hạn message từ client: 64 KB (SDP vài KB).
- Server **không** chặn nộp bài khi SV tắt chia sẻ (khóa ở giao diện); GV thấy trạng thái trong tab "Màn hình SV".

- **Nguồn sự kiện:** mọi chỗ đổi trạng thái run gọi `pg_notify('judge_events', ...)` trong cùng transaction (chỉ gửi sau COMMIT); API giữ một kết nối `LISTEN`, nạp lại trạng thái từ DB rồi đẩy. Mất kết nối `LISTEN` → tự nối lại (backoff 1–10 s) và gửi `resync`.

## Chi tiết Export và hàng đợi

### `GET /sessions/{id}/export.csv` (T, chủ đề)

File `session-<id>-summary.csv`: UTF-8 **có BOM** (Excel hiển thị đúng tiếng Việt), dấu phẩy, xuống dòng CRLF.

```
student_code,fullname,status,A,B,ac
B21DCCN001,Nguyễn Văn Ánh,finished,AC,10/15 TLE,1/2 AC
B21DCCN002,Trần Thị B,in_progress,pending,,0/2 AC
```

Ô problem: `AC`; kết quả hiện hành của bài mới nhất (`10/15 TLE`); `pending` (đang chấm); `system_error`; trống (chưa nộp). Họ tên/mã bắt đầu bằng `= + - @` được thêm `'` để Excel không chạy như công thức.

### `GET /admin/queue` (T)

```json
{"jobs": {"queued": 2, "running": 1, "done": 120, "failed": 0, "obsolete": 15},
 "active_workers": ["7f84be079c06"], "oldest_queued_ms": 850,
 "recent_runs": 40, "avg_queue_wait_ms": 312.5, "avg_judge_ms": 180.2, "generated_at": "..."}
```

`recent_runs`, `avg_*`: run bắt đầu trong 10 phút gần nhất (chờ = `created_at → started_at`, chấm = `started_at → finished_at` của run `completed`). Toàn hệ thống, không theo phiên.
