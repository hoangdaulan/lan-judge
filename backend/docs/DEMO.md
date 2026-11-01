# Kịch bản demo (~10 phút)

Mục tiêu: cho thấy phần **mạng** của hệ thống — nhiều client đồng thời, protocol WebSocket, hàng đợi + worker, xử lý lỗi (mất kết nối, worker chết, khởi động lại).

Mọi lệnh trong tài liệu chạy tại `backend/`: **Ubuntu WSL2** trên Windows, **Terminal** trên macOS/Linux. Chuẩn bị Docker, `.env`, migrate và giáo viên theo [README](../../README.md#2-cài-đặt) trước. Các lệnh Bash dùng `curl` và `python3`; diễn tập tự động và các công cụ `bin/*` cần thêm **JDK 17** trên host.

Diễn tập tự động toàn bộ kịch bản trên một bộ cài dùng để thử (mỗi bước tự kiểm tra kết quả):

```bash
TEACHER_CODE=GV01 TEACHER_PASSWORD='<mật khẩu>' bash scripts/demo-run.sh
```

Mong đợi dòng cuối **Demo OK** và mã thoát 0. Script tự build công cụ Java, tạo dữ liệu mẫu, thử AC/WA/TLE, WebSocket, chấm lại, dừng worker giữa lúc chấm và giả lập 50 sinh viên. Nó giữ dữ liệu thử và dừng/khởi động worker; chạy khi không có phiên thi thật. Nếu bước phục hồi lỗi, script vẫn thử khởi động lại worker khi thoát. Không cần tạo `.smoke.env` để chạy lệnh này.

Bước phục hồi phải đợi lease của job cũ hết hạn (mặc định khoảng 60 giây, cộng thời gian khởi động/nhận lại/chấm). Script đợi tối đa 180 giây và kiểm tra `attempt_count >= 2`, không áp một ngưỡng thời gian giống nhau cho mọi máy.

## Chuẩn bị (trước giờ demo)

```bash
curl --fail --show-error http://localhost:8080/healthz
TEACHER_CODE=GV01 TEACHER_PASSWORD='<mật khẩu>' bash scripts/demo-setup.sh
```

`demo-setup.sh` in ra `session_id`, `problem_A` (a+b, 15 test), `problem_B` (tổng 1..n, giới hạn 200 ms — vòng lặp ngây thơ bị TLE) và tạo SV `DEMO01..DEMO05` (ngày sinh 01/01/2004, mật khẩu = `01012004`).

Mỗi lần chạy tạo đề/phiên mới; các MSV đã có được giữ nguyên. Nếu từng đổi mật khẩu `DEMO01`, đặt lại về ngày sinh trước diễn tập. Nếu chỉ chạy thử trên web thì không cần JDK; làm theo [Chạy thử nhanh](../../README.md#26-chạy-thử-nhanh-với-dữ-liệu-mẫu) có code C++ mẫu. Chạy `make build` trước nếu dùng `./bin/wsprobe` hoặc `./bin/loadtest` để demo thủ công.

Nếu đổi cổng HTTP trong `.env`, thêm `BASE=http://localhost:<API_PORT>` trước lệnh chạy script và dùng cổng mới trong URL. Dữ liệu test được ghi vào `./data` của máy chủ; công cụ tạo test phải chạy ngay trên máy chủ (hoặc truyền `DATA_HOST_DIR` cho script / `-data` cho loadtest trỏ tới thư mục bind mount thực tế).

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
| 1 | Giới thiệu kiến trúc ở đầu README: API, worker, PostgreSQL, `./data`; 2 mạng Docker | Worker không ra Internet; bài SV chạy trong namespace mạng rỗng |
| 2 | Terminal SV/GV: `auth` → `auth.ok`, `subscribe` → `subscribed` | Protocol `{type, data}`, token gửi trong message đầu (không trên URL) |
| 3 | SV `DEMO01` tham gia phiên (Swagger `POST /sessions/{id}/join`), nộp A đúng | Terminal SV: `run.updated` `queued → judging → completed 15/15 AC` không cần F5; terminal GV: `summary.updated` |
| 4 | Nộp A sai (in `a-b`) và B vòng lặp ngây thơ với biến tổng `volatile` để tránh tối ưu vòng lặp | `0/15 WA`; `x/10 TLE` (số test qua phụ thuộc máy; dừng ở test sai đầu tiên) |
| 5 | GV `POST /sessions/{id}/announcements {"text":"Còn 10 phút"}` | Mọi client của phiên nhận `announcement` ngay |
| 6 | Sửa `./data/problems/<A>/tests/15.out` trên máy chủ → `POST /problems/<A>/rejudge` | Bài AC lúc trước thành `14/15 WA` (version mới); sửa lại → `15/15 AC` |
| 7 | Nộp B lần nữa, khi đang `judging` thì `docker compose kill worker`, rồi `docker compose up -d worker` | Bài không mất: sau khi lease cũ hết hạn (mặc định khoảng 60 giây), worker phục hồi job và chấm lại (`attempt_count >= 2`) |
| 8 | Rút mạng / tắt Wi-Fi máy SV vài giây rồi nối lại | Kết nối WS đóng (ping/pong); client nối lại + `GET /submissions/{id}` lấy trạng thái hiện tại |
| 8b | SV chia sẻ toàn màn hình (trang làm bài mới mở khóa); GV tab "Màn hình SV" → "Xem màn hình". SV bấm "Dừng chia sẻ" trên thanh của trình duyệt | GV thấy màn hình SV trực tiếp (WebRTC ngang hàng, server chỉ chuyển tín hiệu qua WebSocket); SV tắt → trang SV khóa lại, GV thấy "đã tắt" |
| 9 | `SMOKE_TEACHER_CODE=GV01 SMOKE_TEACHER_PASSWORD='<mật khẩu>' ./bin/loadtest -base http://<IP>:8080 -students 50 -tests 15 -mode ws` | 50 SV đăng nhập + nộp cùng lúc: mong đợi 50/50 AC, không lỗi HTTP; bảng GV nhảy số |
| 10 | Mở `docs/results/RESULTS.md` | Số đo lịch sử của bản Go; đo lại bằng `make experiment` nếu cần báo cáo hiệu năng bản Java trên máy triển khai |

## Câu hỏi hay gặp khi bảo vệ (gợi ý chỗ xem code)

- *Sao không nộp bài qua WebSocket?* Upload cần giới hạn kích thước (413), mã lỗi rõ ràng, xác nhận "đã nhận" sau COMMIT → REST (`src/main/java/vn/lanlab/Api.java`).
- *Hai worker có chấm trùng một bài không?* Không: `FOR UPDATE SKIP LOCKED` + lease token; ghi kết quả kiểm tra lại token và version (`src/main/java/vn/lanlab/JudgeQueue.java`, test `concurrentClaimsNeverTakeTheSameJob`).
- *Nộp và bấm kết thúc cùng lúc?* Cả hai khóa cùng dòng `exam_participants`; thao tác commit trước thắng (`src/main/java/vn/lanlab/Submissions.java`).
- *Client bỏ lỡ sự kiện thì sao?* WebSocket chỉ báo; REST là nguồn sự thật; mất `LISTEN` → server gửi `resync` (`src/main/java/vn/lanlab/Realtime.java`).
- *Bài SV có đọc được đáp án không?* Chạy bằng uid riêng, `./data` chmod 0750, không capability, không mạng (`native/sandbox.c`, `make test-sandbox`).
