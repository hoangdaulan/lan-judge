#!/usr/bin/env bash
# Prepare demo data on the running stack (docs/DEMO.md): one exam with a
# session running for 2 hours, problem A (a+b, 15 tests) and problem B
# (sum 1..n, n up to 10^9, 200 ms — a naive loop gets TLE), and students DEMO01..05
# (password = date of birth 01/01/2004 as ddmmyyyy: 01012004). Prints the ids to use in the demo.
#
#   TEACHER_CODE=GV01 TEACHER_PASSWORD=... scripts/demo-setup.sh
set -euo pipefail
cd "$(dirname "$0")/.."
BASE=${BASE:-http://localhost:8080}
API=$BASE/api/v1
: "${TEACHER_CODE:?set TEACHER_CODE}" "${TEACHER_PASSWORD:?set TEACHER_PASSWORD}"
DATA=${DATA_HOST_DIR:-./data}

for tool in curl python3; do
	command -v "$tool" >/dev/null || { echo "Install $tool before running the demo (see README)." >&2; exit 1; }
done

j() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))' "$1"; }
post() { curl -sf -X POST "$API$1" -H "Authorization: Bearer $TT" -H 'Content-Type: application/json' -d "$2"; }

credentials=$(python3 -c 'import json,sys; print(json.dumps(dict(code=sys.argv[1], password=sys.argv[2])))' "$TEACHER_CODE" "$TEACHER_PASSWORD")
TT=$(curl -sf -X POST "$API/auth/login" -H 'Content-Type: application/json' \
	-d "$credentials" | j 'd["access_token"]')
EXAM=$(post /exams '{"title":"Demo Lap trinh mang","description":"Bai thi demo"}' | j 'd["id"]')
now=$(date -u +%s)
iso() { python3 -c 'from datetime import datetime, timezone; import sys; print(datetime.fromtimestamp(int(sys.argv[1]), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1"; }
SESSION=$(post "/exams/$EXAM/sessions" "{\"name\":\"Ca demo\",\"starts_at\":\"$(iso $((now - 60)))\",\"ends_at\":\"$(iso $((now + 7200)))\"}" | j 'd["id"]')

PA=$(post "/exams/$EXAM/problems" '{"code":"A","title":"Tong hai so","statement":"Doc hai so nguyen a, b (|a|,|b| <= 10^12). In a+b.","time_limit_ms":1000,"memory_limit_mb":256}')
PA_ID=$(echo "$PA" | j 'd["id"]')
dir="$DATA/$(echo "$PA" | j 'd["test_dir"]')"
python3 - "$dir" <<'PY'
import random, sys
d = sys.argv[1]
random.seed(1)
for i in range(1, 16):
    a, b = random.randint(-10**12, 10**12), random.randint(-10**12, 10**12)
    open(f"{d}/{i}.in", "w").write(f"{a} {b}\n")
    open(f"{d}/{i}.out", "w").write(f"{a+b}\n")
PY
post "/problems/$PA_ID/rejudge" '' >/dev/null

PB=$(post "/exams/$EXAM/problems" '{"code":"B","title":"Tong 1..n","statement":"Doc n (1 <= n <= 10^9). In 1+2+...+n.","time_limit_ms":200,"memory_limit_mb":256}')
PB_ID=$(echo "$PB" | j 'd["id"]')
dir="$DATA/$(echo "$PB" | j 'd["test_dir"]')"
python3 - "$dir" <<'PY'
import sys
d = sys.argv[1]
ns = [1, 10, 1000, 10**5, 10**6, 10**7, 3 * 10**7, 10**8, 5 * 10**8, 10**9]
for i, n in enumerate(ns, 1):
    open(f"{d}/{i}.in", "w").write(f"{n}\n")
    open(f"{d}/{i}.out", "w").write(f"{n * (n + 1) // 2}\n")
PY
post "/problems/$PB_ID/rejudge" '' >/dev/null

csv=$(mktemp)
printf 'full_name,code,date_of_birth\n' >"$csv"
for i in 1 2 3 4 5; do printf 'Sinh vien Demo %d,DEMO0%d,01/01/2004\n' "$i" "$i" >>"$csv"; done
curl -sf -X POST "$API/students/import" -H "Authorization: Bearer $TT" -F "file=@$csv" >/dev/null
rm -f "$csv"

cat <<EOF
exam_id=$EXAM
session_id=$SESSION
problem_A=$PA_ID   tests: $DATA/problems/$PA_ID/tests
problem_B=$PB_ID   tests: $DATA/problems/$PB_ID/tests
students: DEMO01..DEMO05 (password = 01012004)
EOF
