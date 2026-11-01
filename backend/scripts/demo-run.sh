#!/usr/bin/env bash
# Rehearse the demo of docs/DEMO.md end to end and check every expected
# outcome (run it before presenting). Uses scripts/demo-setup.sh data.
#
#   TEACHER_CODE=GV01 TEACHER_PASSWORD=... scripts/demo-run.sh
#
# Kills/restarts the worker for the crash step. Allows up to 180 seconds
# for its existing job lease to expire and the job to be judged again.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE=${BASE:-http://localhost:8080}
API=$BASE/api/v1
: "${TEACHER_CODE:?}" "${TEACHER_PASSWORD:?}"
TMP=$(mktemp -d)
worker_changed=false
test_changed=false
cleanup() {
	local status=$? job
	if [[ $test_changed == true ]]; then
		cp "$TMP/15.out.bak" "$dir/15.out"
		curl -sf -X POST "$API/problems/$PA/rejudge" -H "Authorization: Bearer $TT" >/dev/null || true
	fi
	if [[ $worker_changed == true ]]; then
		docker compose up -d --no-deps worker >/dev/null 2>&1 || echo "Run docker compose up -d worker to restore the worker." >&2
	fi
	for job in $(jobs -p); do kill "$job" 2>/dev/null || true; done
	rm -rf "$TMP"
	exit "$status"
}
trap cleanup EXIT
sh scripts/build-java.sh

j() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))' "$1"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
check() { if eval "$2"; then printf '  \033[32mOK\033[0m %s\n' "$1"; else printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; fi; }
login() {
	local credentials
	credentials=$(python3 -c 'import json,sys; print(json.dumps(dict(code=sys.argv[1], password=sys.argv[2])))' "$1" "$2")
	curl -sf -X POST "$API/auth/login" -H 'Content-Type: application/json' -d "$credentials" | j 'd["access_token"]'
}
submit() { curl -sf -X POST "$API/sessions/$SESSION/problems/$2/submissions" -H "Authorization: Bearer $1" -F "file=@$3" | j 'd["id"]'; }
result() { # result TOKEN SUBMISSION [TIMEOUT_SECONDS] -> "passed/total VERDICT"
	local deadline=$(($(date +%s) + ${3:-60}))
	while [[ $(date +%s) -lt $deadline ]]; do
		r=$(curl -sf "$API/submissions/$2" -H "Authorization: Bearer $1" |
			j '"%d/%d %s" % (d["result"]["passed_tests"], d["result"]["total_tests"], d["result"]["verdict"]) if d["result"] and d["result"]["status"] == "completed" else ""')
		[[ -n $r ]] && echo "$r" && return
		sleep 0.5
	done
	echo timeout
}

step "1. GV chuẩn bị đề, phiên, 2 problem, 5 SV"
out=$(scripts/demo-setup.sh)
echo "$out"
SESSION=$(sed -n 's/^session_id=//p' <<<"$out")
PA=$(sed -n 's/^problem_A=\([0-9]*\).*/\1/p' <<<"$out")
PB=$(sed -n 's/^problem_B=\([0-9]*\).*/\1/p' <<<"$out")
TT=$(login "$TEACHER_CODE" "$TEACHER_PASSWORD")
ST=$(login DEMO01 01012004)
curl -sf -X POST "$API/sessions/$SESSION/join" -H "Authorization: Bearer $ST" >/dev/null

step "2. SV và GV mở WebSocket"
./bin/wsprobe -base "$BASE" -code DEMO01 -password 01012004 -session "$SESSION" -duration 10m >"$TMP/sv.log" &
WSPROBE_PASSWORD=$TEACHER_PASSWORD ./bin/wsprobe -base "$BASE" -code "$TEACHER_CODE" -session "$SESSION" -duration 10m >"$TMP/gv.log" &
sleep 1

