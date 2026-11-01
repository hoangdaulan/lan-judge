LAN Judge — Hệ thống thi và chấm bài C++ trong mạng LAN
LAN Judge là hệ thống tổ chức thi lập trình C++17 dành cho phòng máy, hoạt động trên mạng LAN. Giáo viên có thể tạo đề thi, quản lý phiên thi và bộ test, theo dõi kết quả theo thời gian thực, xem màn hình sinh viên, gửi thông báo và chấm lại bài sau khi cập nhật test. Sinh viên đăng nhập bằng mã sinh viên, viết hoặc tải mã nguồn C++17 lên trình duyệt, nộp bài và nhận kết quả như 15/15 AC hoặc 9/10 TLE mà không cần tải lại trang.

Hệ thống được triển khai bằng Docker Compose trên một máy chủ trong mạng LAN, gồm ba dịch vụ:
Dịch vụ	Công nghệ	Vai trò
api	Java 17, Spring Boot	Cung cấp REST API, WebSocket và giao diện web qua cổng 8080 (HTTP), 8443 (HTTPS)
worker	Java 17	Biên dịch, thực thi bài nộp trong sandbox không có quyền truy cập mạng, có giới hạn thời gian và bộ nhớ
postgres	PostgreSQL 16	Lưu trữ dữ liệu và hàng đợi chấm


Mã nguồn bài nộp và các file test được lưu trong thư mục backend/data trên máy chủ. Chi tiết về cấu trúc backend, quy trình build Java và kiểm thử tương thích được trình bày trong [tài liệu chuyển đổi Java](backend/docs/JAVA-MIGRATION.md).
Mục lục
1. Yêu cầu
2. Cài đặt
   - Chạy thử nhanh với dữ liệu mẫu
3. Hướng dẫn cho giáo viên
4. Hướng dẫn cho sinh viên
5. Vận hành thường ngày
   - Kiểm thử tự động
   - Phát triển giao diện
6. Xử lý sự cố
7. Tài liệu tham khảo
8. Tác giả
1. Yêu cầu
Máy chủ triển khai (máy của giáo viên hoặc một máy tính trong phòng máy) cần sử dụng một trong các môi trường sau:
- Linux (Ubuntu 22.04/24.04 hoặc tương đương): Docker Engine + Docker Compose v2.
- Windows: Docker Desktop chạy Linux containers + WSL2 (Ubuntu), trên phiên bản Windows đáp ứng yêu cầu của Docker Desktop.
- macOS (Apple Silicon hoặc Intel): Docker Desktop.
Các công cụ dòng lệnh cần thiết gồm git, make, curl và openssl. python3 được sử dụng để tạo dữ liệu demo, chạy smoke test và sao lưu. Hướng dẫn cài đặt theo từng hệ điều hành nằm ở mục 2.1. Lần triển khai đầu tiên cần kết nối Internet để tải Docker image và thư viện. Sau khi build, giao diện và bộ chấm không phụ thuộc CDN hay dịch vụ chấm bên ngoài.
Nhu cầu	Công cụ thêm trên máy chủ
Chạy hệ thống, tạo dữ liệu mẫu, thử nộp C++ trên web	Không cần cài Java, Maven, Node.js hay g++ trên máy chủ; Docker đóng gói sẵn
Unit test, integration test, smoke test, wsprobe, loadtest	JDK 17, kiểm tra bằng java -version và javac -version; Maven Wrapper đã có sẵn
Sửa/build giao diện	Node.js 22+, npm
Kiểm thử giao diện tự động	Node.js 22+ và Google Chrome/Chromium cài trong cùng hệ điều hành với Node.js


Trên Windows, thực hiện các lệnh Bash, make, Java và Node.js trong Ubuntu WSL2; trình duyệt vẫn được mở trực tiếp trên Windows. Trên Linux và macOS, sử dụng terminal của hệ điều hành. Worker luôn chạy trong container Linux để bảo đảm môi trường sandbox nhất quán trên cả ba nền tảng.

Máy của giáo viên và sinh viên chỉ cần cùng mạng LAN với máy chủ và sử dụng phiên bản mới của Chrome, Edge hoặc Firefox, không giới hạn hệ điều hành.
2. Cài đặt
2.1. Chuẩn bị máy chủ
Chọn hướng dẫn tương ứng với hệ điều hành máy chủ, sau đó tiếp tục từ mục 2.2.
Linux (Ubuntu)
1. Cài đặt các công cụ dòng lệnh:
   sudo apt update
   sudo apt install -y git make curl openssl python3 ca-certificates
   Cài Docker Engine và Docker Compose plugin theo hướng dẫn dành cho Ubuntu. Có thể bỏ qua bước này nếu docker compose đã hoạt động. Với bản phân phối Linux khác, tham khảo tài liệu Docker tương ứng và cài các công cụ trên qua trình quản lý gói của hệ điều hành.
