// Isolated test data. Production and the development API proxy never import it.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve, extname } from "node:path";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
const stamp = () => new Date().toISOString();
const at = (minutes) => new Date(Date.now() + minutes * 60000).toISOString();
const staticDir = fileURLToPath(new URL("../../web/static", import.meta.url));
const student = {
  id: 2,
  code: "SV001",
  fullname: "Nguyễn Hoàng An",
  role: "student",
};
const teacher = {
  id: 1,
  code: "GV001",
  fullname: "Nguyễn Minh Anh",
  role: "teacher",
};
const secondStudent = { id: 3, code: "SV002", fullname: "Trần Minh Khôi", role: "student" };
const result = (status, verdict = null, id = 1) => ({
  run_id: id,
  problem_version: 1,
  status,
  verdict,
  total_tests: 15,
  passed_tests: verdict === "AC" ? 15 : verdict === "CE" ? 0 : 7,
  compiler_info: "g++ (Debian 12.2.0) 12.2.0",
  compile_output:
    verdict === "CE"
      ? "solution.cpp:5:5: error: expected semicolon before return\n    5 |     return 0;\n      |     ^~~~~~"
      : null,
  finished_at: status === "completed" ? stamp() : null,
});

export async function startFixtures(port = 0) {
  let nextId = 120,
    submissions = [],
    announcements = [],
    participants = [];
  const calls = [],
    sockets = new Set();
  const exams = [
    {
      id: 1,
      title: "Cấu trúc dữ liệu và Giải thuật",
      description: "Kiểm tra giữa kỳ · Học kỳ I, 2026–2027",
      updated_at: stamp(),
    },
    {
      id: 2,
      title: "Lập trình C++ cơ bản",
      description: "Kiểu dữ liệu, cấu trúc điều khiển và hàm",
      updated_at: at(-2000),
    },
    {
      id: 3,
      title: "Thuật toán trên đồ thị",
      description: "Duyệt đồ thị và đường đi ngắn nhất",
      updated_at: at(-3000),
    },
  ];
  const problems = [
    {
      id: 1,
      code: "A",
      title: "Tìm cặp số có tổng bằng K",
      time_limit_ms: 1000,
      memory_limit_mb: 256,
      version: 1,
      test_dir: "tests/1",
      statement:
        "Cho một dãy gồm n số nguyên a₁, a₂, …, aₙ và một số nguyên K.\nHãy tìm hai phần tử ở hai vị trí khác nhau sao cho tổng của chúng bằng K.\n\nDữ liệu vào\nDòng đầu chứa hai số nguyên n và K.\nDòng thứ hai chứa n số nguyên của dãy a.\n\nDữ liệu ra\nIn ra hai chỉ số i và j (i < j) thỏa mãn yêu cầu.\nNếu không tồn tại, in ra -1.\n\nGiới hạn\n2 ≤ n ≤ 10⁵\n−10⁹ ≤ aᵢ, K ≤ 10⁹\n\nVí dụ\nInput\n5 9\n2 7 11 15 3\n\nOutput\n1 2\n\nGiải thích\nHai phần tử tại vị trí 1 và 2 có tổng 2 + 7 = 9.",
    },
    {
      id: 2,
      code: "B",
      title: "Dãy con tăng dài nhất",
      time_limit_ms: 2000,
      memory_limit_mb: 256,
      version: 1,
      test_dir: "tests/2",
      statement:
        "Cho n số nguyên. Hãy tính độ dài dãy con tăng dài nhất.\n\nDữ liệu vào\nSố nguyên n và dãy số.\n\nDữ liệu ra\nĐộ dài dãy con tăng dài nhất.",
    },
    {
      id: 3,
      code: "C",
      title: "Đường đi ngắn nhất",
      time_limit_ms: 1000,
      memory_limit_mb: 128,
      version: 1,
      test_dir: "tests/3",
      statement: "Tìm đường đi ngắn nhất từ đỉnh s đến đỉnh t.",
    },
  ];
  const sessions = [
    {
      id: 1,
      exam_id: 1,
      exam_title: exams[0].title,
      name: "Kiểm tra giữa kỳ · Ca 01",
      starts_at: at(-20),
      ends_at: at(85),
      state: "running",
      participation: null,
    },
    {
      id: 2,
      exam_id: 1,
      exam_title: exams[0].title,
      name: "Kiểm tra giữa kỳ · Ca 02",
      starts_at: at(1440),
      ends_at: at(1560),
      state: "upcoming",
      participation: null,
    },
    {
      id: 3,
      exam_id: 2,
      exam_title: exams[1].title,
      name: "Luyện tập tuần 05",
      starts_at: at(-3000),
      ends_at: at(-2900),
      state: "ended",
      participation: { student_id: 2, status: "finished" },
    },
    {
      id: 4,
      exam_id: 3,
      exam_title: exams[2].title,
      name: "Luyện tập tuần 06",
      starts_at: at(2880),
      ends_at: at(3000),
      state: "upcoming",
      participation: null,
    },
    {
      id: 5,
      exam_id: 2,
      exam_title: exams[1].title,
      name: "Bài tập vòng lặp và hàm",
      starts_at: at(-5800),
      ends_at: at(-5680),
      state: "ended",
      participation: { student_id: 2, status: "finished" },
    },
  ];
  const paged = (data) => ({ data, page: 1, pageSize: 200, totalPages: 1 });
  const summary = () => ({
    session_id: 1,
    generated_at: stamp(),
    problems,
    problems_total: 3,
    online_count: 1,
    queue: { queued: 0, running: 0 },
    rows: [
      {
        student_id: 2,
        student_code: student.code,
        student_fullname: student.fullname,
        status: "working",
        online: true,
        ac_count: submissions.some((x) => x.result.verdict === "AC") ? 1 : 0,
        cells: problems.map((p) => {
          const x = submissions.find((s) => s.problem_id === p.id);
          return {
            problem_id: p.id,
            submissions: x ? 1 : 0,
            state: !x
              ? "none"
              : x.result.status !== "completed"
                ? "pending"
                : x.result.verdict === "AC"
                  ? "ac"
                  : "attempted",
            latest: x?.result,
          };
        }),
      },
    ],
  });
  const broadcast = (type, data) => {
    for (const socket of sockets)
      if (!socket.destroyed)
        socket.write(frame(JSON.stringify({ type, data })));
  };
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, "http://localhost"),
      path = url.pathname;
    const reply = (status, data, type = "application/json") => {
      res.writeHead(status, {
        "Content-Type": type,
        "Cache-Control": "no-store",
      });
      res.end(
        status === 204
          ? ""
          : type === "application/json"
            ? JSON.stringify(data)
            : data,
      );
    };
    try {
      if (!path.startsWith("/api/")) {
        const file = resolve(
          staticDir,
          path === "/" ? "index.html" : path.replace(/^\/assets\//, ""),
        );
        if (!file.startsWith(staticDir + "/")) return reply(404, {});
        const content = await readFile(file),
          mime = {
            ".html": "text/html",
            ".js": "text/javascript",
            ".css": "text/css",
            ".woff2": "font/woff2",
            ".svg": "image/svg+xml",
          };
        return reply(
          200,
          content,
          mime[extname(file)] || "application/octet-stream",
        );
      }
      let raw = "";
      for await (const chunk of req) raw += chunk;
      let body = {};
      try {
        body = JSON.parse(raw);
      } catch {}
      calls.push({ method: req.method, path, query: url.search, body, raw });
      const p = path.replace("/api/v1", ""),
        isTeacher = req.headers.authorization === "Bearer teacher";
      if (p === "/auth/login" || p === "/auth/refresh") {
        if (p === "/auth/login" && body.password === "wrong")
          return reply(401, { error: "invalid code or password" });
        const t =
          body.code?.startsWith("GV") || body.refresh_token === "teacher";
        const second = body.code === "SV002" || body.refresh_token === "student2";
        const token = t ? "teacher" : second ? "student2" : "student";
        return reply(200, {
          user: t ? teacher : second ? secondStudent : student,
          access_token: token,
          refresh_token: token,
          access_expires_at: at(15),
        });
      }
      if (p === "/auth/logout" || p === "/me/password") return reply(204, null);
      if (p === "/me/sessions")
        return reply(200, { ...paged(sessions), server_time: stamp() });
      if (p === "/exams") {
        if (req.method === "POST") {
          const e = { ...body, id: exams.length + 1, updated_at: stamp() };
          exams.push(e);
          return reply(201, e);
        }
        return reply(200, paged(exams));
      }
      if (/^\/exams\/\d+$/.test(p))
        return reply(200, { ...exams[0], problems });
      if (/^\/exams\/\d+\/problems$/.test(p))
        return reply(200, paged(problems));
      if (/^\/exams\/\d+\/sessions$/.test(p))
        return reply(200, paged(sessions));
      if (p === "/students") {
        if (req.method === "POST") return reply(201, body);
        return reply(
          200,
          paged(
            [
              student,
              { id: 3, code: "SV002", fullname: "Trần Minh Khôi" },
              { id: 4, code: "SV003", fullname: "Lê Thảo Nguyên" },
            ]
              .filter((x) =>
                `${x.code} ${x.fullname}`.includes(
                  url.searchParams.get("query") || "",
                ),
              )
              .map((x) => ({ ...x, created_at: at(-4000) })),
          ),
        );
      }
      if (/reset-password$/.test(p)) return reply(204, null);
      if (/^\/problems\/\d+\/tests$/.test(p))
        return reply(200, {
          ...paged([
            { input: "1.in", output: "1.out" },
            { input: "2.in", output: "2.out" },
          ]),
          test_dir: "tests/1",
          disk: { in_sync: true, tests: 2 },
        });
      if (/^\/sessions\/\d+$/.test(p)) {
        const session = sessions.find((s) => s.id === Number(p.split("/")[2]));
        if (req.method === "PUT") {
          Object.assign(session, body);
          session.state = Date.now() < Date.parse(session.starts_at) ? "upcoming" : Date.now() < Date.parse(session.ends_at) ? "running" : "ended";
          broadcast("session.updated", { session_id: session.id });
          return reply(200, session);
        }
        return reply(200, {
          session,
          participation: session.participation,
          problems,
          server_time: stamp(),
        });
      }
      if (/\/join$/.test(p)) {
        const session = sessions.find((s) => s.id === Number(p.split("/")[2]));
        session.participation = {
          id: 1,
          student_id: 2,
          student_code: student.code,
          student_fullname: student.fullname,
          status: "working",
          joined_at: stamp(),
        };
        participants = [session.participation];
        return reply(200, session.participation);
      }
      if (/\/finish$/.test(p)) {
        const session = sessions.find((s) => s.id === Number(p.split("/")[2]));
        session.participation.status = "finished";
        return reply(200, session.participation);
      }
      if (/\/participants$/.test(p)) return reply(200, paged(participants));
      if (/\/summary(?:\/me)?$/.test(p)) return reply(200, summary());
      if (/\/announcements$/.test(p)) {
        if (req.method === "POST") {
          const a = { ...body, session_id: 1, created_at: stamp() };
          announcements.unshift(a);
          broadcast("announcement", a);
          return reply(201, a);
        }
        return reply(200, paged(announcements));
      }
      if (/^\/sessions\/\d+\/problems\/\d+$/.test(p))
        return reply(
          200,
          problems.find((x) => x.id === Number(p.split("/")[4])) || problems[0],
        );
      if (/^\/sessions\/\d+\/problems\/\d+\/submissions$/.test(p)) {
        const verdict =
          raw.match(/TEST_VERDICT:(AC|WA|TLE|MLE|RE|CE)/)?.[1] || "AC";
        const sub = {
          id: ++nextId,
          session_id: Number(p.split("/")[2]),
          problem_id: Number(p.split("/")[4]),
          problem_code: "A",
          problem_version: 1,
          student_id: 2,
          student_code: student.code,
          student_fullname: student.fullname,
          received_at: stamp(),
          result: result("queued", null, nextId),
        };
        submissions.unshift(sub);
        reply(202, sub);
        setTimeout(() => {
          sub.result = result("judging", null, sub.id);
          broadcast("run.updated", sub);
        }, 180);
        setTimeout(() => {
          sub.result = result("completed", verdict, sub.id);
          broadcast("run.updated", sub);
          broadcast("summary.updated", summary());
        }, 750);
        return;
      }
      if (/^\/sessions\/\d+\/submissions$/.test(p))
        return reply(
          200,
          paged(
            submissions.filter(
              (x) =>
                !url.searchParams.get("problem_id") ||
                x.problem_id === Number(url.searchParams.get("problem_id")),
            ),
          ),
        );
      if (/^\/submissions\/\d+\/source$/.test(p))
        return reply(
          200,
          "#include <iostream>\nint main(){ return 0; }",
          "text/plain",
        );
      if (/^\/submissions\/\d+\/tests$/.test(p))
        return isTeacher
          ? reply(
              200,
              paged([
                {
                  test_case_id: 1,
                  verdict: "AC",
                  execution_time_ms: 12,
                  memory_used_kb: 1240,
                },
                {
                  test_case_id: 2,
                  verdict: "AC",
                  execution_time_ms: 16,
                  memory_used_kb: 1320,
                },
              ]),
            )
          : reply(403, { error: "forbidden" });
      if (/^\/submissions\/\d+$/.test(p))
        return reply(
          200,
          submissions.find((x) => x.id === Number(p.split("/")[2])) ||
            submissions[0],
        );
      if (/\/export.csv$/.test(p))
        return reply(200, "code,name,AC\nSV001,An,1", "text/csv");
      if (p === "/admin/queue")
        return reply(200, {
          jobs: { queued: 2, running: 1, done: 128, failed: 0, obsolete: 3 },
          active_workers: ["worker-01"],
          oldest_queued_ms: 120,
          recent_runs: 24,
          avg_queue_wait_ms: 87,
          avg_judge_ms: 214,
          generated_at: stamp(),
        });
      if (req.method !== "GET") return reply(200, {});
      reply(404, { error: `Missing test fixture ${p}` });
    } catch (e) {
      reply(500, { error: e.message });
    }
  });
  server.on("upgrade", (req, socket) => {
    socket.write(
      "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " +
        createHash("sha1")
          .update(
            req.headers["sec-websocket-key"] +
              "258EAFA5-E914-47DA-95CA-C5AB0DC85B11",
          )
          .digest("base64") +
        "\r\n\r\n",
    );
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
    socket.on("error", () => sockets.delete(socket));
    let pending = Buffer.alloc(0);
    socket.on("data", (chunk) => {
      pending = Buffer.concat([pending, chunk]);
      while (pending.length >= 6) {
        let offset = 2,
          len = pending[1] & 127;
        if (len === 126) {
          if (pending.length < 8) return;
          len = pending.readUInt16BE(2);
          offset = 4;
        }
        if (len === 127 || pending.length < offset + 4 + len) return;
        const mask = pending.subarray(offset, offset + 4);
        offset += 4;
        const data = Buffer.from(pending.subarray(offset, offset + len));
        for (let i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
        pending = pending.subarray(offset + len);
        try {
          const msg = JSON.parse(data.toString());
          if (msg.type === "auth")
            socket.write(frame(JSON.stringify({ type: "auth.ok", data: {} })));
          if (msg.type === "subscribe") {
            socket.write(
              frame(
                JSON.stringify({
                  type: "subscribed",
                  data: { session_id: msg.data.session_id },
                }),
              ),
            );
            socket.write(
              frame(
                JSON.stringify({
                  type: "screen.list",
                  data: {
                    session_id: 1,
                    students: [{ student_id: 2, sharing: true }],
                  },
                }),
              ),
            );
          }
          if (msg.type === "screen.state")
            socket.write(frame(JSON.stringify({ type: "screen.state", data: { session_id: 1, sharing: msg.data.sharing } })));
        } catch {}
      }
    });
  });
  await new Promise((ok) => server.listen(port, "127.0.0.1", ok));
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    calls,
    broadcast,
    stop: async () => {
      for (const s of sockets) s.destroy();
      await new Promise((ok) => server.close(ok));
    },
  };
}
function frame(text) {
  const data = Buffer.from(text),
    header = Buffer.alloc(data.length < 126 ? 2 : 4);
  header[0] = 0x81;
  if (data.length < 126) header[1] = data.length;
  else {
    header[1] = 126;
    header.writeUInt16BE(data.length, 2);
  }
  return Buffer.concat([header, data]);
}
