# Backend Java 17 / Spring Boot

Backend dùng Spring Boot 3.5.16, Java 17, JDBC PostgreSQL và Maven Wrapper. API và worker là hai tiến trình/container riêng, chạy cùng JAR bằng các chế độ `api` và `worker`. Giao diện React và các asset offline giữ nguyên.

## Hợp đồng được giữ

- Toàn bộ đường dẫn REST `/api/v1`, vai trò giáo viên/sinh viên, PagedList và định dạng JSON; JWT HS256, bcrypt, refresh token SHA-256 và quy tắc đổi/reset mật khẩu.
- Schema và hai file migration SQL hiện có. Lệnh migrate Java đọc/ghi `goose_db_version`, vì vậy database đã migrate bằng bản Go tiếp tục được nhận diện. Không tạo lại database hoặc chuyển đổi dữ liệu.
- Đường dẫn và nội dung source/test trong `DATA_DIR`; thời gian phiên, join/finish, giới hạn upload, chấm lại và cách tính AC.
- Hàng đợi PostgreSQL: thứ tự khóa problem → run → job; lease, heartbeat, retry, recovery, kiểm tra version trước khi lưu kết quả. Nộp bài và finish vẫn được sắp thứ tự bằng khóa participant.
- TCP framing big-endian, mã message 0x01–0x09, JSON snapshot PascalCase; polling dự phòng khi dispatcher ngắt kết nối.
- UDP discovery/multicast `LANLAB/1`, cổng và biến môi trường, TTL multicast 1; các file Compose và override host networking cho LAN.
- WebSocket xác thực ở message đầu, subscribe, kết quả trực tiếp, bảng giáo viên, thông báo trong bộ nhớ (50/phiên), trạng thái chia sẻ màn hình và chuyển tiếp tín hiệu WebRTC. Video vẫn đi ngang hàng trong LAN.
- HTTP 8080, HTTPS 8443, chứng chỉ `DATA_DIR/tls/cert.pem` và `key.pem`, chuyển hướng trang từ LAN sang HTTPS. Swagger UI được đóng gói để dùng offline.

## Chạy và phát triển

Docker vẫn dùng các lệnh cũ trong `backend/`:

```sh
make up
make migrate
SEED_TEACHER_CODE=GV01 SEED_TEACHER_FULLNAME='Giáo viên' SEED_TEACHER_PASSWORD='...' make seed
```

Phát triển cần JDK 17; Maven Wrapper tự tải Maven khi cần:

```sh
make build
make test
# Sau khi cấu hình DATABASE_URL, JWT_SECRET (ít nhất 32 byte), DATA_DIR:
./bin/api
./bin/lannet discover
./bin/wsprobe -base http://localhost:8080 -code GV01 -session 1
```

`bin/*` là launcher của `target/lan-judge.jar`. Các chế độ khác: `worker`, `migrate up|down|status`, `seed`, `lannet`, `wsprobe`, `loadtest`. Các script demo, smoke, backup/restore và thực nghiệm dùng các launcher Java.

## Sandbox C++

Mọi nghiệp vụ và điều phối chấm chạy bằng Java. `native/sandbox.c` là helper syscall Linux nhỏ, không chứa nghiệp vụ, không có setuid bit. Worker gọi helper để tạo network namespace, bỏ supplementary groups/capability, chuyển sang UID riêng, đặt `no_new_privs` và `setrlimit`, rồi `exec` g++/bài nộp. JVM không bị áp các giới hạn của bài nộp.

Giữ g++ Debian bookworm, C++17, `-O2 -pipe -static -DONLINE_JUDGE`, compile 10 s, output 64 MiB, so token theo byte và dừng ở test sai đầu tiên. Worker không có sandbox phù hợp sẽ giữ job trong hàng đợi. File kết quả/capture thuộc worker, nằm ngoài thư mục runner có quyền sửa.

## Kiểm thử

```sh
make test
make test-integration  # PostgreSQL từ .env; tạo và xóa database thử nghiệm riêng
make test-sandbox      # Docker Linux, đúng capability của worker
make smoke            # stack đang chạy; credentials từ .smoke.env
```

Có thể truyền database thử nghiệm trực tiếp:

```sh
TEST_DATABASE_URL='postgres://user:password@127.0.0.1:5433/judge?sslmode=disable' ./mvnw -Dtest=DatabaseTest test
```

Các số đo hiệu năng Go cũ trong `docs/results/` là dữ liệu lịch sử; cần chạy lại `make experiment` để đo bản Java trên máy triển khai. Chuyển công nghệ không bảo đảm mức dùng RAM và độ trễ giống Go.

## Kết quả xác minh khi chuyển đổi

- 25 kiểm thử Java/JDBC/TCP/UDP qua; 6 kiểm thử sandbox Linux qua.
- Bộ smoke test hiện có: **128 qua, 0 lỗi**, với hai giáo viên để kiểm tra quyền sở hữu.
- Giả lập 50 sinh viên đăng nhập và nộp đồng thời: **50/50 AC**, không lỗi HTTP. Đây là kiểm tra trên cùng máy, chưa phải đo tải qua LAN nhiều máy.
- Client Go cũ đọc được UDP discovery và xác thực WebSocket của server Java. Đã kiểm tra chuyển tiếp offer/quality và dừng view chia sẻ màn hình giữa hai kết nối thật.
- HTTP, HTTPS tự ký, giao diện/Swagger offline và nâng cấp container trên cùng database thử nghiệm hoạt động.

Không thay đổi frontend, migration SQL, schema thiết kế hoặc cấu hình mạng Compose. Mã Go được thay bằng Java; bản cũ còn trong lịch sử Git.