2. Thêm người dùng hiện tại vào nhóm docker để chạy Docker không cần sudo, sau đó đăng xuất và đăng nhập lại:
   sudo usermod -aG docker $USER
3. Kiểm tra:
   docker compose version
   docker info
4. Nếu sử dụng tường lửa ufw, cho phép kết nối từ các máy trong LAN qua cổng 8080 và 8443:
   sudo ufw allow 8080/tcp
   sudo ufw allow 8443/tcp
5. Xem IP LAN của máy chủ (dùng cho TLS_HOSTS ở mục 2.3):
   hostname -I
Từ mục 2.2 trở đi, thực hiện các lệnh trong terminal Linux.
Windows (Docker Desktop + WSL2)
1. Mở PowerShell với quyền quản trị viên để cài Ubuntu trên WSL. Khởi động lại Windows nếu được yêu cầu, sau đó mở Ubuntu từ menu Start và tạo tài khoản người dùng Linux:
   wsl --install -d Ubuntu
   Nếu WSL đã được cài đặt, chạy wsl -l -v để xác nhận Ubuntu đang ở VERSION 2. Nếu vẫn ở VERSION 1, chuyển bằng wsl --set-version Ubuntu 2. Có thể cập nhật WSL qua wsl --update.
2. Cài Docker Desktop, sử dụng backend WSL 2 và chế độ Linux containers. Trong Docker Desktop, mở Settings → Resources → WSL integration, bật tích hợp với Ubuntu và chọn Apply & restart. Không cần cài Docker Engine riêng bên trong Ubuntu WSL.
3. Trong terminal Ubuntu, cài các công cụ dòng lệnh:
   sudo apt update
   sudo apt install -y git make curl openssl python3 ca-certificates
4. Kiểm tra Docker từ Ubuntu:
   docker compose version
   docker info
5. Mở PowerShell với quyền quản trị viên và tạo quy tắc tường lửa cho kết nối từ LAN:
   New-NetFirewallRule -DisplayName "LAN Judge" -Direction Inbound -Protocol TCP -LocalPort 8080,8443 -Profile Private -RemoteAddress LocalSubnet -Action Allow
   Thiết lập mạng phòng máy ở chế độ Private trong Windows Settings để quy tắc có hiệu lực. Nếu thay đổi cổng trong .env, cập nhật quy tắc tường lửa tương ứng.
6. Xem IP LAN của máy Windows (dùng cho TLS_HOSTS ở mục 2.3; không dùng IP của WSL): chạy ipconfig trong PowerShell, lấy dòng IPv4 Address của card Wi-Fi/Ethernet.
Từ mục 2.2 trở đi, thực hiện các lệnh trong terminal Ubuntu. Nên clone mã nguồn vào thư mục home (~) của Ubuntu, không đặt trong C:\ hoặc /mnt/c/..., để bảo toàn quyền truy cập file Linux của thư mục data/.
macOS
1. Cài Docker Desktop cho macOS, chọn bản Apple Silicon hoặc Intel phù hợp với máy. Khởi chạy Docker Desktop và kiểm tra dịch vụ đã sẵn sàng.
2. Cài git và make qua Xcode Command Line Tools:
   xcode-select --install
3. Kiểm tra:
   docker compose version
   docker info
4. Nếu tường lửa đang được bật tại System Settings → Network → Firewall, chọn Allow khi macOS yêu cầu cấp quyền nhận kết nối cho Docker để các máy khác truy cập được cổng 8080/8443.
5. Xem IP LAN của máy Mac (dùng cho TLS_HOSTS ở mục 2.3):
   ipconfig getifaddr en0
   Nếu không nhận được địa chỉ IP, thử en1 hoặc kiểm tra trong System Settings → Network.
6. Kiểm tra python3 --version. Nếu chưa có, cài Python 3 bằng bộ cài cho macOS, mở lại Terminal rồi kiểm tra lại. curl và openssl có thể kiểm tra bằng curl --version và openssl version.
Từ mục 2.2 trở đi, sử dụng ứng dụng Terminal trên macOS.
2.2. Lấy mã nguồn
cd ~
git -c core.autocrlf=false clone https://github.com/hoangdaulan/lan-lab.git
cd lan-lab/backend
Các lệnh make và docker compose ở những mục tiếp theo được thực hiện trong thư mục backend/.

Nếu đã có repository, vào thư mục dự án, chạy git pull, sau đó dùng cd backend. Khi cập nhật, giữ nguyên .env và dữ liệu hiện có; không sao chép đè .env.example lên .env.
2.3. Tạo file .env
cp .env.example .env
Mở .env bằng trình soạn thảo, chẳng hạn nano .env, và cấu hình tối thiểu các biến dưới đây. File .env đã được khai báo trong .gitignore và không được đưa lên Git:
Biến	Ý nghĩa	Gợi ý
POSTGRES_PASSWORD	Mật khẩu PostgreSQL	Chuỗi ngẫu nhiên, tạo bằng openssl rand -hex 16
JWT_SECRET	Khóa ký token đăng nhập	openssl rand -hex 32
TLS_HOSTS	IP LAN của máy chủ, ghi vào chứng chỉ HTTPS	IP đã xem ở mục 2.1, ví dụ 192.168.1.9
APP_UID, APP_GID	Chủ sở hữu thư mục data/	Kết quả của id -u và id -g (Linux/WSL thường là 1000, macOS thường là 501 và 20)
DISPATCH_TOKEN	Khóa chung giữa api và worker	openssl rand -hex 16
WORKER_CONCURRENCY	Số bài chấm song song	Mặc định 2; tăng nếu máy nhiều nhân