step "3. SV nộp: A đúng, A sai, B vòng lặp ngây thơ"
printf '#include <cstdio>\nint main(){long long a,b;scanf("%%lld %%lld",&a,&b);printf("%%lld\\n",a+b);}\n' >"$TMP/a_ok.cpp"
printf '#include <cstdio>\nint main(){long long a,b;scanf("%%lld %%lld",&a,&b);printf("%%lld\\n",a-b);}\n' >"$TMP/a_wa.cpp"
# volatile: otherwise -O2 turns the loop into n*(n+1)/2 and it is AC.
printf '#include <cstdio>\nint main(){long long n;volatile long long s=0;scanf("%%lld",&n);for(long long i=1;i<=n;i++)s+=i;printf("%%lld\\n",(long long)s);}\n' >"$TMP/b_naive.cpp"
s1=$(submit "$ST" "$PA" "$TMP/a_ok.cpp")
s2=$(submit "$ST" "$PA" "$TMP/a_wa.cpp")
s3=$(submit "$ST" "$PB" "$TMP/b_naive.cpp")
r1=$(result "$ST" "$s1"); r2=$(result "$ST" "$s2"); r3=$(result "$ST" "$s3")
echo "  A đúng: $r1 | A sai: $r2 | B ngây thơ: $r3"
check "A đúng = 15/15 AC" '[[ $r1 == "15/15 AC" ]]'
check "A sai = WA" '[[ $r2 == *" WA" ]]'
check "B ngây thơ = x/10 TLE (qua được test nhỏ)" '[[ $r3 == */10\ TLE && ${r3%%/*} -ge 5 ]]'
sleep 1
check "SV nhận kết quả qua WebSocket" 'grep -q "\"verdict\":\"TLE\"" "$TMP/sv.log"'
check "GV nhận bảng tổng hợp trực tiếp" 'grep -q "summary.updated" "$TMP/gv.log"'

step "4. GV gửi thông báo"
curl -sf -X POST "$API/sessions/$SESSION/announcements" -H "Authorization: Bearer $TT" \
	-H 'Content-Type: application/json' -d '{"text":"Còn 10 phút"}' >/dev/null
sleep 0.5
check "SV nhận thông báo qua WebSocket" 'grep -q "Còn 10 phút" "$TMP/sv.log"'

step "5. GV sửa test trên server rồi bấm Chấm lại"
dir=${DATA_HOST_DIR:-./data}/problems/$PA/tests
cp "$dir/15.out" "$TMP/15.out.bak"
test_changed=true
echo 0 >"$dir/15.out"
curl -sf -X POST "$API/problems/$PA/rejudge" -H "Authorization: Bearer $TT" >/dev/null
sleep 1
r1=$(result "$ST" "$s1")
check "Bài AC trước đó thành 14/15 WA sau khi test đổi" '[[ $r1 == "14/15 WA" ]]'
cp "$TMP/15.out.bak" "$dir/15.out"
curl -sf -X POST "$API/problems/$PA/rejudge" -H "Authorization: Bearer $TT" >/dev/null
test_changed=false
sleep 1
r1=$(result "$ST" "$s1")
check "Sửa lại test → 15/15 AC" '[[ $r1 == "15/15 AC" ]]'

step "6. Worker chết khi đang chấm → bài vẫn được chấm"
s4=$(submit "$ST" "$PB" "$TMP/b_naive.cpp")
for i in $(seq 1 100); do # wait until the worker has claimed it
	st=$(curl -sf "$API/submissions/$s4" -H "Authorization: Bearer $ST" | j '(d["result"] or {}).get("status")')
	[[ $st == judging ]] && break
	sleep 0.05
done
check "Bài đã được worker nhận chấm" '[[ $st == judging ]]'
worker_changed=true
docker compose kill worker >/dev/null 2>&1
echo "  worker bị kill khi bài $s4 đang ở trạng thái: $st"
start=$(date +%s)
docker compose up -d --no-deps worker >/dev/null 2>&1
r4=$(result "$ST" "$s4" 180)
waited=$(($(date +%s) - start))
attempts=$(docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT j.attempt_count FROM judge_jobs j JOIN judge_runs r ON r.id=j.judge_run_id WHERE r.submission_id=$1 ORDER BY r.id DESC LIMIT 1"' sh "$s4")
echo "  bài $s4: $r4 sau $waited s, attempt_count=$attempts (đợi lease cũ hết hạn)"
check "Bài được nhận lại và chấm xong sau khi worker khởi động lại" '[[ $r4 == */10\ TLE && $attempts -ge 2 ]]'
worker_changed=false

step "7. 50 SV giả lập nộp cùng lúc"
res=$(./bin/loadtest -base "$BASE" -teacher-code "$TEACHER_CODE" -teacher-password "$TEACHER_PASSWORD" -data "${DATA_HOST_DIR:-./data}" -students 50 -tests 15 -mode ws -label demo)
echo "$res" | j '"  50 bài: e2e p50 %.0f ms, p95 %.0f ms, %.1f bài/s, verdict %s, HTTP %s" % (d["submit_to_result"]["p50_ms"], d["submit_to_result"]["p95_ms"], d["throughput_per_s"], d["verdicts"], d["http_status"])'
check "50/50 AC, không lỗi HTTP" "echo '$res' | python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d[\"verdicts\"] == {\"AC\": 50} and all(c.startswith(\"2\") for c in d[\"http_status\"]) else 1)'"

printf '\n\033[1mDemo OK\033[0m\n'
