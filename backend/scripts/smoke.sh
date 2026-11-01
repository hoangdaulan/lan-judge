#!/usr/bin/env bash
# Smoke test HTTP cho API v1 trên stack dev đang chạy (docs/IMPLEMENTATION_PLAN.md).
#
#   scripts/smoke.sh   (credentials from .smoke.env, or SMOKE_TEACHER_CODE=... SMOKE_TEACHER_PASSWORD=...)
#   scripts/smoke.sh [auth|students|exams|problems|submissions|judge|rejudge|ws|summary|all]
#
# Biến tùy chọn: SMOKE_TEACHER2_CODE/SMOKE_TEACHER2_PASSWORD (GV thứ hai, bật kiểm
# tra quyền sở hữu đề), BASE (mặc định http://localhost:8080), DATA_HOST_DIR (thư mục
# ./data trên host, mặc định ./data). Mỗi lần chạy tạo dữ liệu mới (mã có hậu
# tố thời gian) và không xóa dữ liệu cũ. Cần curl và python3.
set -uo pipefail
BACKEND_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)

# Local test credentials (git-ignored), created with `make seed`.
if [[ -f .smoke.env ]]; then
	set -a
	# shellcheck disable=SC1091
	. ./.smoke.env
	set +a
fi

BASE=${BASE:-http://localhost:8080}
API=$BASE/api/v1
DATA_HOST_DIR=${DATA_HOST_DIR:-./data}
TEACHER_CODE=${SMOKE_TEACHER_CODE:?set SMOKE_TEACHER_CODE}
TEACHER_PASSWORD=${SMOKE_TEACHER_PASSWORD:?set SMOKE_TEACHER_PASSWORD}
RUN=$(date +%H%M%S)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
STATUS=""
BODY=""

# req METHOD PATH [JSON_BODY] [TOKEN]  -> sets STATUS, BODY
req() {
	local method=$1 path=$2 data=${3:-} token=${4:-}
	local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$method" "$API$path")
	[[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
	[[ -n $data ]] && args+=(-H 'Content-Type: application/json' -d "$data")
	STATUS=$(curl "${args[@]}")
	BODY=$(cat "$TMP/body")
}

# upload PATH FILE TOKEN  -> multipart field "file"
upload() {
	local share_pid=''
	if [[ $1 =~ ^/sessions/([0-9]+)/problems/[0-9]+/submissions$ ]]; then
		share_for "$3" "${BASH_REMATCH[1]}"
		share_pid=$SHARE_PID
	fi
	STATUS=$(curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$API$1" -H "Authorization: Bearer $3" -F "file=@$2")
	BODY=$(cat "$TMP/body")
	if [[ -n $share_pid ]]; then kill "$share_pid" 2>/dev/null || true; wait "$share_pid" 2>/dev/null || true; fi
}

# Automated protocol client: report sharing over an authenticated connection
# and keep it open until the multipart request finishes. Browser capture is
# exercised separately by screen-share.mjs.
share_for() {
	local i
	WSPROBE_ACCESS_TOKEN="$1" "$BACKEND_DIR/bin/wsprobe" -base "$BASE" -session "$2" -sharing true -duration 1m >"$TMP/sharing.log" 2>&1 &
	SHARE_PID=$!
	for i in $(seq 1 100); do
		grep -q '"type":"screen.state"' "$TMP/sharing.log" && return
		kill -0 "$SHARE_PID" 2>/dev/null || return 0
		sleep 0.05
	done
}

# jget EXPR  -> evaluates a Python expression over the JSON body bound to d
jget() {
	python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(eval(sys.argv[2]))' "$BODY" "$1"
}

ok() { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s (status %s) %s\n' "$1" "$STATUS" "${BODY:0:300}"; }

# expect DESC STATUS [PYTHON_CONDITION over d]
expect() {
	local desc=$1 want=$2 cond=${3:-}
	if [[ $STATUS != "$want" ]]; then
		bad "$desc: want $want"
		return
	fi
	if [[ -n $cond ]] && ! python3 -c 'import json,sys; d=json.loads(sys.argv[1]); sys.exit(0 if eval(sys.argv[2]) else 1)' "$BODY" "$cond" 2>/dev/null; then
		bad "$desc: condition $cond"
		return
	fi
	ok "$desc"
}

check() { # check DESC SHELL_TEST...
	local desc=$1
	shift
	if "$@"; then ok "$desc"; else bad "$desc"; fi
}

iso() { python3 -c 'from datetime import datetime, timezone; import sys; print(datetime.fromtimestamp(int(sys.argv[1]), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1"; }
lower() { python3 -c 'import sys; print(sys.argv[1].lower())' "$1"; }
upper() { python3 -c 'import sys; print(sys.argv[1].upper())' "$1"; }

login_body() { # CODE PASSWORD [ROLE] -> JSON, including passwords with quotes/backslashes
	python3 -c 'import json,sys; d=dict(code=sys.argv[1], password=sys.argv[2]); d.update(role=sys.argv[3]) if len(sys.argv) > 3 else None; print(json.dumps(d))' "$@"
}

login() { # login CODE PASSWORD -> prints access token
	req POST /auth/login "$(login_body "$1" "$2")"
	jget 'd["access_token"]'
}

teacher_token() { login "$TEACHER_CODE" "$TEACHER_PASSWORD"; }

# Students are created with this date of birth; the initial password is it as ddmmyyyy.
SV_DOB="05/09/2004" SV_PASS="05092004"

new_student() { # new_student CODE TEACHER_TOKEN -> prints id
	req POST /students "{\"code\":\"$1\",\"fullname\":\"Smoke $1\",\"date_of_birth\":\"$SV_DOB\"}" "$2"
	jget 'd["id"]'
}

# make_exam TOKEN -> sets EXAM_ID, RUNNING_ID, UPCOMING_ID
make_exam() {
	local now
	now=$(date -u +%s)
	req POST /exams "{\"title\":\"Smoke $RUN\",\"description\":\"\"}" "$1"
	EXAM_ID=$(jget 'd["id"]')
	req POST "/exams/$EXAM_ID/sessions" "{\"name\":\"running\",\"starts_at\":\"$(iso $((now - 60)))\",\"ends_at\":\"$(iso $((now + 3600)))\"}" "$1"
	RUNNING_ID=$(jget 'd["id"]')
	req POST "/exams/$EXAM_ID/sessions" "{\"name\":\"upcoming\",\"starts_at\":\"$(iso $((now + 3600)))\",\"ends_at\":\"$(iso $((now + 7200)))\"}" "$1"
	UPCOMING_ID=$(jget 'd["id"]')
}

section_auth() {
	echo "== auth"
	req POST /auth/login "$(login_body "$TEACHER_CODE" "$TEACHER_PASSWORD" teacher)"
	expect "login with legacy role field rejected" 400 '"role" in d["error"]'
	req POST /auth/login "{\"code\":\"$TEACHER_CODE\",\"password\":\"wrong-$RUN\"}"
	expect "wrong password" 401
	local wrong_msg=$BODY
	req POST /auth/login "{\"code\":\"NOPE$RUN\",\"password\":\"x\"}"
	expect "unknown code, same message as wrong password" 401 "d == $wrong_msg"
	req POST /auth/login "{\"code\":\"$TEACHER_CODE\"}"
	expect "missing password" 400

	req POST /auth/login "$(login_body "$(lower "$TEACHER_CODE")" "$TEACHER_PASSWORD")"
	expect "login lowercase code, role from DB" 200 'd["user"]["role"] == "teacher" and d["user"]["code"] == "'"$(upper "$TEACHER_CODE")"'"'
	local at rt rt2
	at=$(jget 'd["access_token"]')
	rt=$(jget 'd["refresh_token"]')
	check "JWT HS256, role claim, 15 min TTL" python3 - "$at" <<'PY'
import base64, json, sys
h, p, _ = sys.argv[1].split(".")
dec = lambda s: json.loads(base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)))
h, p = dec(h), dec(p)
sys.exit(0 if h["alg"] == "HS256" and p["role"] == "teacher" and p["exp"] - p["iat"] == 900 else 1)
PY
	req GET /me
	expect "/me without token" 401
	req GET /me "" "${at%?}x"
	expect "/me with bad signature" 401
	req GET /me "" "$at"
	expect "/me" 200 'd["role"] == "teacher"'

	req POST /auth/refresh "{\"refresh_token\":\"$rt\"}"
	expect "refresh rotates" 200 'd["refresh_token"] != "'"$rt"'"'
	rt2=$(jget 'd["refresh_token"]')
	req POST /auth/refresh "{\"refresh_token\":\"$rt\"}"
	expect "old refresh token is single use" 401
	req POST /auth/logout "{\"refresh_token\":\"$rt2\"}"
	expect "logout" 204
	req POST /auth/refresh "{\"refresh_token\":\"$rt2\"}"
	expect "refresh after logout" 401

	local code="SMK${RUN}AU" st srt
	new_student "$code" "$at" >/dev/null
	req POST /auth/login "{\"code\":\"$code\",\"password\":\"$SV_PASS\"}"
	expect "student login with date of birth" 200 'd["user"]["role"] == "student"'
	st=$(jget 'd["access_token"]')
	srt=$(jget 'd["refresh_token"]')
	req PUT /me/password '{"current_password":"bad","new_password":"x"}' "$st"
	expect "change password, wrong current" 401
	req PUT /me/password "{\"current_password\":\"$SV_PASS\",\"new_password\":\"new-$RUN\"}" "$st"
	expect "change password" 204
	req POST /auth/refresh "{\"refresh_token\":\"$srt\"}"
	expect "refresh tokens revoked by password change" 401
	req POST /auth/login "{\"code\":\"$code\",\"password\":\"new-$RUN\"}"
	expect "login with new password" 200
	req GET /students "" "$st"
	expect "student on teacher route" 403
	req GET /me/sessions "" "$at"
	expect "teacher on student route" 403
}

section_students() {
	echo "== students"
	local tt
	tt=$(teacher_token)
	local a="SMK${RUN}A" b="SMK${RUN}B" c="SMK${RUN}C"
	req POST /students "{\"code\":\" $(lower "$a") \",\"fullname\":\"Nguyen   Van  A\",\"date_of_birth\":\"5/9/2004\"}" "$tt"
	expect "create student normalizes" 201 'd["code"] == "'"$a"'" and d["fullname"] == "Nguyen Van A" and d["date_of_birth"] == "2004-09-05"'
	req POST /students "{\"code\":\"$a\",\"fullname\":\"X\",\"date_of_birth\":\"$SV_DOB\"}" "$tt"
	expect "duplicate student code" 409
	req POST /students "{\"code\":\"$TEACHER_CODE\",\"fullname\":\"X\",\"date_of_birth\":\"$SV_DOB\"}" "$tt"
	expect "student with teacher code" 409
	req POST /students "{\"code\":\"A B\",\"fullname\":\"X\",\"date_of_birth\":\"$SV_DOB\"}" "$tt"
	expect "code with space" 400
	req POST /students "{\"code\":\"${c}N\",\"fullname\":\"X\"}" "$tt"
	expect "student without date of birth" 400

	printf 'full_name,code,date_of_birth\nPham D,%s,01/01/2004\nNo Name,%s,\nDup,%s,01/01/2004\n' "${b}X" "${c}X" "$(lower "$b")x" >"$TMP/bad.csv"
	upload /students/import "$TMP/bad.csv" "$tt"
	expect "bad CSV rejected with row errors" 400 '[r["line"] for r in d["row_errors"]] == [3, 4] and d["row_errors"][0]["error"] == "date_of_birth is required"'
	req GET "/students?query=${b}X" "" "$tt"
	expect "bad CSV saved nothing" 200 'd["data"] == [] and d["totalPages"] == 0'

	printf '\xef\xbb\xbffull_name,code,date_of_birth,class\nTran Thi B,%s,%s,D21\nAgain,%s,%s,D21\n"Le, Van C",%s,%s,D21\nTeacher,%s,%s,D21\n' "$b" "$SV_DOB" "$a" "$SV_DOB" "$c" "$SV_DOB" "$TEACHER_CODE" "$SV_DOB" >"$TMP/ok.csv"
	upload /students/import "$TMP/ok.csv" "$tt"
	expect "import skips existing + teacher codes" 200 'd["created_count"] == 2 and [(s["line"], s["reason"]) for s in d["skipped"]] == [(3, "already exists"), (5, "code belongs to a teacher account")]'

	req GET "/students?query=SMK${RUN}&pageSize=2" "" "$tt"
	expect "list search + paging" 200 'd["totalPages"] >= 2 and len(d["data"]) == 2 and d["pageSize"] == 2 and d["page"] == 1'
	req GET "/students?query=$TEACHER_CODE" "" "$tt"
	expect "teachers never listed as students" 200 'all(s["code"] != "'"$(upper "$TEACHER_CODE")"'" for s in d["data"])'

	req POST /auth/login "{\"code\":\"$b\",\"password\":\"$SV_PASS\"}"
	expect "imported student logs in with date of birth" 200
	local st srt sid
	st=$(jget 'd["access_token"]')
	sid=$(jget 'd["user"]["id"]')
	req GET /students "" "$st"
	expect "student cannot manage students" 403
	req PUT /me/password "{\"current_password\":\"$SV_PASS\",\"new_password\":\"changed-$RUN\"}" "$st"
	req POST /auth/login "{\"code\":\"$b\",\"password\":\"changed-$RUN\"}"
	srt=$(jget 'd["refresh_token"]')
	req POST "/students/$sid/reset-password" "" "$tt"
	expect "reset password" 204
	req POST /auth/refresh "{\"refresh_token\":\"$srt\"}"
	expect "reset revokes refresh tokens" 401
	req POST /auth/login "{\"code\":\"$b\",\"password\":\"$SV_PASS\"}"
	expect "login with date of birth after reset" 200
	req GET /me "" "$tt"
	local tid
	tid=$(jget 'd["id"]')
	req POST "/students/$tid/reset-password" "" "$tt"
	expect "reset on a teacher id" 404
}

section_exams() {
	echo "== exams"
	local tt st code="SMK${RUN}E"
	tt=$(teacher_token)
	new_student "$code" "$tt" >/dev/null
	st=$(login "$code" "$SV_PASS")
	make_exam "$tt"
	req GET "/exams/$EXAM_ID" "" "$tt"
	expect "get exam" 200
	req POST "/exams/$EXAM_ID/sessions" "{\"name\":\"bad\",\"starts_at\":\"2026-10-21T10:00:00Z\",\"ends_at\":\"2026-10-21T10:00:00Z\"}" "$tt"
	expect "ends_at must be after starts_at" 400
	req POST "/exams/$EXAM_ID/problems" '{"code":"A","title":"Sum","statement":"a+b","time_limit_ms":1000,"memory_limit_mb":256}' "$tt"

	req GET /me/sessions "" "$st"
	expect "student sees sessions" 200 "any(i['id'] == $RUNNING_ID and i['participation'] is None for i in d['data'])"
	req GET "/sessions/$UPCOMING_ID" "" "$st"
	expect "upcoming hides problems" 200 'd["problems_hidden"] and d["problems"] == []'
	req GET "/sessions/$RUNNING_ID" "" "$st"
	expect "running shows problems" 200 'not d["problems_hidden"] and len(d["problems"]) == 1 and d["participation"] is None'
	req POST "/sessions/$UPCOMING_ID/join" "" "$st"
	expect "join before start" 409
	req POST "/sessions/$UPCOMING_ID/finish" "" "$st"
	expect "finish without join" 409
	req POST "/sessions/$RUNNING_ID/join" "" "$st"
	expect "join running" 200 'd["status"] == "in_progress"'
	local pid
	pid=$(jget 'd["id"]')
	req POST "/sessions/$RUNNING_ID/join" "" "$st"
	expect "re-join returns same row" 200 "d['id'] == $pid"
	req POST "/sessions/$RUNNING_ID/finish" "" "$st"
	expect "finish" 200 'd["status"] == "finished"'
	local fin
	fin=$(jget 'd["finished_at"]')
	sleep 1
	req POST "/sessions/$RUNNING_ID/finish" "" "$st"
	expect "finish is idempotent" 200 "d['finished_at'] == '$fin'"
	req GET "/sessions/$RUNNING_ID/participants" "" "$tt"
	expect "participants" 200 "[(p['student_code'], p['status']) for p in d['data']] == [('$code', 'finished')]"
	req DELETE "/exams/$EXAM_ID" "" "$tt"
	expect "delete exam with children" 409
	req DELETE "/sessions/$UPCOMING_ID" "" "$tt"
	expect "delete session without submissions" 204
	if [[ -n ${SMOKE_TEACHER2_CODE:-} ]]; then
		local t2
		t2=$(login "$SMOKE_TEACHER2_CODE" "${SMOKE_TEACHER2_PASSWORD:?set SMOKE_TEACHER2_PASSWORD}")
		req GET "/exams/$EXAM_ID" "" "$t2"
		expect "other teacher cannot read exam" 404
		req PUT "/exams/$EXAM_ID" '{"title":"hijack","description":""}' "$t2"
		expect "other teacher cannot edit exam" 404
		req GET "/sessions/$RUNNING_ID/participants" "" "$t2"
		expect "other teacher cannot list participants" 404
		req GET /exams "" "$t2"
		expect "other teacher's exam list excludes it" 200 "all(e['id'] != $EXAM_ID for e in d['data'])"
	else
		echo "  (skip ownership checks: set SMOKE_TEACHER2_CODE/SMOKE_TEACHER2_PASSWORD)"
	fi
	req GET /exams/999999999 "" "$tt"
	expect "unknown exam" 404
	req GET /exams/abc "" "$tt"
	expect "malformed id" 400
}

section_problems() {
	echo "== problems"
	local tt st code="SMK${RUN}P"
	tt=$(teacher_token)
	new_student "$code" "$tt" >/dev/null
	st=$(login "$code" "$SV_PASS")
	make_exam "$tt"
	req POST "/exams/$EXAM_ID/problems" '{"code":" a ","title":"Sum","statement":"a+b","time_limit_ms":1000,"memory_limit_mb":256}' "$tt"
	expect "create problem" 201 'd["code"] == "A" and d["version"] == 1'
	local pid dir
	pid=$(jget 'd["id"]')
	dir="$DATA_HOST_DIR/$(jget 'd["test_dir"]')"
	check "test directory created and writable on host" test -d "$dir" -a -w "$dir"
	req POST "/exams/$EXAM_ID/problems" '{"code":"A","title":"dup","statement":"","time_limit_ms":1000,"memory_limit_mb":256}' "$tt"
	expect "duplicate problem code" 409
	req POST "/exams/$EXAM_ID/problems" '{"code":"B","title":"x","statement":"","time_limit_ms":0,"memory_limit_mb":256}' "$tt"
	expect "time limit out of range" 400

	req POST "/problems/$pid/rejudge" "" "$tt"
	expect "rejudge without tests" 409
	printf '1 2\n' >"$dir/1.in"
	req POST "/problems/$pid/rejudge" "" "$tt"
	expect "rejudge with unpaired file" 409 '"1.in has no 1.out" in d["error"]'
	printf '3\n' >"$dir/1.out"
	printf '' >"$dir/2.in"
	printf '0\n' >"$dir/2.out"
	printf '5 5\n' >"$dir/10.in"
	printf '10\n' >"$dir/10.out"
	req POST "/problems/$pid/rejudge" "" "$tt"
	expect "rejudge syncs tests in numeric order" 200 'd["problem"]["version"] == 2 and [t["name"] for t in d["tests"]] == ["1", "2", "10"]'
	req GET "/problems/$pid/tests" "" "$tt"
	expect "list tests" 200 'len(d["data"]) == 3'

	req PUT "/problems/$pid" '{"code":"A","title":"Sum 2","statement":"a+b","time_limit_ms":1000,"memory_limit_mb":256}' "$tt"
	expect "text edit keeps version" 200 'd["problem"]["version"] == 2 and not d["rejudge_queued"]'
	req PUT "/problems/$pid" '{"code":"A","title":"Sum 2","statement":"a+b","time_limit_ms":2000,"memory_limit_mb":256}' "$tt"
	expect "limit change bumps version" 200 'd["problem"]["version"] == 3 and d["rejudge_queued"]'

	req GET "/sessions/$RUNNING_ID/problems/$pid" "" "$st"
	expect "statement before join" 403
	req POST "/sessions/$RUNNING_ID/join" "" "$st"
	req GET "/sessions/$RUNNING_ID/problems/$pid" "" "$st"
	expect "statement after join" 200 'd["statement"] == "a+b" and d["time_limit_ms"] == 2000'
	req GET "/sessions/$UPCOMING_ID/problems/$pid" "" "$st"
	expect "statement before starts_at" 403

	local other_running=$RUNNING_ID
	make_exam "$tt"
	req POST "/exams/$EXAM_ID/problems" '{"code":"Z","title":"tmp","statement":"","time_limit_ms":1000,"memory_limit_mb":64}' "$tt"
	local other
	other=$(jget 'd["id"]')
	req GET "/sessions/$other_running/problems/$other" "" "$st"
	expect "problem of another exam" 404
	req DELETE "/problems/$other" "" "$tt"
	expect "delete problem without submissions" 204
	req GET "/problems/$other" "" "$tt"
	expect "deleted problem gone" 404
	req POST "/problems/$pid/rejudge" "" "$st"
	expect "student cannot rejudge" 403
}

# make_problem TOKEN CODE -> prints id; writes 1.in/1.out and syncs tests
make_problem() {
	req POST "/exams/$EXAM_ID/problems" "{\"code\":\"$2\",\"title\":\"Sum\",\"statement\":\"a+b\",\"time_limit_ms\":1000,\"memory_limit_mb\":256}" "$1"
	local id dir
	id=$(jget 'd["id"]')
	dir="$DATA_HOST_DIR/$(jget 'd["test_dir"]')"
	printf '1 2\n' >"$dir/1.in"
	printf '3\n' >"$dir/1.out"
	req POST "/problems/$id/rejudge" "" "$1"
	echo "$id"
}

section_submissions() {
	echo "== submissions"
	local tt st st2 a="SMK${RUN}S1" b="SMK${RUN}S2"
	tt=$(teacher_token)
	new_student "$a" "$tt" >/dev/null
	new_student "$b" "$tt" >/dev/null
	st=$(login "$a" "$SV_PASS")
	st2=$(login "$b" "$SV_PASS")
	make_exam "$tt"
	local pid empty_pid
	pid=$(make_problem "$tt" A)
	req POST "/exams/$EXAM_ID/problems" '{"code":"B","title":"No tests","statement":"","time_limit_ms":1000,"memory_limit_mb":256}' "$tt"
	empty_pid=$(jget 'd["id"]')

	printf '#include <cstdio>\nint main(){int a,b;scanf("%%d %%d",&a,&b);printf("%%d\\n",a+b);}\n' >"$TMP/ok.cpp"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/ok.cpp" "$st"
	expect "submit before join" 409 '"join" in d["error"]'
	req POST "/sessions/$RUNNING_ID/join" "" "$st"
	req POST "/sessions/$RUNNING_ID/join" "" "$st2"

	STATUS=$(curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$API/sessions/$RUNNING_ID/problems/$pid/submissions" -H "Authorization: Bearer $st" -F "file=@$TMP/ok.cpp")
	BODY=$(cat "$TMP/body")
	expect "submit without an active sharing connection is rejected" 403 '"screen-sharing" in d["error"]'
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/ok.cpp" "$st"
	expect "submit accepted" 202 'd["result"]["status"] == "queued" and d["result"]["problem_version"] == d["problem_version"] and d["student_code"] == "'"$a"'"'
	local sid
	sid=$(jget 'd["id"]')

	cp "$TMP/ok.cpp" "$TMP/ok.c"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/ok.c" "$st"
	expect "wrong extension" 400
	: >"$TMP/empty.cpp"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/empty.cpp" "$st"
	expect "empty file" 400
	head -c 65536 /dev/zero | tr '\0' ' ' >"$TMP/max.cpp"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/max.cpp" "$st"
	expect "exactly 64 KB accepted" 202
	head -c 65537 /dev/zero | tr '\0' ' ' >"$TMP/big.cpp"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/big.cpp" "$st"
	expect "64 KB + 1 byte" 413
	head -c 300000 /dev/zero | tr '\0' ' ' >"$TMP/huge.cpp"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/huge.cpp" "$st"
	expect "300 KB body" 413
	upload "/sessions/$RUNNING_ID/problems/$empty_pid/submissions" "$TMP/ok.cpp" "$st"
	expect "problem without tests" 409 '"no tests" in d["error"]'
	upload "/sessions/$UPCOMING_ID/problems/$pid/submissions" "$TMP/ok.cpp" "$st"
	expect "session not joined (upcoming)" 409

	req GET "/submissions/$sid" "" "$st"
	expect "owner reads submission" 200 "d['id'] == $sid and 'system_error' not in (d['result'] or {})"
	req GET "/submissions/$sid" "" "$st2"
	expect "other student cannot read" 404
	STATUS=$(curl -s -o "$TMP/src" -w '%{http_code}' -H "Authorization: Bearer $st" "$API/submissions/$sid/source")
	check "owner downloads source" cmp -s "$TMP/src" "$TMP/ok.cpp"
	req GET "/submissions/$sid/source" "" "$st2"
	expect "other student cannot download source" 404
	req GET "/sessions/$RUNNING_ID/submissions" "" "$st2"
	expect "student list shows only own" 200 'd["data"] == []'
	req GET "/sessions/$RUNNING_ID/submissions?student_id=0" "" "$tt"
	expect "bad filter" 400
	req GET "/sessions/$RUNNING_ID/submissions?problem_id=$pid" "" "$tt"
	expect "teacher lists session" 200 'len(d["data"]) == 2'
	req GET "/submissions/$sid/tests" "" "$tt"
	# Judging may already have finished (workers wake on NOTIFY): accept either.
	expect "teacher per-test results" 200 "d['submission']['id'] == $sid and all(t['verdict'] in ('AC','WA','TLE','MLE','RE') for t in d['data'])"
	req GET "/submissions/$sid/tests" "" "$st"
	expect "student cannot read per-test results" 403

	# The teacher moves the session start into the future: received_at < starts_at.
	local now
	now=$(date -u +%s)
	req PUT "/sessions/$RUNNING_ID" "{\"name\":\"moved\",\"starts_at\":\"$(iso $((now + 600)))\",\"ends_at\":\"$(iso $((now + 3600)))\"}" "$tt"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/ok.cpp" "$st"
	expect "submit before starts_at" 409 '"not started" in d["error"]'
	req PUT "/sessions/$RUNNING_ID" "{\"name\":\"moved\",\"starts_at\":\"$(iso $((now - 600)))\",\"ends_at\":\"$(iso $((now + 2)))\"}" "$tt"
	sleep 3
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/ok.cpp" "$st"
	expect "submit after ends_at" 409 '"ended" in d["error"]'

	# Problem of another exam through this session.
	local other_session=$RUNNING_ID other_pid
	make_exam "$tt"
	other_pid=$(make_problem "$tt" Z)
	upload "/sessions/$other_session/problems/$other_pid/submissions" "$TMP/ok.cpp" "$st"
	expect "problem of another exam" 404

	# Finished participant, and submit racing finish on the same participant.
	req POST "/sessions/$RUNNING_ID/join" "" "$st2"
	pid=$(make_problem "$tt" A)
	req POST "/sessions/$RUNNING_ID/finish" "" "$st2"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/ok.cpp" "$st2"
	expect "submit after finish" 409 '"finished" in d["error"]'

	local i accepted=0 rejected=0 other=0
	for i in $(seq 1 16); do
		local code="SMK${RUN}R$i" tok
		new_student "$code" "$tt" >/dev/null
		tok=$(login "$code" "$SV_PASS")
		req POST "/sessions/$RUNNING_ID/join" "" "$tok"
		share_for "$tok" "$RUNNING_ID"
		curl -s -o "$TMP/race-sub-$i" -w '%{http_code}' -H "Authorization: Bearer $tok" -F "file=@$TMP/ok.cpp" \
			"$API/sessions/$RUNNING_ID/problems/$pid/submissions" >"$TMP/race-sub-code-$i" &
		local submit_pid=$!
		# Stagger finish by 0-70 ms so both orders occur across the trials.
		(sleep "0.0$(((i - 1) % 8))" && curl -s -o /dev/null -X POST -H "Authorization: Bearer $tok" "$API/sessions/$RUNNING_ID/finish") &
		local finish_pid=$!
		wait "$submit_pid" "$finish_pid"
		kill "$SHARE_PID" 2>/dev/null || true
		wait "$SHARE_PID" 2>/dev/null || true
		local code_sub
		code_sub=$(cat "$TMP/race-sub-code-$i")
		req GET "/sessions/$RUNNING_ID/submissions?problem_id=$pid" "" "$tok"
		local n
		n=$(jget 'len(d["data"])')
		if [[ $code_sub == 202 && $n == 1 ]]; then
			accepted=$((accepted + 1))
		elif [[ $code_sub == 409 && $n == 0 ]] && grep -q finished "$TMP/race-sub-$i"; then
			rejected=$((rejected + 1))
		else
			other=$((other + 1))
		fi
	done
	STATUS=200 BODY="{\"accepted\":$accepted,\"rejected\":$rejected,\"inconsistent\":$other}"
	expect "submit vs finish race: 202 <=> stored, else 409 finished" 200 'd["inconsistent"] == 0'
	echo "    race outcome: $BODY"
}

# wait_result SUBMISSION_ID TOKEN -> polls until the run is completed/system_error
wait_result() {
	local i
	for i in $(seq 1 60); do
		req GET "/submissions/$1" "" "$2"
		if [[ $(jget '(d["result"] or {}).get("status")') =~ ^(completed|system_error)$ ]]; then
			return 0
		fi
		sleep 0.5
	done
	return 1
}

section_judge() {
	echo "== judge (needs the worker with the sandbox)"
	local tt st code="SMK${RUN}J"
	tt=$(teacher_token)
	new_student "$code" "$tt" >/dev/null
	st=$(login "$code" "$SV_PASS")
	make_exam "$tt"
	req POST "/exams/$EXAM_ID/problems" '{"code":"A","title":"Sum","statement":"a+b","time_limit_ms":1000,"memory_limit_mb":256}' "$tt"
	local pid dir
	pid=$(jget 'd["id"]')
	dir="$DATA_HOST_DIR/$(jget 'd["test_dir"]')"
	printf '1 2\n' >"$dir/1.in"
	printf '3\n' >"$dir/1.out"
	printf '10 20\n' >"$dir/2.in"
	printf '30\n' >"$dir/2.out"
	printf '100 1\n' >"$dir/3.in"
	printf '101\n' >"$dir/3.out"
	req POST "/problems/$pid/rejudge" "" "$tt"
	req POST "/sessions/$RUNNING_ID/join" "" "$st"

	local name want src
	while IFS='|' read -r name want src; do
		printf '%b' "$src" >"$TMP/$name.cpp"
		upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/$name.cpp" "$st"
		local sid
		sid=$(jget 'd["id"]')
		if ! wait_result "$sid" "$st"; then
			bad "$name: not judged within 30 s"
			continue
		fi
		expect "$name -> $want" 200 "'%d/%d %s' % (d['result']['passed_tests'], d['result']['total_tests'], d['result']['verdict']) == '$want' and 'system_error' not in d['result']"
	done <<'EOF'
ac|3/3 AC|#include <cstdio>\nint main(){long long a,b;scanf("%lld %lld",&a,&b);printf("%lld\\n",a+b);}
wa_test2|1/3 WA|#include <cstdio>\nint main(){long long a,b;scanf("%lld %lld",&a,&b);printf("%lld\\n",a==1?3:a-b);}
tle_test3|2/3 TLE|#include <cstdio>\nint main(){long long a,b;scanf("%lld %lld",&a,&b);if(a==100)for(volatile int i=0;;i++);printf("%lld\\n",a+b);}
re|0/3 RE|int main(){volatile int*p=0;*p=1;}
ce|0/3 CE|int main(){ return }
EOF
	req GET "/submissions/$(jget 'd["id"]')" "" "$st"
	expect "student sees compile output for CE" 200 '"error" in (d["result"]["compile_output"] or "")'

	printf '0\n' >"$dir/1.out"
	req GET "/problems/$pid/tests" "" "$tt"
	expect "editing test contents without renaming is detected" 200 'not d["disk"]["in_sync"]'
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/ac.cpp" "$st"
	local snapshot_sub
	snapshot_sub=$(jget 'd["id"]')
	wait_result "$snapshot_sub" "$st"
	expect "unpublished test edits do not change the active verdict" 200 'd["result"]["verdict"] == "AC" and d["problem_version"] == 2'
	req POST "/problems/$pid/rejudge" "" "$tt"
	wait_result "$snapshot_sub" "$st"
	expect "rescan publishes edited content under the next version" 200 'd["result"]["verdict"] == "WA" and d["problem_version"] == 3'
	printf '3\n' >"$dir/1.out"
	req POST "/problems/$pid/rejudge" "" "$tt"
}

# result_of SUBMISSION_ID TOKEN -> "passed/total VERDICT@vVERSION run=ID" once judged
result_of() {
	wait_result "$1" "$2" || {
		echo "timeout"
		return
	}
	jget "'%d/%d %s@v%d run=%d' % (d['result']['passed_tests'], d['result']['total_tests'], d['result']['verdict'], d['result']['problem_version'], d['result']['run_id'])"
}

section_rejudge() {
	echo "== rejudge (needs the worker with the sandbox)"
	local tt s1 s2 a="SMK${RUN}Q1" b="SMK${RUN}Q2"
	tt=$(teacher_token)
	new_student "$a" "$tt" >/dev/null
	new_student "$b" "$tt" >/dev/null
	s1=$(login "$a" "$SV_PASS")
	s2=$(login "$b" "$SV_PASS")
	make_exam "$tt"
	req POST "/exams/$EXAM_ID/problems" '{"code":"A","title":"Sum","statement":"a+b","time_limit_ms":1000,"memory_limit_mb":256}' "$tt"
	local pid dir
	pid=$(jget 'd["id"]')
	dir="$DATA_HOST_DIR/$(jget 'd["test_dir"]')"
	printf '1 2\n' >"$dir/1.in"
	printf '3\n' >"$dir/1.out"
	printf '10 20\n' >"$dir/2.in"
	printf '30\n' >"$dir/2.out"
	req POST "/problems/$pid/rejudge" "" "$tt"
	req POST "/sessions/$RUNNING_ID/join" "" "$s1"
	req POST "/sessions/$RUNNING_ID/join" "" "$s2"

	# fast: a+b; slow: ~0.3 s of CPU per test, then a+b.
	printf '#include <cstdio>\nint main(){long long a,b;scanf("%%lld %%lld",&a,&b);printf("%%lld\\n",a+b);}\n' >"$TMP/fast.cpp"
	printf '#include <cstdio>\nint main(){long long a,b;scanf("%%lld %%lld",&a,&b);volatile unsigned long x=0;for(unsigned long i=0;i<400000000UL;i++)x+=i;printf("%%lld\\n",a+b);}\n' >"$TMP/slow.cpp"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/fast.cpp" "$s1"
	local f
	f=$(jget 'd["id"]')
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/slow.cpp" "$s2"
	local sl
	sl=$(jget 'd["id"]')
	local rf rs
	rf=$(result_of "$f" "$s1")
	rs=$(result_of "$sl" "$s2")
	STATUS=200 BODY="{\"fast\":\"$rf\",\"slow\":\"$rs\"}"
	expect "both AC at v2" 200 'd["fast"].startswith("2/2 AC@v2") and d["slow"].startswith("2/2 AC@v2")'

	# The teacher edits an expected output on the server, then presses rejudge.
	printf '31\n' >"$dir/2.out"
	req POST "/problems/$pid/rejudge" "" "$tt"
	expect "rejudge requeues both submissions" 200 'd["problem"]["version"] == 3 and d["requeued_submissions"] == 2'
	req GET "/submissions/$f" "" "$s1"
	# Current result is the v3 run (queued or already judged), never the v2 AC.
	expect "current result is the new version, not the old AC" 200 'd["result"]["problem_version"] == 3'
	rf=$(result_of "$f" "$s1")
	rs=$(result_of "$sl" "$s2")
	STATUS=200 BODY="{\"fast\":\"$rf\",\"slow\":\"$rs\"}"
	expect "AC lost after test change: 1/2 WA at v3" 200 'd["fast"].startswith("1/2 WA@v3") and d["slow"].startswith("1/2 WA@v3")'

	printf '30\n' >"$dir/2.out"
	req POST "/problems/$pid/rejudge" "" "$tt"
	rf=$(result_of "$f" "$s1")
	STATUS=200 BODY="{\"fast\":\"$rf\"}"
	expect "test fixed: AC again at v4" 200 'd["fast"].startswith("2/2 AC@v4")'

	# Lower time limit: requeue; the slow program now exceeds it.
	req PUT "/problems/$pid" '{"code":"A","title":"Sum","statement":"a+b","time_limit_ms":100,"memory_limit_mb":256}' "$tt"
	expect "limit change requeues" 200 'd["problem"]["version"] == 5 and d["rejudge_queued"] and d["requeued_submissions"] == 2'
	rf=$(result_of "$f" "$s1")
	rs=$(result_of "$sl" "$s2")
	STATUS=200 BODY="{\"fast\":\"$rf\",\"slow\":\"$rs\"}"
	expect "100 ms limit: fast AC, slow 0/2 TLE" 200 'd["fast"].startswith("2/2 AC@v5") and d["slow"].startswith("0/2 TLE@v5")'

	# Manual rejudge of one submission: new run, same version, other untouched.
	local slow_run=${rs##*run=} fast_run=${rf##*run=}
	req POST "/submissions/$f/rejudge" "" "$tt"
	expect "manual rejudge accepted" 202 "d['result']['status'] == 'queued' and d['result']['problem_version'] == 5 and d['result']['run_id'] != $fast_run"
	req POST "/submissions/$f/rejudge" "" "$s1"
	expect "student cannot rejudge" 403
	rf=$(result_of "$f" "$s1")
	req GET "/submissions/$sl" "" "$s2"
	STATUS=200 BODY="{\"fast\":\"$rf\",\"slow_run\":$(jget 'd["result"]["run_id"]')}"
	expect "only that submission was rejudged" 200 "d['fast'].startswith('2/2 AC@v5') and d['slow_run'] == $slow_run"
}

# handshake ORIGIN -> HTTP status of a WebSocket upgrade with that Origin
handshake() {
	curl -s -o /dev/null -w '%{http_code}' --max-time 2 -H "Origin: $1" -H "Connection: Upgrade" -H "Upgrade: websocket" \
		-H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" "$API/ws" || true
}

section_ws() {
	echo "== ws (WebSocket; needs the worker with the sandbox)"
	local probe=$BACKEND_DIR/bin/wsprobe
	[[ -x $probe ]] || sh "$BACKEND_DIR/scripts/build-java.sh" || {
		bad "build Java wsprobe"
		return
	}
	local tt s1 a="SMK${RUN}W1" b="SMK${RUN}W2"
	tt=$(teacher_token)
	new_student "$a" "$tt" >/dev/null
	new_student "$b" "$tt" >/dev/null
	s1=$(login "$a" "$SV_PASS")
	make_exam "$tt"
	local pid
	pid=$(make_problem "$tt" A)
	req POST "/sessions/$RUNNING_ID/join" "" "$s1"
	req POST "/sessions/$RUNNING_ID/join" "" "$(login "$b" "$SV_PASS")"

	STATUS=$(handshake "http://evil.example")
	BODY='{}'
	expect "foreign Origin rejected" 403
	local host=${BASE#http://}
	STATUS=$(handshake "http://$host")
	expect "same Origin upgrades" 101

	"$probe" -base "$BASE" -code "$a" -password "$SV_PASS" -session "$RUNNING_ID" -duration 25s >"$TMP/w1.log" 2>&1 &
	local p1=$!
	"$probe" -base "$BASE" -code "$b" -password "$SV_PASS" -session "$RUNNING_ID" -duration 25s >"$TMP/w2.log" 2>&1 &
	local p2=$!
	WSPROBE_PASSWORD=$TEACHER_PASSWORD "$probe" -base "$BASE" -code "$TEACHER_CODE" -session "$RUNNING_ID" -duration 25s >"$TMP/wt.log" 2>&1 &
	local pt=$!
	sleep 1.5

	req GET "/sessions/$RUNNING_ID/summary" "" "$tt"
	expect "REST board: 2 students online" 200 'd["online_count"] == 2 and d["problems_total"] == 1 and len(d["rows"]) == 2'

	printf '#include <cstdio>\nint main(){long long a,b;scanf("%%lld %%lld",&a,&b);printf("%%lld\\n",a+b);}\n' >"$TMP/w.cpp"
	upload "/sessions/$RUNNING_ID/problems/$pid/submissions" "$TMP/w.cpp" "$s1"
	local sid
	sid=$(jget 'd["id"]')
	wait_result "$sid" "$s1"
	req POST "/sessions/$RUNNING_ID/announcements" '{"text":"Còn 10 phút"}' "$tt"
	expect "announcement posted" 201
	req PUT "/problems/$pid" '{"code":"A","title":"Sum","statement":"a+b","time_limit_ms":2000,"memory_limit_mb":256}' "$tt"
	sleep 0.3
	wait_result "$sid" "$s1"
	req GET "/sessions/$RUNNING_ID/summary/me" "" "$s1"
	expect "student summary: own row, 1/1 AC" 200 'len(d["rows"]) == 1 and d["rows"][0]["ac_count"] == 1'
	req GET "/sessions/$RUNNING_ID/announcements" "" "$s1"
	expect "announcements over REST (reconnect)" 200 'd["data"][0]["text"] == "Còn 10 phút"'
	sleep 1
	kill "$p1" "$p2" "$pt" 2>/dev/null
	wait "$p1" "$p2" "$pt" 2>/dev/null

	python3 - "$TMP/w1.log" "$TMP/w2.log" "$TMP/wt.log" "$sid" >"$TMP/ws.json" <<'PY'
import json, sys
def msgs(path):
    out = []
    for line in open(path):
        parts = line.split(" ", 1)
        if len(parts) == 2 and parts[1].startswith("{"):
            out.append(json.loads(parts[1]))
    return out
w1, w2, wt, sid = msgs(sys.argv[1]), msgs(sys.argv[2]), msgs(sys.argv[3]), int(sys.argv[4])
runs = [m["data"] for m in w1 if m["type"] == "run.updated" and m["data"]["id"] == sid]
res = [(r["result"] or {}) for r in runs]
v0 = res[0].get("problem_version", 0) if res else 0  # version when submitted
print(json.dumps({
  "w1_types": sorted({m["type"] for m in w1}),
  "w1_statuses": ["%s@v%s" % (r.get("status"), r.get("problem_version")) for r in res],
  "w1_final_v1": any(r.get("status") == "completed" and r.get("verdict") == "AC" and r.get("problem_version") == v0 for r in res),
  "w1_requeued_v2": any(r.get("status") == "queued" and r.get("problem_version") == v0 + 1 for r in res),
  "w1_final_v2": any(r.get("status") == "completed" and r.get("verdict") == "AC" and r.get("problem_version") == v0 + 1 for r in res),
  "w1_announcement": any(m["type"] == "announcement" and m["data"]["text"] == "Còn 10 phút" for m in w1),
  "w2_runs": sum(1 for m in w2 if m["type"] == "run.updated"),
  "w2_announcement": any(m["type"] == "announcement" for m in w2),
  "t_runs": sum(1 for m in wt if m["type"] == "run.updated"),
  "t_board_ac": max([r["ac_count"] for m in wt if m["type"] == "summary.updated" for r in m["data"]["rows"]] or [0]),
  "t_board_online": max([m["data"]["online_count"] for m in wt if m["type"] == "summary.updated"] or [0]),
}))
PY
	STATUS=200 BODY=$(cat "$TMP/ws.json")
	expect "student: queued -> judging -> 1/1 AC pushed" 200 'd["w1_final_v1"] and "auth.ok" in d["w1_types"] and "subscribed" in d["w1_types"]'
	expect "student: rejudge pushed as queued at version+1 then AC" 200 'd["w1_requeued_v2"] and d["w1_final_v2"]'
	expect "announcement pushed to both students" 200 'd["w1_announcement"] and d["w2_announcement"]'
	expect "other student got no run.updated" 200 'd["w2_runs"] == 0'
	expect "teacher: run.updated + live board (1 AC, 2 online)" 200 'd["t_runs"] >= 3 and d["t_board_ac"] == 1 and d["t_board_online"] == 2'
	echo "    $BODY"
}

section_summary() {
	echo "== summary (CSV export, queue stats; needs the worker)"
	local tt s1 s2 a="SMK${RUN}V1" b="SMK${RUN}V2"
	tt=$(teacher_token)
	new_student "$a" "$tt" >/dev/null
	new_student "$b" "$tt" >/dev/null
	s1=$(login "$a" "$SV_PASS")
	s2=$(login "$b" "$SV_PASS")
	make_exam "$tt"
	local pa pb
	pa=$(make_problem "$tt" A)
	pb=$(make_problem "$tt" B)
	req POST "/sessions/$RUNNING_ID/join" "" "$s1"
	req POST "/sessions/$RUNNING_ID/join" "" "$s2"
	printf '#include <cstdio>\nint main(){long long a,b;scanf("%%lld %%lld",&a,&b);printf("%%lld\\n",a+b);}\n' >"$TMP/ok.cpp"
	printf '#include <cstdio>\nint main(){puts("0");}\n' >"$TMP/wa.cpp"
	local x y z
	upload "/sessions/$RUNNING_ID/problems/$pa/submissions" "$TMP/ok.cpp" "$s1"
	x=$(jget 'd["id"]')
	upload "/sessions/$RUNNING_ID/problems/$pb/submissions" "$TMP/wa.cpp" "$s1"
	y=$(jget 'd["id"]')
	upload "/sessions/$RUNNING_ID/problems/$pa/submissions" "$TMP/wa.cpp" "$s2"
	z=$(jget 'd["id"]')
	wait_result "$x" "$s1" && wait_result "$y" "$s1" && wait_result "$z" "$s2"

	STATUS=$(curl -s -o "$TMP/board.csv" -D "$TMP/board.h" -w '%{http_code}' -H "Authorization: Bearer $tt" \
		"$API/sessions/$RUNNING_ID/export.csv")
	BODY=$(python3 - "$TMP/board.csv" "$TMP/board.h" "$a" "$b" <<'PY'
import csv, io, json, sys
raw = open(sys.argv[1], "rb").read()
hdr = open(sys.argv[2]).read().lower()
rows = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"))))
by = {r[0]: r for r in rows[1:]}
print(json.dumps({"bom": raw.startswith(b"\xef\xbb\xbf"), "crlf": b"\r\n" in raw,
  "ctype": "text/csv" in hdr, "attach": "attachment" in hdr, "header": rows[0],
  "a": by.get(sys.argv[3]), "b": by.get(sys.argv[4])}))
PY
)
	expect "CSV: BOM, CRLF, text/csv attachment" 200 'd["bom"] and d["crlf"] and d["ctype"] and d["attach"]'
	expect "CSV: header and x/N AC per student" 200 'd["header"] == ["student_code","fullname","status","A","B","ac"] and d["a"][3:] == ["AC","0/1 WA","1/2 AC"] and d["b"][3:] == ["0/1 WA","","0/2 AC"]'
	req GET "/sessions/$RUNNING_ID/export.csv" "" "$s1"
	expect "student cannot export" 403
	req GET "/admin/queue" "" "$tt"
	expect "queue stats" 200 'set(d["jobs"]) >= {"queued","running","done","failed","obsolete"} and d["jobs"]["done"] >= 3 and d["recent_runs"] >= 3 and d["avg_judge_ms"] > 0'
	req GET "/admin/queue" "" "$s1"
	expect "student cannot read queue stats" 403
}

case ${1:-all} in
auth) section_auth ;;
students) section_students ;;
exams) section_exams ;;
problems) section_problems ;;
submissions) section_submissions ;;
judge) section_judge ;;
rejudge) section_rejudge ;;
ws) section_ws ;;
summary) section_summary ;;
all)
	section_auth
	section_students
	section_exams
	section_problems
	section_submissions
	section_judge
	section_rejudge
	section_ws
	section_summary
	;;
*)
	echo "usage: $0 [auth|students|exams|problems|submissions|judge|rejudge|ws|summary|all]" >&2
	exit 2
	;;
esac

echo "== $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