Giữ nguyên các giá trị API_PORT=8080, API_HTTPS_PORT=8443 và DB_PORT=5433 nếu không xảy ra xung đột cổng.

JWT_SECRET phải dài ít nhất 32 byte; giá trị mặc định change-me trong file mẫu không đáp ứng yêu cầu khởi động API. Nên sử dụng chuỗi hex cho các khóa và mật khẩu cơ sở dữ liệu để có thể đưa trực tiếp vào URL kết nối. Nếu chỉ chạy thử trên máy chủ, có thể để trống TLS_HOSTS và truy cập https://localhost:8443/.
Lưu ý về quyền file: Đặt APP_UID và APP_GID theo tài khoản đang sử dụng để chỉnh sửa file test mà không cần sudo. Nếu giữ giá trị 10001, cần cấp quyền cho thư mục dữ liệu bằng lệnh sudo chown -R 10001:10001 data.

2.4. Khởi động, tạo bảng và tài khoản giáo viên đầu tiên
make up
Lệnh trên tạo thư mục data/, build Docker image và khởi chạy ba dịch vụ postgres, api, worker ở chế độ nền. Ở lần đầu tiên, quá trình này còn tải image và cài g++.
make migrate
Lệnh make migrate tạo các bảng cơ sở dữ liệu và có thể chạy lại sau mỗi lần cập nhật mã nguồn.
SEED_TEACHER_CODE=GV01 SEED_TEACHER_FULLNAME="Nguyễn Văn A" SEED_TEACHER_PASSWORD='<mật-khẩu-của-bạn>' make seed
Lệnh make seed tạo tài khoản giáo viên đầu tiên. Thay GV01, họ tên và mật khẩu bằng thông tin thực tế. Truyền mật khẩu qua biến môi trường của lệnh như ví dụ; không lưu mật khẩu giáo viên trong .env.

Kiểm tra các container đang chạy:
docker compose ps
Kết quả cần có đủ ba dịch vụ postgres, api và worker ở trạng thái Up/running; riêng postgres phải ở trạng thái healthy. API có thể khởi động chậm hơn container một vài giây. Kiểm tra kết nối giữa API và cơ sở dữ liệu:
curl --fail --show-error http://localhost:8080/healthz
API hoạt động bình thường sẽ trả về HTTP 200 cùng JSON {"status":"ok","db":"up"} (không phụ thuộc thứ tự các trường). Nếu chưa kết nối được, thử lại sau vài giây; nếu lỗi tiếp diễn, kiểm tra log:
docker compose logs -f api
Nhấn Ctrl+C để ngừng theo dõi log mà không dừng container. Health check chỉ xác nhận API và cơ sở dữ liệu; cần thực hiện bài nộp mẫu ở mục 2.6 để kiểm tra worker.
2.5. Mở giao diện web
- Trên trình duyệt: https://<IP-máy-chủ>:8443/ (ví dụ https://192.168.1.9:8443/). Trên chính máy chủ có thể dùng https://localhost:8443/.
- Chứng chỉ là tự ký nên trình duyệt sẽ cảnh báo lần đầu: chọn Nâng cao → Tiếp tục truy cập. Mỗi máy chỉ cần làm một lần.
- http://<IP>:8080 sẽ tự chuyển sang HTTPS. Phải dùng HTTPS vì trình duyệt chỉ cho chia sẻ màn hình trên kết nối bảo mật.
- Swagger UI (thử API): http://<IP>:8080/swagger/index.html.
Chứng chỉ TLS được tạo trong data/tls khi dịch vụ api khởi động. Nếu địa chỉ IP của máy chủ thay đổi, cập nhật TLS_HOSTS trong .env rồi chạy lại make up.
2.6. Chạy thử nhanh với dữ liệu mẫu
Sau khi hoàn thành mục 2.4, chạy lệnh sau trong backend/ bằng tài khoản giáo viên vừa tạo. Bước này cần curl và python3, không yêu cầu JDK hay Node.js:
TEACHER_CODE=GV01 TEACHER_PASSWORD='<mật-khẩu-đã-tạo>' bash scripts/demo-setup.sh
Script tạo một đề thi, một phiên thi mở trong 2 giờ, bài A — Tổng hai số gồm 15 test, bài B — Tổng 1..n gồm 10 test và năm tài khoản sinh viên từ DEMO01 đến DEMO05. Sau khi chạy, terminal hiển thị exam_id, session_id, problem_A và problem_B. Mỗi lần chạy sẽ tạo đề và phiên mới nhưng vẫn giữ dữ liệu cũ; tài khoản sinh viên đã tồn tại không được tạo lại. Nếu mật khẩu của các tài khoản demo đã thay đổi, giáo viên cần đặt lại về ngày sinh trước khi thử.
1. Mở https://localhost:8443/, đăng nhập giáo viên, vào đề Demo Lap trinh mang vừa tạo → Ca demo → Bảng trực tiếp.
2. Mở cửa sổ ẩn danh hoặc trình duyệt khác, đăng nhập DEMO01 / 01012004, chọn phiên Ca demo vừa tạo. Nếu thử từ máy khác, dùng https://<IP-LAN>:8443/.
3. Chấp nhận cảnh báo chứng chỉ, bấm Chia sẻ toàn màn hình, chọn toàn bộ màn hình. Trên Mac, nếu được hỏi quyền, cho phép trình duyệt trong System Settings → Privacy & Security → Screen & System Audio Recording (hoặc Screen Recording tùy phiên bản), rồi mở lại trình duyệt nếu hệ điều hành yêu cầu.
4. Chọn bài A, dán code C++17 sau và bấm Nộp bài:
   #include <iostream>
   using namespace std;
   int main() {
       long long a, b;
       if (cin >> a >> b) cout << a + b << '\n';
       return 0;
   }
