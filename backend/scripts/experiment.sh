#!/usr/bin/env bash
# Phase 14 experiments against the local Compose stack. Each run reconfigures
# the worker, samples CPU/RAM with `docker stats`, runs bin/loadtest and
# appends one JSON line (load result + resource peaks) to $OUT.
#
#   scripts/experiment.sh [workers|pickup|delivery|restart|all]
#
# Env: STUDENTS (50), TESTS (15), REPS (3), OUT (docs/results/runs.jsonl).
# Needs .env and .smoke.env; leaves the worker back on its defaults.
set -euo pipefail
cd "$(dirname "$0")/.."

STUDENTS=${STUDENTS:-50}
TESTS=${TESTS:-15}
REPS=${REPS:-3}
OUT=${OUT:-docs/results/runs.jsonl}
set -a
. ./.env
. ./.smoke.env
set +a
export LOADTEST_DB="postgres://$POSTGRES_USER:$POSTGRES_PASSWORD@127.0.0.1:${DB_PORT:-5433}/$POSTGRES_DB?sslmode=disable"
mkdir -p "$(dirname "$OUT")" bin
sh scripts/build-java.sh

# worker CONCURRENCY LISTEN [REPLICAS]: restart the worker(s) with a setting
worker() {
	WORKER_CONCURRENCY=$1 WORKER_LISTEN=$2 docker compose up -d --no-deps --scale worker="${3:-1}" worker >/dev/null 2>&1
	sleep 4
}

# measure LABEL ARGS...: loadtest + docker stats sampling
measure() {
	local label=$1
	shift
	local stats
	stats=$(mktemp)
	# shellcheck disable=SC2046
	docker stats --format '{{.Name}};{{.CPUPerc}};{{.MemUsage}}' $(docker compose ps -q) >"$stats" 2>/dev/null &
	local sampler=$!
	local res
	res=$(./bin/loadtest -students "$STUDENTS" -tests "$TESTS" -label "$label" "$@")
	kill "$sampler" 2>/dev/null || true
	wait "$sampler" 2>/dev/null || true
	python3 - "$stats" "$res" >>"$OUT" <<'PY'
import json, re, sys
peak = {}
for line in open(sys.argv[1], errors="ignore"):
    line = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", line).strip()
    parts = line.split(";")
    if len(parts) != 3 or not parts[1].endswith("%"):
        continue
    if not parts[0].startswith("backend-"):
        continue
    name = re.sub(r"^backend-", "", parts[0])  # api-1, worker-1, worker-2, postgres-1
    cpu = float(parts[1].rstrip("%"))
    m = re.match(r"([\d.]+)\s*([KMG]i?B)", parts[2])
    mem = float(m.group(1)) * {"KiB": 1/1024, "MiB": 1, "GiB": 1024, "kB": 1/1000, "MB": 1, "GB": 1000}.get(m.group(2), 1) if m else 0
    p = peak.setdefault(name, {"cpu_peak_pct": 0.0, "mem_peak_mib": 0.0})
    p["cpu_peak_pct"] = max(p["cpu_peak_pct"], cpu)
    p["mem_peak_mib"] = max(p["mem_peak_mib"], round(mem, 1))
r = json.loads(sys.argv[2])
r["resources"] = peak
print(json.dumps(r))
PY
	rm -f "$stats"
	tail -1 "$OUT" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  %-22s e2e p50=%7.0fms p95=%7.0fms makespan=%6.0fms thr=%5.1f/s req=%4d bytes=%7d dbx=%5d verdicts=%s status=%s" % (d["label"], d["submit_to_result"]["p50_ms"], d["submit_to_result"]["p95_ms"], d["makespan_ms"], d["throughput_per_s"], d["wait_phase_requests"], d["wait_phase_bytes"], d.get("db_xact_during_wait",0), d["verdicts"], d["http_status"]))'
}

# E1: judging capacity — 1, 2, 4, 8 slots in one worker, and 2 workers x 4.
exp_workers() {
	echo "== E1 workers (ws, listen)"
	for r in $(seq "$REPS"); do
		for c in 1 2 4 8; do
			worker "$c" true
			measure "workers-c$c" -mode ws
		done
		worker 4 true 2
		measure "workers-2x4" -mode ws
	done
}

# E2: job pickup — polling only (1 s) vs NOTIFY wake-up, 4 slots.
exp_pickup() {
	echo "== E2 pickup (ws, 4 slots)"
	for r in $(seq "$REPS"); do
		worker 4 false
		measure "pickup-poll1s" -mode ws
		worker 4 true
		measure "pickup-notify" -mode ws
	done
}

# E3: result delivery — WebSocket push vs client polling every 1 s / 0.25 s.
exp_delivery() {
	echo "== E3 delivery (4 slots, listen)"
	worker 4 true
	for r in $(seq "$REPS"); do
		measure "delivery-ws" -mode ws
		measure "delivery-poll1s" -mode poll -poll-interval 1s
		measure "delivery-poll250ms" -mode poll -poll-interval 250ms
	done
}

# E4: restart the whole stack while 50 submissions are being judged; clients
# poll through the outage. Every accepted submission must still be judged.
exp_restart() {
	echo "== E4 restart during judging (1 slot, poll)"
	worker 1 true
	(sleep 4 && docker compose restart >/dev/null 2>&1) &
	measure "restart-midway" -mode poll -poll-interval 1s -timeout 5m
	wait
}

case ${1:-all} in
workers) exp_workers ;;
pickup) exp_pickup ;;
delivery) exp_delivery ;;
restart) exp_restart ;;
all)
	exp_workers
	exp_pickup
	exp_delivery
	exp_restart
	;;
*)
	echo "usage: $0 [workers|pickup|delivery|restart|all]" >&2
	exit 2
	;;
esac
worker 2 true # back to the defaults
