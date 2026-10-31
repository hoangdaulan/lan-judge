.PHONY: up down migrate seed build test test-integration test-sandbox smoke experiment backup data ui-build ui-test
ui-build:
	npm run build
ui-test:
	npm run test:ui
data:
	mkdir -p data
up: data
	docker compose up -d --build
down:
	docker compose down
ARGS ?= up
migrate:
	docker compose run --rm --entrypoint /app/migrate api $(ARGS)
seed:
	docker compose run --rm -e SEED_TEACHER_CODE -e SEED_TEACHER_FULLNAME -e SEED_TEACHER_PASSWORD --entrypoint /app/seed api
build:
	sh scripts/build-java.sh
test:
	./mvnw -q test
test-integration:
	set -a && . ./.env && set +a && TEST_DATABASE_URL="postgres://$$POSTGRES_USER:$$POSTGRES_PASSWORD@127.0.0.1:$${DB_PORT:-5433}/$$POSTGRES_DB?sslmode=disable" ./mvnw -q -Dtest=DatabaseTest test
test-sandbox:
	docker build --target sandbox-test -t lan-judge-sandbox-test .
	docker run --rm --network none --cap-drop ALL --cap-add SYS_ADMIN --cap-add SETUID --cap-add SETGID --cap-add CHOWN --cap-add FOWNER --cap-add DAC_OVERRIDE --cap-add KILL --security-opt no-new-privileges:true lan-judge-sandbox-test
smoke: build
	bash scripts/smoke.sh all
experiment: build
	bash scripts/experiment.sh all
	python3 scripts/results.py
backup:
	bash scripts/backup.sh
