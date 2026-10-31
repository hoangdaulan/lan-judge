#!/usr/bin/env python3
"""Summarize docs/results/runs.jsonl (scripts/experiment.sh) into
docs/results/RESULTS.md: median over repetitions for each label."""
import json
import statistics
import sys
from collections import defaultdict

src = sys.argv[1] if len(sys.argv) > 1 else "docs/results/runs.jsonl"
dst = sys.argv[2] if len(sys.argv) > 2 else "docs/results/RESULTS.md"
runs = defaultdict(list)
for line in open(src):
    if line.strip():
        r = json.loads(line)
        runs[r["label"]].append(r)


def med(rs, f):
    vals = [f(r) for r in rs]
    vals = [v for v in vals if v is not None]
    return statistics.median(vals) if vals else 0


def peak(r, prefix, key):
    vals = [v[key] for n, v in r.get("resources", {}).items() if n.startswith(prefix)]
    return sum(vals) if key == "cpu_peak_pct" else max(vals or [0])


def ok(rs):
    bad = sum(n for r in rs for c, n in r["http_status"].items() if not c.startswith("2"))
    verdicts = defaultdict(int)
    for r in rs:
        for v, n in r["verdicts"].items():
            verdicts[v] += n
    return bad, dict(verdicts)


out = []
w = out.append
first = next(iter(runs.values()))[0]
w("# Kết quả thực nghiệm (phase 14)\n")
w(f"Sinh tự động bởi `scripts/results.py` từ `{src}`. Mỗi dòng là **trung vị** của các lần lặp (cột n).")
w(f"Mỗi lần: {first['students']} SV đăng nhập + mở kết nối, {first['submissions']} bài nộp gửi **cùng lúc**, {first['tests']} test/bài, chương trình C++ a+b (AC).\n")
w("Thời gian tính bằng ms. `e2e` = từ lúc SV gửi bài tới lúc client biết kết quả; `chờ hàng đợi`/`chấm` đo ở server (DB); `makespan` = bài đầu gửi → kết quả cuối; `thông lượng` = bài/giây.\n")


def table(title, labels, cols):
    labels = [l for l in labels if l in runs]
    if not labels:
        return
    w(f"## {title}\n")
    w("| cấu hình | n | " + " | ".join(c[0] for c in cols) + " | lỗi HTTP | verdict |")
    w("|---|---:|" + "---:|" * len(cols) + "---:|---|")
    for l in labels:
        rs = runs[l]
        bad, verdicts = ok(rs)
        cells = []
        for _, f, fmt in cols:
            cells.append(fmt.format(med(rs, f)))
        w(f"| `{l}` | {len(rs)} | " + " | ".join(cells) + f" | {bad} | {verdicts} |")
    w("")


e2e = [("e2e p50", lambda r: r["submit_to_result"]["p50_ms"], "{:.0f}"),
       ("e2e p95", lambda r: r["submit_to_result"]["p95_ms"], "{:.0f}")]
server = [("chờ hàng đợi p50", lambda r: (r.get("server_queue_wait") or {}).get("p50_ms"), "{:.0f}"),
          ("chấm p50", lambda r: (r.get("server_judge") or {}).get("p50_ms"), "{:.0f}")]
thr = [("makespan", lambda r: r["makespan_ms"], "{:.0f}"),
       ("thông lượng", lambda r: r["throughput_per_s"], "{:.1f}")]
res = [("CPU đỉnh worker %", lambda r: peak(r, "worker", "cpu_peak_pct"), "{:.0f}"),
       ("RAM đỉnh worker MiB", lambda r: peak(r, "worker", "mem_peak_mib"), "{:.0f}"),
       ("CPU đỉnh API %", lambda r: peak(r, "api", "cpu_peak_pct"), "{:.0f}"),
       ("RAM đỉnh API MiB", lambda r: peak(r, "api", "mem_peak_mib"), "{:.0f}")]
net = [("request sau khi nộp", lambda r: r["wait_phase_requests"], "{:.0f}"),
       ("byte sau khi nộp", lambda r: r["wait_phase_bytes"], "{:.0f}"),
       ("message WS", lambda r: r["ws_messages"], "{:.0f}"),
       ("transaction DB", lambda r: r.get("db_xact_during_wait"), "{:.0f}"),
       ("tuple DB đọc", lambda r: r.get("db_tuples_returned_during_wait"), "{:.0f}")]