5. Kiểm tra kết quả: bài nộp chuyển từ trạng thái chờ/chấm sang 15/15 AC; bảng giáo viên tự cập nhật mà không cần tải lại trang. Sau đó sửa a + b thành a - b và nộp lại để kiểm tra kết quả 0/15 WA. Có thể kiểm tra thêm chức năng Xem màn hình tại tab Màn hình SV và thử gửi thông báo tới phiên thi.
Hoàn thành các bước trên đồng nghĩa với việc đã kiểm tra luồng đăng nhập, tạo dữ liệu, tải mã nguồn lên, thực thi C++ trong sandbox và cập nhật kết quả trực tiếp. Nếu bài nộp vẫn ở trạng thái chờ, kiểm tra docker compose logs --tail=100 worker. Các tình huống mất kết nối và dừng worker được mô tả trong [kịch bản demo](backend/docs/DEMO.md).
3. Hướng dẫn cho giáo viên
3.1. Đăng nhập
1. Mở https://<IP>:8443/.
2. Nhập mã tài khoản (ví dụ GV01 đã tạo bằng make seed) và mật khẩu.
3. Thanh điều hướng có ba mục: Đề thi, Sinh viên, Hàng đợi chấm. Đổi mật khẩu ở menu tài khoản góc trên (mục Đổi mật khẩu).
3.2. Thêm sinh viên
Vào Sinh viên.

Thêm thủ công: nhập MSV, Họ tên và Ngày sinh theo định dạng dd/mm/yyyy, sau đó chọn Thêm.

Nhập bằng CSV: chọn tệp và bấm Nhập CSV. Tệp phải dùng mã hóa UTF-8 với dòng tiêu đề đúng như sau:
full_name,code,date_of_birth
Nguyễn Văn An,SV001,05/09/2004
Trần Thị Bình,SV002,12/01/2004
- Ngày sinh nên viết dd/mm/yyyy; hệ thống cũng nhận d/m/yyyy và yyyy-mm-dd.
- Nếu có dòng lỗi (kể cả thiếu ngày sinh) thì không dòng nào được lưu, và trang sẽ liệt kê dòng lỗi để sửa.
- MSV đã tồn tại sẽ bị bỏ qua (không ghi đè).
- File mẫu: backend/examples/students-import.csv.
Mật khẩu khởi tạo của sinh viên là ngày sinh theo định dạng ddmmyyyy (ví dụ 05/09/2004 → 05092004). Khi cần đặt lại, giáo viên tìm sinh viên theo MSV hoặc họ tên rồi chọn Đặt lại mật khẩu để khôi phục mật khẩu mặc định.
3.3. Tạo đề thi và bài tập
1. Vào Đề thi, nhập Tên đề, Mô tả, bấm Tạo đề thi. Trang chi tiết đề sẽ mở ra.
2. Ở mục Bài tập, bấm + Thêm bài và điền:
   - Mã bài (ví dụ A), Tên bài, Đề bài (văn bản).
   - Giới hạn thời gian (ms) (mặc định 1000) và Giới hạn bộ nhớ (MB) (mặc định 256).
