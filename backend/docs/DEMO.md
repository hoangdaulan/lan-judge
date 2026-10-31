# Kịch bản demo (~10 phút)

Mục tiêu: cho thấy phần **mạng** của hệ thống — nhiều client đồng thời, protocol WebSocket, hàng đợi + worker, xử lý lỗi (mất kết nối, worker chết, khởi động lại).

Diễn tập tự động toàn bộ kịch bản (chạy trước khi bảo vệ; mỗi bước tự kiểm tra kết quả):

```bash
TEACHER_CODE=GV01 TEACHER_PASSWORD='<mật khẩu>' scripts/demo-run.sh
```

Đã diễn tập 23/10/2026: chạy liên tiếp nhiều lần, lần nào cũng "Demo OK" (11/11 kiểm tra).

## Chuẩn bị (trước giờ demo)

```bash
cp .env.example .env            # sửa POSTGRES_PASSWORD, JWT_SECRET, APP_UID/APP_GID
make up && make migrate
SEED_TEACHER_CODE=GV01 SEED_TEACHER_FULLNAME="Giao vien" SEED_TEACHER_PASSWORD='<mật khẩu>' make seed
TEACHER_CODE=GV01 TEACHER_PASSWORD='<mật khẩu>' scripts/demo-setup.sh
```

`demo-setup.sh` in ra `session_id`, `problem_A` (a+b, 15 test), `problem_B` (tổng 1..n, giới hạn 200 ms — vòng lặp ngây thơ bị TLE) và tạo SV `DEMO01..DEMO05` (ngày sinh 01/01/2004, mật khẩu = `01012004`).

Màn hình nên mở sẵn (giao diện web: `https://<IP>:8443/`, chấp nhận cảnh báo chứng chỉ tự ký một lần):
0. Trình duyệt GV: đăng nhập → Đề thi → mở phiên → tab "Bảng trực tiếp". Trình duyệt SV (máy khác, hoặc tab ẩn danh): đăng nhập `DEMO01`/`01012004` → Phiên thi → "Vào làm bài". Các bước 2–7 bên dưới làm được hoàn toàn trên giao diện web (nộp bằng ô soạn code; thông báo ở tab "Thông báo"; chấm lại bằng nút "Quét test & chấm lại" ở trang đề).
1. Swagger UI: `http://<IP>:8080/swagger/index.html` (đăng nhập GV, Authorize).
2. Terminal GV — bảng trực tiếp qua WebSocket:
   `WSPROBE_PASSWORD='<mật khẩu>' ./bin/wsprobe -base http://<IP>:8080 -code GV01 -session <session_id> -duration 30m`
3. Terminal SV (máy khác trong LAN nếu có):
   `./bin/wsprobe -base http://<IP>:8080 -code DEMO01 -password 01012004 -session <session_id> -duration 30m`

## Các bước

| # | Thao tác | Thấy gì / nói gì |
|---|---|---|
| 1 | Giới thiệu kiến trúc (README mục 4): API, worker, PostgreSQL, `./data`; 2 mạng Docker | Worker không ra Internet; bài SV chạy trong namespace mạng rỗng |
| 2 | Terminal SV/GV: `auth` → `auth.ok`, `subscribe` → `subscribed` | Protocol `{type, data}`, token gửi trong message đầu (không trên URL) |
| 3 | SV `DEMO01` tham gia phiên (Swagger `POST /sessions/{id}/join`), nộp A đúng | Terminal SV: `run.updated` `queued → judging → completed 15/15 AC` không cần F5; terminal GV: `summary.updated` |
| 4 | Nộp A sai (in `a-b`) và B vòng lặp ngây thơ | `0/15 WA`; `9/10 TLE` (dừng ở test sai đầu tiên) |
| 5 | GV `POST /sessions/{id}/announcements {"text":"Còn 10 phút"}` | Mọi client của phiên nhận `announcement` ngay |
| 6 | Sửa `./data/problems/<A>/tests/15.out` trên máy chủ → `POST /problems/<A>/rejudge` | Bài AC lúc trước thành `14/15 WA` (version mới); sửa lại → `15/15 AC` |
| 7 | Nộp B lần nữa, khi đang `judging` thì `docker compose kill worker`, rồi `docker compose up -d worker` | Bài không mất: sau khi lease hết hạn worker phục hồi job và chấm lại (`attempt_count = 2`) — dùng `WORKER_LEASE=15s` cho demo |
| 8 | Rút mạng / tắt Wi-Fi máy SV vài giây rồi nối lại | Kết nối WS đóng (ping/pong); client nối lại + `GET /submissions/{id}` lấy trạng thái hiện tại |
| 8b | SV chia sẻ toàn màn hình (trang làm bài mới mở khóa); GV tab "Màn hình SV" → "Xem màn hình". SV bấm "Dừng chia sẻ" trên thanh của trình duyệt | GV thấy màn hình SV trực tiếp (WebRTC ngang hàng, server chỉ chuyển tín hiệu qua WebSocket); SV tắt → trang SV khóa lại, GV thấy "đã tắt" |
| 9 | `./bin/loadtest -base http://<IP>:8080 -students 50 -tests 15 -mode ws` | 50 SV đăng nhập + nộp cùng lúc: 50/50 AC, không lỗi HTTP; bảng GV nhảy số |
| 10 | Mở `docs/results/RESULTS.md` | Số liệu thật: 1 → 8 slot, polling vs NOTIFY, WebSocket vs polling, restart giữa chừng |

## Câu hỏi hay gặp khi bảo vệ (gợi ý chỗ xem code)

- *Sao không nộp bài qua WebSocket?* Upload cần giới hạn kích thước (413), mã lỗi rõ ràng, xác nhận "đã nhận" sau COMMIT → REST (`src/main/java/vn/lanlab/Api.java`).
- *Hai worker có chấm trùng một bài không?* Không: `FOR UPDATE SKIP LOCKED` + lease token; ghi kết quả kiểm tra lại token và version (`src/main/java/vn/lanlab/JudgeQueue.java`, test `concurrentClaimsNeverTakeTheSameJob`).
- *Nộp và bấm kết thúc cùng lúc?* Cả hai khóa cùng dòng `exam_participants`; thao tác commit trước thắng (`src/main/java/vn/lanlab/Submissions.java`).
- *Client bỏ lỡ sự kiện thì sao?* WebSocket chỉ báo; REST là nguồn sự thật; mất `LISTEN` → server gửi `resync` (`src/main/java/vn/lanlab/Realtime.java`).
- *Bài SV có đọc được đáp án không?* Chạy bằng uid riêng, `./data` chmod 0750, không capability, không mạng (`native/sandbox.c`, `make test-sandbox`).