login = [("đăng nhập p50", lambda r: r["login"]["p50_ms"], "{:.0f}"),
         ("đăng nhập p95", lambda r: r["login"]["p95_ms"], "{:.0f}"),
         ("nộp 202 p50", lambda r: r["submit_202"]["p50_ms"], "{:.0f}"),
         ("nộp 202 p95", lambda r: r["submit_202"]["p95_ms"], "{:.0f}")]

table("E1. Số worker (slot chấm song song)", ["workers-c1", "workers-c2", "workers-c4", "workers-c8", "workers-2x4"],
      e2e + server + thr + res)
table("E2. Cách worker nhận job: polling 1 s vs NOTIFY", ["pickup-poll1s", "pickup-notify"], e2e + server + thr)
table("E3. Cách client nhận kết quả: WebSocket vs polling", ["delivery-ws", "delivery-poll1s", "delivery-poll250ms"], e2e + net)
table("Đăng nhập và nhận bài khi cả lớp thao tác cùng lúc", [l for l in runs if l.startswith(("workers", "delivery"))], login)
table("E4. Khởi động lại toàn bộ stack khi đang chấm", ["restart-midway"], e2e + thr)

w("""## Môi trường và giới hạn của phép đo

- Một máy: Intel i5-12400F (6 nhân/12 luồng), 15 GiB RAM, Ubuntu 24.04, Docker 29.8; PostgreSQL 16, API, worker chạy bằng Docker Compose. **Không phải** laptop i3 dự kiến dùng khi thi — cần đo lại trên máy thật.
- Client giả lập (`bin/loadtest`) chạy **trên cùng máy**, nối qua loopback: độ trễ mạng LAN thật (Wi-Fi/switch) **không** có trong số liệu.
- Bài nộp là chương trình a+b nhỏ: thời gian chấm chủ yếu là biên dịch `-static`. Bài nặng hơn (TLE, nhiều test lớn) sẽ làm hàng đợi dài hơn.
- CPU/RAM lấy mẫu bằng `docker stats` (~1 mẫu/giây); lần chạy ngắn hơn ~1,5 s có thể không bắt được đỉnh (ví dụ dòng `workers-c8` ghi 0). CPU % tính theo 1 luồng = 100 %.
- Byte = thân request/response + ước lượng 400 byte header mỗi lượt HTTP; với WebSocket = độ dài message + 4 byte header frame. Byte và request tính cho phần **sau khi nộp** (gồm cả 50 request nộp bài).

## Đọc kết quả

- **E1 – thêm slot chấm:** thông lượng tăng gần tuyến tính 1 → 2 → 4 slot (11 → 21,5 → 35 bài/s), sau đó bão hòa ở 8 slot (~42 bài/s) do CPU: thời gian chấm mỗi bài tăng 85 → 164 ms vì các bài tranh CPU. 2 container × 4 slot cho kết quả tương đương 1 container × 8 slot → giới hạn là CPU của máy, không phải cách chia container.
- **E2 – worker được báo bằng NOTIFY:** so với worker chỉ polling 1 s, thời gian chờ hàng đợi p50 giảm ~37 % (1203 → 764 ms), e2e p50 giảm ~32 % (1362 → 923 ms), thông lượng tăng ~26 %.
- **E3 – WebSocket vs polling:** WebSocket cần **ít request hơn 2,4–4,7 lần** (50 so với 118/237) và **ít transaction DB hơn ~2,6 lần** (250 so với 626–689), e2e p50 thấp hơn ~16 %. Byte thì **không ít hơn** polling 1 s trong thí nghiệm này: WebSocket đẩy 3 trạng thái/bài (queued, judging, completed) còn bài a+b chấm xong chỉ sau 1–2 lượt poll. Khi bài chấm lâu hơn (hàng đợi dài), số lượt poll tăng theo thời gian chờ còn số message WebSocket giữ nguyên.
- **Đăng nhập cả lớp cùng lúc:** p95 < 300 ms; CPU của API lên ~340 % do bcrypt (cost 10) cho 50 lần đăng nhập đồng thời. Nộp bài (lưu file + transaction) p95 < 100 ms. Không có lỗi HTTP nào trong 31 lần chạy.
- **E4 – khởi động lại toàn bộ stack khi đang chấm:** cả 50 bài vẫn được chấm (50 AC). 49 bài xong ở lần thử 1; 1 bài đang chấm dở lúc khởi động lại được phục hồi khi lease 60 s hết hạn và chấm lại ở lần thử 2 (chờ 67 s) — đó là nguyên nhân makespan 68 s.
""")
open(dst, "w").write("\n".join(out) + "\n")
print(f"wrote {dst} ({sum(len(v) for v in runs.values())} runs, {len(runs)} labels)")
