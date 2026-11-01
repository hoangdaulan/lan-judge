import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  auth,
  login,
  logout,
  resume,
  onAuthChange,
  put,
} from "../static/api.js";
import * as live from "../static/live.js";
import * as screen from "../static/screen.js";
import { teacherRoutes, teacherNav } from "../static/teacher.js";
import { errText, toast, html, modal } from "../static/ui.js";
import { Brand, Icon, Loading } from "./components.jsx";
import { Sessions } from "./sessions.jsx";
import { Room } from "./workspace.jsx";

function readTheme() {
  try {
    return localStorage.getItem("lanjudge.theme") === "light"
      ? "light"
      : "dark";
  } catch {
    return "dark";
  }
}
document.documentElement.dataset.theme = readTheme();

function App() {
  const [user, setUser] = useState(auth.user),
    [ready, setReady] = useState(false);
  const [path, setPath] = useState(location.hash.slice(1) || "/"),
    [theme, setTheme] = useState(readTheme),
    [menu, setMenu] = useState(false);
  const account = useRef(null),
    intentionalLogout = useRef(false);
  useEffect(() => {
    const off = onAuthChange(() => {
      if (user && !auth.user && !intentionalLogout.current)
        toast("Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.", "error");
      setUser(auth.user);
    });
    return off;
  }, [user]);
  useEffect(() => {
    resume().finally(() => setReady(true));
    const change = () => {
      setPath(location.hash.slice(1) || "/");
      setMenu(false);
    };
    window.addEventListener("hashchange", change);
    return () => window.removeEventListener("hashchange", change);
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem("lanjudge.theme", theme);
    } catch {}
    document.querySelector('meta[name="theme-color"]').content =
      theme === "dark" ? "#0b0f14" : "#f4f6f9";
  }, [theme]);
  useEffect(() => {
    if (!menu) return;
    const close = (e) => {
      if (
        e.key === "Escape" ||
        (e.type === "pointerdown" && !account.current?.contains(e.target))
      )
        setMenu(false);
    };
    document.addEventListener("pointerdown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("pointerdown", close);
      document.removeEventListener("keydown", close);
    };
  }, [menu]);
  const current = path.split("?")[0],
    home = user?.role === "teacher" ? "/exams" : "/sessions";
  const room = user?.role === "student" && /^\/sessions\/\d+/.test(current);
  useEffect(() => {
    if (user && current === "/") location.replace("#" + home);
    if (!user || (user.role === "student" && !room)) {
      live.stop();
      screen.stopShare();
    }
    document.title = `${room ? "Không gian làm bài" : user ? "Không gian lập trình" : "Đăng nhập"} · LAN Judge`;
  }, [user, current, room, home]);
  const signOut = async () => {
    intentionalLogout.current = true;
    await logout();
    location.hash = "#/";
    intentionalLogout.current = false;
  };
  const help = () =>
    modal(
      html`<h2>Phím tắt &amp; hướng dẫn</h2>
        <dl class="kv">
          <dt>⌘ / Ctrl + Enter</dt>
          <dd>Nộp mã nguồn trong trình soạn thảo</dd>
          <dt>Tab / Shift + Tab</dt>
          <dd>Thụt / giảm thụt dòng</dd>
          <dt>Ngoặc và dấu nháy</dt>
          <dd>Tự đóng cặp; bọc phần mã đang chọn. Enter tự thụt lề.</dd>
          <dt>Escape trong editor</dt>
          <dd>Chuyển đến thanh công cụ</dd>
          <dt>← / → trên tab</dt>
          <dd>Chuyển tab</dd>
          <dt>← / →, ↑ / ↓ ở thanh chia</dt>
          <dd>Điều chỉnh kích thước panel</dd>
        </dl>
        <p class="muted">
          Bản nháp được lưu trên trình duyệt hiện tại. Phiên đang diễn ra yêu
          cầu chia sẻ toàn màn hình.
        </p>`,
    );
  return (
    <div
      className={`app-shell ${room ? "is-workspace" : ""} ${!user ? "is-auth" : ""}`}
    >
      <a
        className="skip-link"
        href="#main-content"
        onClick={(e) => {
          e.preventDefault();
          document.getElementById("main-content")?.focus();
        }}
      >
        Đến nội dung chính
      </a>
      <header id="top">
        <Brand />
        <span className="header-divider" />
        {!user && (
          <span className="shell-context">Code evaluation platform</span>
        )}
        {user && (
          <nav id="nav" aria-label="Điều hướng chính">
            {(user.role === "teacher"
              ? teacherNav
              : [["/sessions", "Phiên thi"]]
            ).map(([href, label]) => (
              <a
                key={href}
                href={"#" + href}
                className={
                  current.startsWith(href) || (room && href === home)
                    ? "active"
                    : ""
                }
              >
                {label}
              </a>
            ))}
          </nav>
        )}
        <div className="header-actions">
          <span id="conn" className="conn" hidden />
          <button
            className="icon-button"
            onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
            aria-label={
              theme === "dark"
                ? "Chuyển sang chế độ sáng"
                : "Chuyển sang chế độ tối"
            }
            title={theme === "dark" ? "Chế độ sáng" : "Chế độ tối"}
          >
            <Icon name={theme === "dark" ? "sun" : "moon"} />
          </button>
          {user && (
            <>
              <button
                className="icon-button help-button"
                onClick={help}
                aria-label="Hướng dẫn và phím tắt"
                title="Hướng dẫn và phím tắt"
              >
                <Icon name="help" />
              </button>
              <div className="account" ref={account}>
                <button
                  id="who"
                  className="account-trigger"
                  onClick={() => setMenu(!menu)}
                  aria-expanded={menu}
                  aria-label="Mở menu tài khoản"
                >
                  <span className="avatar">
                    {user.fullname?.trim().split(/\s+/).pop()?.slice(0, 1) ||
                      "U"}
                  </span>
                  <Icon name="down" size={12} />
                </button>
                {menu && (
                  <div className="account-menu">
                    <div>
                      <strong>{user.fullname}</strong>
                      <span className="muted">
                        {user.code} ·{" "}
                        {user.role === "teacher" ? "Giáo viên" : "Sinh viên"}
                      </span>
                    </div>
                    <a href="#/password">
                      <Icon name="settings" />
                      Đổi mật khẩu
                    </a>
                    <button id="logout" onClick={signOut}>
                      <Icon name="logout" />
                      Đăng xuất
                    </button>
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </header>
      <main
        id="main-content"
        tabIndex="-1"
        className={!user ? "auth-main" : room ? "workspace-main" : "page-main"}
      >
        {!ready ? (
          <Loading />
        ) : !user ? (
          <Login />
        ) : current === "/password" ? (
          <Password signOut={signOut} />
        ) : user.role === "teacher" ? (
          <TeacherPage key={path} path={path} />
        ) : current === "/sessions" || current === "/" ? (
          <Sessions />
        ) : /^\/sessions\/(\d+)(?:\/(\d+))?$/.test(current) ? (
          <Room
            key={current}
            ids={current.match(/^\/sessions\/(\d+)(?:\/(\d+))?$/).slice(1)}
          />
        ) : (
          <Unknown home={home} />
        )}
      </main>
      {!room && user && (
        <footer className="global-status">
          <span>
            <span className="status-dot" />
            LAN Judge
          </span>
          <span>
            C++17 <span className="status-divider">/</span> Không gian{" "}
            {user.role === "teacher" ? "giảng dạy" : "lập trình"}
          </span>
        </footer>
      )}
    </div>
  );
}
function Unknown({ home }) {
  useEffect(() => {
    location.replace("#" + home);
  }, [home]);
  return <Loading />;
}

function TeacherPage({ path }) {
  const ref = useRef(null);
  useEffect(() => {
    const root = ref.current,
      cleanups = [];
    let disposed = false;
    const ctx = {
      onCleanup(fn) {
        if (disposed) fn();
        else cleanups.push(fn);
      },
      every(ms, fn) {
        if (disposed) return;
        const t = setInterval(fn, ms);
        cleanups.push(() => clearInterval(t));
      },
      on(type, fn) {
        if (!disposed) cleanups.push(live.on(type, fn));
      },
    };
    const current = path.split("?")[0],
      match = teacherRoutes.find(([re]) => re.test(current));
    if (match) {
      root.innerHTML =
        '<div class="skeleton" role="status" aria-label="Đang tải"><span></span><span></span></div>';
      Promise.resolve(
        match[1](root, current.match(match[0]).slice(1), ctx),
      ).catch((e) => {
        if (!disposed) {
          root.replaceChildren();
          const out = document.createElement("div");
          out.className = "error-state";
          out.textContent = errText(e);
          root.append(out);
        }
      });
    } else location.replace("#/exams");
    return () => {
      disposed = true;
      cleanups.forEach((f) => {
        try {
          f();
        } catch {}
      });
    };
  }, [path]);
  return <div className="teacher-page" ref={ref} />;
}

function Login() {
  const [pending, setPending] = useState(false),
    [error, setError] = useState(""),
    [visible, setVisible] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    const f = e.currentTarget;
    setPending(true);
    setError("");
    try {
      await login(f.code.value.trim(), f.password.value);
      location.hash = auth.user.role === "teacher" ? "#/exams" : "#/sessions";
    } catch (e) {
      setError(errText(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <div className="login-layout">
      <section className="login-context">
        <div className="login-context-content">
          <span className="context-tag">
            <Icon name="terminal" size={17} /> Online judge · C++17
          </span>
          <h1>
            Không gian lập trình.
            <br />
            <span>Tập trung vào lời giải.</span>
          </h1>
          <p>
            Đọc đề, viết mã và theo dõi kết quả chấm trong một không gian làm
            việc liền mạch.
          </p>
          <ol className="login-workflow">
            <li>
              <span className="mono">01</span>
              <div>
                <strong>Biên soạn</strong>
                <p>Editor, bản nháp tự động và không gian làm bài riêng.</p>
              </div>
            </li>
            <li>
              <span className="mono">02</span>
              <div>
                <strong>Nộp bài</strong>
                <p>Mã nguồn C++17 được gửi đến hệ thống chấm.</p>
              </div>
            </li>
            <li>
              <span className="mono">03</span>
              <div>
                <strong>Theo dõi kết quả</strong>
                <p>Nhận phản hồi trực tiếp, xem lại từng lần nộp.</p>
              </div>
            </li>
          </ol>
        </div>
        <div className="login-context-foot">
          <span>
            <Icon name="code" />
            C++17
          </span>
          <span>
            <Icon name="monitor" />
            Không gian thi trực tuyến
          </span>
        </div>
      </section>
      <section className="login-form-region">
        <div className="login-form-wrap">
          <span className="context-tag">Truy cập không gian của bạn</span>
          <h2>Đăng nhập</h2>
          <p className="muted">Sử dụng tài khoản được giáo viên cung cấp.</p>
          <form id="login-form" className="stack" onSubmit={submit}>
            <label htmlFor="login-code">
              Mã sinh viên / giáo viên
              <input
                id="login-code"
                name="code"
                autoComplete="username"
                placeholder="Nhập mã tài khoản"
                required
                autoFocus
                aria-invalid={!!error}
                aria-describedby={error ? "login-err" : undefined}
              />
            </label>
            <label htmlFor="login-password">
              Mật khẩu
              <div className="password-field">
                <input
                  id="login-password"
                  name="password"
                  type={visible ? "text" : "password"}
                  autoComplete="current-password"
                  placeholder="Nhập mật khẩu"
                  required
                  aria-invalid={!!error}
                />
                <button type="button" onClick={() => setVisible(!visible)}>
                  {visible ? "Ẩn" : "Hiện"}
                </button>
              </div>
            </label>
            {error && (
              <div className="form-error" id="login-err" role="alert">
                <Icon name="alert" />
                {error}
              </div>
            )}
            <button className="primary" disabled={pending}>
              {pending ? (
                <>
                  <span className="spinner" />
                  Đang đăng nhập…
                </>
              ) : (
                <>
                  Đăng nhập
                  <Icon name="arrow" />
                </>
              )}
            </button>
          </form>
          <div className="login-note">
            <Icon name="info" />
            <span>
              Sinh viên đăng nhập lần đầu bằng mã sinh viên và mật khẩu mặc định
              là mã sinh viên.
            </span>
          </div>
          <p className="login-help">
            Cần hỗ trợ đăng nhập? Liên hệ giáo viên của bạn.
          </p>
        </div>
        <div className="login-form-foot">
          <span>LAN Judge</span>
          <span>Code. Submit. Iterate.</span>
        </div>
      </section>
    </div>
  );
}

function Password({ signOut }) {
  const [pending, setPending] = useState(false),
    [error, setError] = useState("");
  const submit = async (e) => {
    e.preventDefault();
    const f = e.currentTarget;
    setError("");
    if (f.new1.value !== f.new2.value)
      return setError("Hai mật khẩu mới không khớp.");
    setPending(true);
    try {
      await put("/me/password", {
        current_password: f.cur.value,
        new_password: f.new1.value,
      });
      toast("Đã đổi mật khẩu. Vui lòng đăng nhập lại.", "ok");
      await signOut();
    } catch (e) {
      setError(errText(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <div className="settings-page">
      <div className="page-heading">
        <div>
          <span className="breadcrumb">Tài khoản / Bảo mật</span>
          <h1>Đổi mật khẩu</h1>
          <p>Cập nhật mật khẩu đăng nhập của bạn.</p>
        </div>
      </div>
      <div className="settings-content">
        <form className="stack" id="pw" onSubmit={submit}>
          <label>
            Mật khẩu hiện tại
            <input
              name="cur"
              type="password"
              autoComplete="current-password"
              required
            />
          </label>
          <label>
            Mật khẩu mới
            <input
              name="new1"
              type="password"
              autoComplete="new-password"
              maxLength="72"
              required
            />
          </label>
          <label>
            Nhập lại mật khẩu mới
            <input
              name="new2"
              type="password"
              autoComplete="new-password"
              maxLength="72"
              required
            />
          </label>
          {error && (
            <p className="errors" role="alert">
              {error}
            </p>
          )}
          <button className="primary" disabled={pending}>
            {pending ? "Đang lưu…" : "Cập nhật mật khẩu"}
            <Icon name="check" />
          </button>
        </form>
        <aside>
          <Icon name="info" />
          <h2>Bảo vệ tài khoản</h2>
          <p>Sau khi đổi mật khẩu, bạn cần đăng nhập lại trên các thiết bị.</p>
        </aside>
      </div>
    </div>
  );
}
createRoot(document.getElementById("app")).render(<App />);
