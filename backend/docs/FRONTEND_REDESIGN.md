> Ghi chép đợt thiết kế giao diện trước khi chuyển backend sang Java. Hiện Java phục vụ cùng bundle/asset; xem [JAVA-MIGRATION.md](JAVA-MIGRATION.md) cho lệnh build hiện tại.

# Full-screen LAN Judge frontend

## Existing application and preserved behavior

The inspected repository uses a Go-embedded vanilla JavaScript UI. React now
owns the application shell, login/password forms, student session browser and
coding workspace. Teacher pages retain their existing handlers inside an
isolated React adapter. All existing hash routes remain available.

The original REST client, authentication/refresh behavior, WebSocket module,
screen-sharing enforcement and WebRTC signaling remain shared browser modules
at `/assets/`. The backend, database schemas and API contracts are unchanged.
Go/Docker serves a checked-in production bundle and local fonts without Node or
internet access at runtime.

## Design and layout

- Dark is the default: graphite surfaces, blue focus/action states, restrained
  success/warning/error colors; a complete light theme is available.
- IBM Plex Sans includes a self-hosted Vietnamese subset; JetBrains Mono is used
  for code and technical information. Font licenses are included with assets.
- The app is `100dvh` and full width, without a global maximum width. The desktop
  workspace uses all available height, edge-to-edge panels and independent scroll.
- Both splitters support pointer dragging and keyboard resizing. Pane sizes and
  the 12–18 px editor font setting persist locally. Results can collapse, and a
  focus mode gives the editor the full available width.
- Small screens switch between the problem and solution views, preserving a
  full-width editor rather than shrinking two columns.
- Administrative forms, tables, dialogs, live board, supervision, queue and
  submission details use the same tokens and compact type hierarchy.

## Feature boundaries

The API supports C++17 submissions and judging, not a separate Run endpoint or
additional languages. The UI keeps the real Submit action and a C++17 label.
There are no invented difficulty levels, public catalogue, registration or
profile/progress data. Per-test timing and memory remain teacher-only; students
see the real aggregate verdict, compiler output and submission metadata.
Hidden inputs/expected outputs are not exposed. The original 64 KB upload limit,
file import, indentation, independent drafts, finish action, announcements and
live/reconnection behavior are preserved.

## Run and build

```bash
npm ci
npm run build
```

Then rebuild/restart the Go API to include the changed embedded assets, for
example `docker compose up -d --build --no-deps api` with an existing configured
stack. Node is only needed when editing the React source.

`npm run dev` watches React source, serves the UI on `http://127.0.0.1:5173`, and
proxies real REST/WebSocket traffic to `http://127.0.0.1:8080`. Set `BACKEND_URL`
and `PORT` if needed. Reload the page after source changes. The Go API must be
running. The proxy preserves the original Host/Origin relationship for sockets.

For an isolated visual preview without a backend:

```bash
npm run preview:ui
```

This explicitly uses **test fixtures**, with `SV001 / demo` and `GV001 / demo`.
Results are simulated only inside that test server; it does not compile C++.
Neither the real app nor the development proxy imports the fixture server.

## Verification

`npm run test:ui` needs Node 22+ and Chrome. It uses isolated API fixtures and test-only screen
capture. It covers authentication/errors, independent drafts, both splitters,
saved pane/font settings, result collapse, all six verdicts, compile diagnostics,
history/source preview, focus mode, sharing lock/relock, finish, password
validation and all teacher routes. It checks viewport fit and full width at
1366×768, 1440×900, 1920×1080, 2560×1080, 768×1024 and 390×844. Screenshots are
saved to `artifacts/ui/` (ignored).

The current browser suite passes 71 checks. The 16 secondary/status/code text
token combinations checked across both themes exceed 4.5:1 contrast. Additional
checks with an isolated PostgreSQL database and the unmodified Go API verify
actual login, session loading, WebSocket authentication, multipart upload,
persisted source, queued status, teacher announcements, history and finishing.
The development proxy also passes real HTTP and WebSocket authentication checks.
Real C++ execution is not simulated by these API checks; without a Linux worker
the uploaded submission stays queued.

The relevant native Go package tests and `GOOS=linux GOARCH=amd64 go vet ./...`
pass. Full native `make test` on macOS is constrained by the original Linux
sandbox syscalls; actual C++ judging needs the Linux worker stack. The existing
`scripts/ui-e2e/screen-share.mjs` remains available to check real peer-to-peer
screen supervision against the running stack.