3. Sau khi lưu, cột Thư mục test trên máy chủ hiện đường dẫn dạng ./data/problems/<id>/tests.
3.4. Thêm file test
Mỗi test gồm một cặp tệp N.in và N.out, được lưu trực tiếp trong thư mục bài tập trên máy chủ (đường dẫn tính từ backend/):
backend/data/problems/<id>/tests/
├── 1.in
├── 1.out
├── 2.in
├── 2.out
└── ...
Ví dụ sao chép bộ test trên máy chủ:
cp ~/bo-test-bai-A/*.in ~/bo-test-bai-A/*.out data/problems/1/tests/
Trên Windows có thể mở thư mục này bằng File Explorer tại \\wsl$\Ubuntu\home\<user-ubuntu>\lan-lab\backend\data rồi kéo thả file vào; trên macOS mở bằng open data/problems/1/tests trong Terminal.

Quy tắc:
- Mỗi N.in phải có N.out tương ứng và ngược lại; không dùng thư mục con hay symlink.
- Kết quả so khớp theo từng token (bỏ qua khác biệt khoảng trắng/xuống dòng).
- Chép xong, quay lại trang đề, bấm Quét test & chấm lại ở dòng của bài. Cột Test sẽ hiện số test đang dùng; nút Test cho xem danh sách.
Nếu thư mục sai quy tắc, hệ thống báo lỗi và không thay đổi gì.
Sau khi quét, bộ chấm dùng bản sao cố định của các file test. Sửa file gốc chưa làm thay đổi kết quả chấm cho tới khi bấm Quét test & chấm lại; hệ thống phát hiện cả thay đổi nội dung giữ nguyên tên file. Các bản sao nằm trong `data/problems/<id>/testsets/`, không chỉnh sửa trực tiếp và cần sao lưu cùng `data/`. Khi nâng cấp dữ liệu từ phiên bản chưa có bản sao, job đầu tiên sẽ tạo bản sao, tăng version và chấm lại các bài cũ của bài tập đó.
3.5. Tạo phiên thi
1. Trong trang đề, mục Phiên thi, bấm + Thêm phiên.
2. Đặt Tên phiên (ví dụ Ca 1), giờ Bắt đầu và Kết thúc. Máy chủ dùng giờ của nó để mở/đóng nộp bài, nên hãy đảm bảo giờ máy chủ đúng.
3. Bấm Mở bảng để vào trang điều hành phiên.
Sinh viên chỉ vào làm bài được trong khung giờ của phiên.
3.6. Điều hành phiên thi
Trang điều hành phiên gồm năm tab và nút Xuất CSV để tải bảng điểm có thể mở bằng Excel.

Bảng trực tiếp: mỗi hàng tương ứng một sinh viên, mỗi cột tương ứng một bài tập. Ô kết quả hiển thị AC (số lần nộp), đang chấm… hoặc kết quả gần nhất như 9/10 TLE; cột cuối thống kê số bài AC. Bảng được cập nhật tự động qua WebSocket. Chấm xanh bên cạnh MSV cho biết sinh viên đang mở trang. Chọn một ô để xem các lần nộp của sinh viên đó đối với bài tương ứng.

Màn hình SV: xem trực tiếp màn hình sinh viên.
1. Chọn kích thước lưới 1 / 4 / 9 / 15 ô. Lưới càng lớn thì mỗi màn hình càng ít khung hình để máy giáo viên không bị quá tải:
   Lưới	Khung/giây mỗi màn hình
   1	15
   4	10
   9	5
   15	3
2. Bấm Xem màn hình ở dòng sinh viên đang chia sẻ để đưa vào lưới (hoặc Xem tất cả để mở các màn hình đang chia sẻ cho tới khi đầy lưới; Đóng tất cả để xóa lưới). Lưới đầy thì phải đóng bớt ô hoặc chọn lưới lớn hơn.
3. Nhấp đúp một ô để phóng to (15 khung/giây). Bấm Đóng để bỏ một ô.
Luồng video được truyền trực tiếp từ máy sinh viên đến máy giáo viên trong mạng LAN qua WebRTC; máy chủ chỉ xử lý tín hiệu thiết lập kết nối. Khi sinh viên dừng chia sẻ màn hình, trang làm bài bị khóa và giáo viên nhìn thấy trạng thái "Chưa/đã tắt chia sẻ".

Bài nộp: liệt kê toàn bộ bài nộp, hỗ trợ lọc theo sinh viên và bài tập. Chọn một dòng để xem mã nguồn, thông báo biên dịch và kết quả từng test, bao gồm thời gian và bộ nhớ. Nút Chấm lại bài này chỉ chấm lại bài nộp đang chọn.

Người tham gia: hiển thị người đang làm bài, người đã kết thúc, thời điểm vào thi và thời điểm kết thúc.

Thông báo: nhập nội dung, chẳng hạn "Còn 10 phút", rồi chọn Gửi tới cả phiên. Thông báo được hiển thị ngay cho sinh viên đang mở trang. Dữ liệu thông báo chỉ nằm trong bộ nhớ của dịch vụ api và sẽ mất khi dịch vụ khởi động lại.
3.7. Chấm lại
- Sửa test: sửa/thêm/xóa file trong data/problems/<id>/tests/, rồi bấm Quét test & chấm lại. Bài tăng version và mọi bài đã nộp của bài đó được chấm lại theo test mới.
- Đổi giới hạn thời gian/bộ nhớ: bấm Sửa ở dòng bài, lưu lại; hệ thống tự chấm lại mọi bài đã nộp của bài đó.
- Một bài nộp: mở chi tiết bài nộp, bấm Chấm lại bài này.
Có thể chấm lại cả khi phiên đang diễn ra; bảng trực tiếp tự cập nhật khi có kết quả mới.
3.8. Hàng đợi chấm
Trang Hàng đợi chấm cung cấp số lượng bài đang chờ, đang chấm, đã hoàn tất; worker đang xử lý bài; bài có thời gian chờ lâu nhất; cùng thời gian chờ và chấm trung bình trong 10 phút gần nhất. Dữ liệu được làm mới mỗi 3 giây.
3.9. Xóa
- Xóa bài: chỉ được khi bài chưa có bài nộp.
- Xóa phiên: chỉ được khi phiên chưa có bài nộp.
- Xóa đề: chỉ được khi đề không còn bài và phiên.
4. Hướng dẫn cho sinh viên
1. Mở trang: https://<IP-máy-chủ>:8443/ (giáo viên sẽ cho địa chỉ). Lần đầu chấp nhận cảnh báo chứng chỉ.
2. Đăng nhập: mã tài khoản là MSV, mật khẩu ban đầu là ngày sinh dạng ddmmyyyy (sinh ngày 05/09/2004 thì mật khẩu là 05092004). Nên đổi mật khẩu ở menu tài khoản → Đổi mật khẩu.
3. Chọn phiên thi: trang Phiên thi liệt kê phiên Đang diễn ra / Sắp diễn ra / Đã kết thúc. Bấm Vào làm bài ở phiên đang diễn ra (lần sau là Tiếp tục làm bài).
4. Chia sẻ màn hình (bắt buộc): bấm Chia sẻ toàn màn hình, chọn Toàn bộ màn hình (Entire screen, không chọn cửa sổ hay tab) rồi bấm Chia sẻ. Nếu dừng chia sẻ, trang làm bài bị khóa cho tới khi chia sẻ lại.
   Nút nộp chỉ mở sau khi máy chủ xác nhận kết nối chia sẻ; mất kết nối hoặc tắt chia sẻ sẽ chặn nộp bài. Gia hạn đăng nhập tự động giữ nguyên video đang xem.
5. Làm bài:
   - Chọn bài ở thanh bên hoặc dùng Bài trước / Bài tiếp theo. Đề bài hiện bên trái.
   - Viết code C++17 trong ô soạn thảo (bản nháp tự lưu riêng theo tài khoản, phiên và bài trên trình duyệt), hoặc bấm Tải lên .cpp để chọn file. Bản nháp cũ chưa có thông tin tài khoản không được tự khôi phục. Mã nguồn tối đa 64 KB, chỉ một file .cpp.
   - Bấm Nộp bài hoặc nhấn Ctrl + Enter (⌘ + Enter trên Mac).
6. Xem kết quả: kết quả hiện ngay khi chấm xong, không cần tải lại trang: Đang chờ → Đang chấm → ví dụ 15/15 AC. Ý nghĩa:
   - AC: đúng tất cả test. WA: sai đáp án. TLE: quá thời gian. MLE: quá bộ nhớ. RE: lỗi khi chạy. CE: lỗi biên dịch (có thông báo của trình biên dịch).
   - Hệ thống dừng ở test sai đầu tiên, nên 9/10 TLE nghĩa là qua 9 test rồi test thứ 10 bị quá giờ.
   - Có thể nộp nhiều lần; danh sách bài nộp của bài nằm ở vùng kết quả, bấm Xem mã nguồn để xem lại.
7. Thông báo: thông báo của giáo viên hiện trên đầu trang làm bài; bấm Tất cả thông báo để xem lại.
8. Kết thúc: bấm Kết thúc khi làm xong. Sau khi kết thúc không nộp thêm được nữa. Hết giờ phiên thì hệ thống cũng ngừng nhận bài.
Danh sách phím tắt và hướng dẫn sử dụng nằm trong mục Hướng dẫn và phím tắt (biểu tượng dấu hỏi) trên thanh công cụ phía trên.
5. Vận hành thường ngày
5.1. Khởi động, cập nhật và sao lưu
Chạy trong backend/:
Việc	Lệnh
Khởi động / cập nhật sau khi git pull	make up rồi make migrate
Dừng hệ thống (dữ liệu vẫn còn)	make down
Xem log	docker compose logs -f api worker
Sao lưu DB và data/ vào backups/<thời-điểm>/	make backup
Kiểm tra bản sao lưu (không đụng dữ liệu thật)	scripts/restore.sh backups/<thời-điểm> --verify
Khôi phục bản sao lưu (ghi đè DB và data/)	scripts/restore.sh backups/<thời-điểm> --yes


Nên thực hiện make backup trước khi cập nhật hệ thống đang có dữ liệu cần lưu giữ. Sau make up và make migrate, kiểm tra /healthz theo mục 2.4. Nếu có thay đổi ở giao diện, tải lại trang để trình duyệt nhận bản build mới.
5.2. Kiểm thử tự động
Chạy các lệnh make trong backend/. Trên Windows, cài công cụ và chạy trong Ubuntu WSL2 như phần cài đặt.
Bộ kiểm thử	Lệnh	Điều kiện	Dấu hiệu thành công
Java: hợp đồng dữ liệu, TCP/UDP	make test	JDK 17; cho phép mở socket loopback	Lệnh thoát mã 0; không có Failures/`Errors`
Nghiệp vụ và giao dịch PostgreSQL	make test-integration	JDK 17, .env, container postgres đang chạy	DatabaseTest chạy và không lỗi
Biên dịch/chạy C++ và cách ly sandbox	make test-sandbox	Docker đang chạy; không cần JDK/g++ trên host	SandboxTest chạy trong container Linux và không lỗi
REST, quyền truy cập, nộp/chấm/chấm lại, WebSocket	Lệnh smoke bên dưới	JDK 17, curl, python3, toàn bộ stack đã migrate/seed	Dòng cuối … passed, 0 failed


make test mặc định không chạy kiểm thử cơ sở dữ liệu và sandbox; phải chạy thêm hai lệnh tương ứng trong bảng để kiểm tra đầy đủ. Integration test sử dụng cấu hình trong .env, tạo rồi xóa các cơ sở dữ liệu thử nghiệm riêng. Tài khoản PostgreSQL cần quyền CREATEDB; tài khoản do Docker Compose khởi tạo đã có quyền này.

Ví dụ cài JDK trong Ubuntu/Linux hoặc Ubuntu WSL2:
sudo apt install -y openjdk-17-jdk
java -version
javac -version
Trên Mac, cài JDK 17 cho macOS aarch64/Apple Silicon hoặc x64/Intel từ Eclipse Temurin, rồi kiểm tra java -version, javac -version trong Terminal. Maven Wrapper tải Maven và thư viện ở lần chạy đầu.

Chạy smoke test với giáo viên đã tạo bằng make seed:
SMOKE_TEACHER_CODE=GV01 SMOKE_TEACHER_PASSWORD='<mật-khẩu-đã-tạo>' make smoke
Smoke test tạo các tài khoản, đề thi, phiên thi và bài nộp mẫu; dữ liệu thử nghiệm được giữ lại để phục vụ kiểm tra. Nên thực hiện trên môi trường riêng. Có thể truyền thêm SMOKE_TEACHER2_CODE và SMOKE_TEACHER2_PASSWORD của giáo viên thứ hai để kiểm tra quyền sở hữu đề thi. Các script cũng đọc .smoke.env nếu tệp này tồn tại; không đưa tệp vào Git. Để chỉ kiểm tra xác thực: SMOKE_TEACHER_CODE=GV01 SMOKE_TEACHER_PASSWORD='<mật-khẩu>' bash scripts/smoke.sh auth.

Nếu đổi cổng HTTP, thêm BASE=http://localhost:<API_PORT> khi chạy demo hoặc smoke test. Nếu đổi cổng DB, cập nhật DB_PORT trong .env trước integration test.
5.3. Phát triển giao diện
Cài Node.js 22+ và npm trong môi trường đang dùng (Windows: Ubuntu WSL2). Chạy tại gốc repo, thư mục chứa package.json:
npm ci
npm run dev
Truy cập http://127.0.0.1:5173/. Development server theo dõi mã React trong web/src và chuyển tiếp REST/WebSocket tới API tại http://127.0.0.1:8080; do đó cần hệ thống backend đang chạy và tài khoản hợp lệ. Sau khi chỉnh sửa mã nguồn, tải lại trang để kiểm tra. Nhấn Ctrl+C để dừng server. Có thể cấu hình lại địa chỉ API và cổng giao diện:
BACKEND_URL=http://127.0.0.1:8080 PORT=5173 npm run dev
Để xem giao diện với dữ liệu giả lập, chạy npm run preview:ui thay cho development server và đăng nhập bằng SV001 / demo hoặc GV001 / demo. Chế độ preview không kết nối cơ sở dữ liệu và không thực thi bài C++ thực tế.

Build để cập nhật giao diện trong Docker:
npm run build
cd backend
docker compose up -d --build --no-deps api
Kiểm thử giao diện tự động tại gốc repo, sau npm ci, với Google Chrome/Chromium:
npm run test:auth
npm run test:ui
Script tự tìm Chrome ở đường dẫn thông dụng trên macOS/Linux. Trong WSL2, cần cài Chrome bản Linux trong Ubuntu để chạy headless, theo hướng dẫn của Microsoft; Chrome đang cài trên Windows dùng cho thao tác web thủ công. Nếu dùng đường dẫn khác, truyền CHROME là file thực thi, ví dụ trên Mac:
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" npm run test:ui
Kiểm thử thành công sẽ xuất hiện thông báo … browser checks passed. Bộ test sử dụng dữ liệu giả lập, không kiểm tra worker C++ thực tế. Ảnh chụp trong quá trình kiểm thử được lưu ở artifacts/ui/ và có thể xóa sau khi xem.

Để kiểm tra video WebRTC với stack thật, chạy trong backend/ (cần Node.js 22+, Chrome và tài khoản giáo viên):
TEACHER_CODE=GV01 TEACHER_PASSWORD='<mật-khẩu>' node scripts/ui-e2e/screen-share.mjs
Kiểm thử thành công hiển thị Screen sharing OK. Nếu thay đổi cổng, bổ sung BASE=http://localhost:<API_PORT> và UI=https://localhost:<API_HTTPS_PORT>.
6. Xử lý sự cố
- Không kết nối được Docker / Cannot connect to the Docker daemon: mở Docker Desktop và chờ sẵn sàng; Windows kiểm tra WSL integration cho đúng Ubuntu. Linux kiểm tra Docker Engine đang chạy và đăng nhập lại sau khi thêm user vào nhóm docker. Dùng docker info để xác nhận trước make up.
- Cổng đã được sử dụng / address already in use: đổi API_PORT, API_HTTPS_PORT hoặc DB_PORT trong .env, rồi make up; dùng cổng mới trong URL, firewall và biến BASE/`UI` của script test.
- Máy sinh viên không mở được trang: kiểm tra cùng mạng (ping <IP>), đã mở cổng 8080/8443 trên tường lửa (mục 2.1), và docker compose ps thấy api đang chạy. Windows dùng IP của Windows, không phải WSL; kiểm tra mạng đang là Private. Mạng Wi-Fi có bật client/AP isolation có thể chặn các máy nhìn thấy nhau.
- Không chia sẻ màn hình được / báo "Trình duyệt không chia sẻ màn hình được": mở bằng https://<IP>:8443/ trên Chrome/Edge/Firefox mới, chọn toàn màn hình. macOS cần cấp quyền Screen Recording cho trình duyệt như mục 2.6. Nếu xem màn hình bị đen, kiểm tra hai máy cùng LAN và không bị tường lửa chặn kết nối WebRTC ngang hàng.
- Trình duyệt báo chứng chỉ không khớp: TLS_HOSTS chưa có IP hiện tại của máy chủ. Sửa .env rồi make up.
- Bài nộp đứng ở "Đang chờ": xem docker compose logs worker và trang Hàng đợi chấm. Bài không bị mất khi worker khởi động lại; nó sẽ được chấm lại.
- Bài báo "Lỗi hệ thống" hoặc test không quét được: kiểm tra quyền thư mục data/ khớp với APP_UID/`APP_GID` trong .env, và các file N.in/`N.out` đủ cặp.
- Lỗi set POSTGRES_USER in .env khi chạy make: chưa tạo .env hoặc đang đứng sai thư mục; chạy lệnh trong backend/.
- API dừng ngay vì JWT_SECRET: thay change-me bằng khóa ít nhất 32 byte (openssl rand -hex 32), rồi make up.
- Lỗi mật khẩu PostgreSQL sau khi sửa .env: mật khẩu khởi tạo chỉ được áp dụng khi volume DB còn trống. Với dữ liệu đã có, dùng lại thông tin DB cũ hoặc đổi mật khẩu trong PostgreSQL rồi đồng bộ .env.
- bad interpreter, bash\r hoặc không chạy được .sh trên Windows: dùng Ubuntu WSL2 và clone vào ~ như mục 2.2; giữ file shell ở kiểu xuống dòng LF. Repo có .gitattributes để giữ LF khi checkout.
- Test UI không tìm thấy Chrome: kiểm tra Node.js 22+, cài Chrome trong cùng hệ điều hành với Node.js hoặc truyền biến CHROME như mục 5.3. Máy chỉ cần dùng ứng dụng web thì không cần Node.js/Chrome headless.
7. Tài liệu tham khảo
- [Thiết kế backend](backend/BACKEND_CONTEXT.md).
- [REST và WebSocket](backend/docs/api-v1.md), Swagger UI tại /swagger/index.html.
- [Các giao thức mạng viết tay](backend/docs/net-protocols.md): TCP dispatch giữa api và worker; UDP discovery/multicast là tùy chọn, không cần bật để dùng web trong LAN.
- [Kịch bản demo](backend/docs/DEMO.md).
- [Ví dụ gọi API](backend/examples/api-manual.http).
- [Backend Java và kiểm thử](backend/docs/JAVA-MIGRATION.md).
8. Tác giả
- Nguyễn Sỹ Hoàng — GitHub: hoangdaulan
- Mai Anh Đức — GitHub: JoeyDuc
