import React, { useCallback, useEffect, useRef, useState } from "react";
import { auth, get, post, upload, request } from "../static/api.js";
import * as live from "../static/live.js";
import * as screen from "../static/screen.js";
import {
  errText,
  toast,
  fmtTime,
  fmtClock,
  fmtDuration,
  modal,
  html,
  downloadBlob,
} from "../static/ui.js";
import {
  Icon,
  Loading,
  Empty,
  ErrorState,
  ResultBadge,
  VERDICTS,
  tabKeys,
} from "./components.jsx";
import { useNow, useStored, useDragResize } from "./hooks.js";
import { CodeEditor } from "./editor.jsx";
import { STATES } from "./sessions.jsx";
const MAX_SOURCE = 64 * 1024;
const RANK = { queued: 0, judging: 1, completed: 2, system_error: 2 };

export function Room({ ids: [sid, pidParam] }) {
  const [info, setInfo] = useState(null),
    [error, setError] = useState(null),
    [revision, setRevision] = useState(0);
  const reload = useCallback(() => setRevision((x) => x + 1), []);
  useEffect(() => {
    const timer = setInterval(reload, 15000);
    const offSync = live.on("resync", reload);
    const offSession = live.on("session.updated", (data) => {
      if (String(data.session_id) === String(sid)) reload();
    });
    return () => { clearInterval(timer); offSync(); offSession(); };
  }, [sid, reload]);
  useEffect(() => {
    let active = true;
    (async () => {
      try {
        let data = await get(`/sessions/${sid}`);
        if (!data.participation && data.session.state === "running") {
          try {
            await post(`/sessions/${sid}/join`);
            data = await get(`/sessions/${sid}`);
          } catch (e) {
            toast(errText(e), "error");
          }
        }
        if (active) {
          setInfo({
            ...data,
            offset: data.server_time
              ? Date.parse(data.server_time) - Date.now()
              : 0,
          });
          setError(null);
        }
      } catch (e) {
        if (active) setError(e);
      }
    })();
    return () => {
      active = false;
    };
  }, [sid, revision]);
  if (error)
    return <ErrorState error={{ message: errText(error) }} retry={reload} />;
  if (!info) return <Loading lines={8} />;
  const pid = pidParam || String(info.problems?.[0]?.id || "");
  return (
    <Workspace
      key={`${auth.user.id}.${sid}.${pid}.${info.participation?.status}.${info.session.state}`}
      sid={sid}
      pid={pid}
      info={info}
      reload={reload}
    />
  );
}

function Workspace({ sid, pid, info, reload }) {
  const s = info.session,
    part = info.participation,
    problems = info.problems || [];
  const now = useNow(info.offset),
    started = now >= Date.parse(s.starts_at),
    ended = now >= Date.parse(s.ends_at);
  const working = !!part && part.status !== "finished" && s.state === "running",
    canSubmit = working && started && !ended;
  const [problem, setProblem] = useState(null),
    [problemError, setProblemError] = useState(null);
  const [subs, setSubs] = useState([]),
    [subsLoaded, setSubsLoaded] = useState(false),
    [subError, setSubError] = useState(null);
  const [states, setStates] = useState({}),
    [announcements, setAnnouncements] = useState([]),
    [showAnn, setShowAnn] = useState(true);
  const [sharing, setSharing] = useState(screen.isSharing()),
    [sending, setSending] = useState(false),
    [finishing, setFinishing] = useState(false);
  const [leftTab, setLeftTab] = useState("description"),
    [resultTab, setResultTab] = useState("verdict"),
    [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState(null),
    [detailError, setDetailError] = useState(null);
  const [focus, setFocus] = useState(false),
    [collapsed, setCollapsed] = useState(false),
    [mobilePane, setMobilePane] = useState("description"),
    [settings, setSettings] = useState(false);
  const [split, setSplit] = useStored("lanjudge.pane.description", 42),
    [resultSize, setResultSize] = useStored("lanjudge.pane.results", 31),
    [fontSize, setFontSize] = useStored("lanjudge.editor.fontSize", 14);
  const draftKey = `lanjudge.draft.${auth.user.id}.${sid}.${pid}`;
  const [source, setSource] = useState(() => {
      try {
        return localStorage.getItem(draftKey) || "";
      } catch {
        return "";
      }
    }),
    [saved, setSaved] = useState(true);
  const sourceRef = useRef(source),
    sendRef = useRef(null),
    fileRef = useRef(null),
    gridRef = useRef(null),
    solutionRef = useRef(null),
    settingsRef = useRef(null);
  const dragDescription = useDragResize(gridRef, "vertical", setSplit, 25, 65),
    dragResults = useDragResize(
      solutionRef,
      "horizontal",
      setResultSize,
      20,
      55,
    );
  const current = subs.find((x) => x.id === selected) || subs[0],
    result = current?.result;
  const index = problems.findIndex((p) => String(p.id) === pid),
    meta = problems[index];
  const bytes = new Blob([source]).size;
  const boundary = useRef(s.state),
    alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  useEffect(() => {
    const state = !started ? "upcoming" : ended ? "ended" : "running";
    if (state !== boundary.current) {
      boundary.current = state;
      reload();
    }
  }, [started, ended, reload]);
  useEffect(() => {
    live.subscribe(Number(sid));
    const off = screen.onShareChange(setSharing);
    const unlock = working ? screen.lockOverlay() : () => {};
    if (!working) screen.stopShare();
    return () => {
      off();
      unlock();
    };
  }, [sid, working]);
  useEffect(() => {
    if (!settings) return;
    const close = (e) => {
      if (
        e.key === "Escape" ||
        (e.type === "pointerdown" && !settingsRef.current?.contains(e.target))
      )
        setSettings(false);
    };
    document.addEventListener("pointerdown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("pointerdown", close);
      document.removeEventListener("keydown", close);
    };
  }, [settings]);

  const updateSource = (value) => {
    sourceRef.current = value;
    setSource(value);
    try {
      localStorage.setItem(draftKey, value);
      setSaved(true);
    } catch {
      setSaved(false);
    }
  };
  const loadSubs = useCallback(async () => {
    try {
      const list = await get(`/sessions/${sid}/submissions`, {
        problem_id: pid,
        pageSize: 100,
      });
      if (alive.current) {
        setSubs((old) => mergeSubs(old, list.data));
        setSubsLoaded(true);
        setSubError(null);
      }
    } catch (e) {
      if (alive.current) setSubError(e);
    }
  }, [sid, pid]);
  const loadStates = useCallback(async () => {
    const sum = await get(`/sessions/${sid}/summary/me`),
      row = sum.rows?.[0];
    if (row && alive.current)
      setStates(
        Object.fromEntries(row.cells.map((c) => [c.problem_id, c.state])),
      );
  }, [sid]);
  const loadAnns = useCallback(async () => {
    const list = await get(`/sessions/${sid}/announcements`);
    if (alive.current) setAnnouncements(list.data);
  }, [sid]);
  useEffect(() => {
    let active = true;
    loadAnns().catch(() => {});
    if (part && pid) {
      get(`/sessions/${sid}/problems/${pid}`)
        .then((p) => {
          if (active) setProblem(p);
        })
        .catch((e) => {
          if (active) setProblemError(e);
        });
      loadSubs();
      loadStates().catch(() => {});
    }
    const offRun = live.on("run.updated", (x) => {
      if (String(x.session_id) !== String(sid)) return;
      if (String(x.problem_id) === String(pid)) {
        setSubs((old) => mergeSubs(old, [x]));
        if (x.result?.status === "completed")
          toast(
            `Bài #${x.id}: ${VERDICTS[x.result.verdict]?.[0] || x.result.verdict}`,
            x.result.verdict === "AC" ? "ok" : "",
          );
      }
      loadStates().catch(() => {});
    });
    const offAnn = live.on("announcement", (a) => {
      if (String(a.session_id) === String(sid)) {
        setShowAnn(true);
        toast(a.text);
        loadAnns().catch(() => {});
      }
    });
    const offSync = live.on("resync", () => {
      if (part && pid) {
        loadSubs();
        loadStates().catch(() => {});
      }
      loadAnns().catch(() => {});
    });
    return () => {
      active = false;
      offRun();
      offAnn();
      offSync();
    };
  }, [sid, pid, loadAnns, loadSubs, loadStates]);
  useEffect(() => {
    if (!current?.id) return;
    let active = true;
    setDetail(null);
    setDetailError(null);
    get(`/submissions/${current.id}`)
      .then((data) => {
        if (active) setDetail(data);
      })
      .catch((e) => {
        if (active) setDetailError(e);
      });
    return () => {
      active = false;
    };
  }, [current?.id, result?.run_id, result?.status]);
  const send = async () => {
    if (sending || !canSubmit || !screen.isSharing()) return;
    const src = sourceRef.current;
    if (!src.trim())
      return toast("Hãy viết hoặc tải lên mã nguồn trước khi nộp.", "error");
    if (new Blob([src]).size > MAX_SOURCE)
      return toast("Mã nguồn vượt quá giới hạn 64 KB.", "error");
    const fd = new FormData();
    fd.append(
      "file",
      new File([src], "solution.cpp", { type: "text/x-c++src" }),
    );
    setSending(true);
    setCollapsed(false);
    setResultTab("verdict");
    try {
      const sub = await upload(
        `/sessions/${sid}/problems/${pid}/submissions`,
        fd,
      );
      if (alive.current) {
        setSubs((old) => mergeSubs(old, [sub]));
        setSelected(sub.id);
      }
      toast(`Đã nhận bài #${sub.id} — đang chờ chấm.`, "ok");
      loadStates().catch(() => {});
    } catch (e) {
      toast(errText(e), "error");
    } finally {
      if (alive.current) setSending(false);
    }
  };
  sendRef.current = send;
  useEffect(() => {
    const key = (e) => {
      if (
        (e.metaKey || e.ctrlKey) &&
        e.key === "Enter" &&
        e.target.closest(".editor-panel")
      ) {
        e.preventDefault();
        sendRef.current?.();
      }
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, []);
  const importFile = async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    if (!/\.cpp$/i.test(f.name)) {
      toast("Chỉ nhận file .cpp.", "error");
      e.target.value = "";
      return;
    }
    if (f.size > MAX_SOURCE) {
      toast("File lớn hơn 64 KB.", "error");
      e.target.value = "";
      return;
    }
    try {
      updateSource(await f.text());
    } catch {
      toast("Không đọc được file. Vui lòng thử lại.", "error");
    }
    e.target.value = "";
  };
  const finish = async () => {
    if (
      !confirm(
        "Kết thúc làm bài? Sau khi kết thúc bạn KHÔNG nộp thêm được nữa.",
      )
    )
      return;
    setFinishing(true);
    try {
      await post(`/sessions/${sid}/finish`);
      screen.stopShare();
      toast("Đã kết thúc làm bài.", "ok");
      reload();
    } catch (e) {
      toast(errText(e), "error");
    } finally {
      if (alive.current) setFinishing(false);
    }
  };
  const navigate = (p) => {
    if (p) location.hash = `#/sessions/${sid}/${p.id}`;
  };
  const openAnns = () =>
    modal(
      html`<h2>Thông báo từ giáo viên</h2>
        ${announcements.map((a) => html`<div class="announce"><span class="muted mono small">${fmtClock(a.created_at)}</span><span>${a.text}</span></div>`)}`,
    );

  return (
    <div className={`room ${focus ? "focus-mode" : ""}`}>
      <div className="room-toolbar">
        <a
          className="icon-button room-back"
          href="#/sessions"
          aria-label="Về danh sách phiên thi"
          title="Về danh sách phiên thi"
        >
          <Icon name="back" />
        </a>
        <div className="room-breadcrumb">
          <span>{s.exam_title}</span>
          <Icon name="right" size={12} />
          <strong>{s.name}</strong>
        </div>
        <span className={`badge ${working ? "ok" : ""}`}>
          {part?.status === "finished"
            ? "Đã kết thúc bài làm"
            : STATES[s.state]}
        </span>
        <span className="spacer" />
        <span id="share-badge" className="share-state">
          {working && (
            <>
              <Icon name="monitor" size={14} />
              <span className={`status-dot ${sharing ? "ok" : "bad"}`} />
              {sharing ? "Đang chia sẻ" : "Chưa chia sẻ"}
            </>
          )}
        </span>
        {!!announcements.length && (
          <button
            className="icon-button announcement-trigger"
            onClick={openAnns}
            aria-label={`Xem ${announcements.length} thông báo từ giáo viên`}
            title="Tất cả thông báo"
          >
            <Icon name="info" />
            {announcements.length}
          </button>
        )}
        <div
          className={`room-countdown ${ended || (started && Date.parse(s.ends_at) - now < 300000) ? "low" : ""}`}
          title={
            !started
              ? "Bắt đầu sau"
              : ended
                ? "Đã hết giờ"
                : "Thời gian còn lại"
          }
        >
          <Icon name="clock" size={15} />
          <span>
            {ended
              ? "00:00"
              : fmtDuration(
                  Date.parse(started ? s.ends_at : s.starts_at) - now,
                )}
          </span>
        </div>
        {working && (
          <button
            id="finish"
            className="finish-button"
            onClick={finish}
            disabled={finishing}
          >
            {finishing ? "Đang kết thúc…" : "Kết thúc"}
            <Icon name="logout" size={14} />
          </button>
        )}
      </div>
      {showAnn && !!announcements.length && (
        <div className="room-announcement">
          <Icon name="info" />
          <span>
            <strong>Giáo viên</strong>
            <span className="muted">
              {" "}
              {fmtClock(announcements[0].created_at)}
            </span>{" "}
            · {announcements[0].text}
          </span>
          <button
            className="icon-button"
            onClick={() => setShowAnn(false)}
            aria-label="Ẩn thông báo"
          >
            <Icon name="x" size={14} />
          </button>
        </div>
      )}
      {!part || !problems.length ? (
        <Empty
          icon="book"
          title={
            !part
              ? s.state === "upcoming"
                ? "Phiên thi sắp bắt đầu"
                : "Bạn chưa tham gia phiên thi"
              : info.problems_hidden
                ? "Đề sẽ hiện khi phiên bắt đầu"
                : "Chưa có bài tập trong phiên"
          }
        >
          Trang sẽ cập nhật khi trạng thái phiên thay đổi.
        </Empty>
      ) : (
        <>
          <div className="problem-navigation">
            <span className="problem-nav-label">
              <Icon name="layers" />
              Bài tập
            </span>
            <div id="ptabs" className="problem-tabs">
              {problems.map((p) => (
                <button
                  key={p.id}
                  data-p={p.id}
                  className={String(p.id) === pid ? "active" : ""}
                  onClick={() => navigate(p)}
                >
                  <span className="problem-code mono">{p.code}</span>
                  <span className="problem-tab-title">{p.title}</span>
                  {states[p.id] === "ac" && <Icon name="check" size={13} />}
                  {states[p.id] === "pending" && <span className="spinner" />}
                  {states[p.id] === "attempted" && (
                    <span className="attempt-dot" />
                  )}
                </button>
              ))}
            </div>
            <span className="problem-position mono">
              {index + 1}/{problems.length}
            </span>
            <button
              className="icon-button"
              disabled={index <= 0}
              onClick={() => navigate(problems[index - 1])}
              aria-label="Bài trước"
            >
              <Icon name="back" size={14} />
            </button>
            <button
              className="icon-button"
              disabled={index === problems.length - 1}
              onClick={() => navigate(problems[index + 1])}
              aria-label="Bài tiếp theo"
            >
              <Icon name="arrow" size={14} />
            </button>
          </div>
          <div
            className="mobile-workspace-tabs"
            aria-label="Chọn vùng làm việc"
          >
            <button
              className={mobilePane === "description" ? "active" : ""}
              onClick={() => setMobilePane("description")}
              aria-pressed={mobilePane === "description"}
            >
              <Icon name="book" />
              Đề bài
            </button>
            <button
              className={mobilePane === "solution" ? "active" : ""}
              onClick={() => setMobilePane("solution")}
              aria-pressed={mobilePane === "solution"}
            >
              <Icon name="code" />
              Mã nguồn & kết quả
            </button>
          </div>
          <div
            className={`workspace-grid mobile-${mobilePane}`}
            ref={gridRef}
            style={{ "--description-size": `${split}%` }}
          >
            <section className="description-panel">
              <div
                className="panel-tabs"
                role="tablist"
                aria-label="Đề bài và bài nộp"
                onKeyDown={tabKeys}
              >
                <button
                  id="description-tab"
                  role="tab"
                  aria-selected={leftTab === "description"}
                  aria-controls="description-content"
                  tabIndex={leftTab === "description" ? 0 : -1}
                  className={leftTab === "description" ? "active" : ""}
                  onClick={() => setLeftTab("description")}
                >
                  <Icon name="book" />
                  Đề bài
                </button>
                <button
                  id="history-tab"
                  role="tab"
                  aria-selected={leftTab === "history"}
                  aria-controls="history-content"
                  tabIndex={leftTab === "history" ? 0 : -1}
                  className={leftTab === "history" ? "active" : ""}
                  onClick={() => setLeftTab("history")}
                >
                  <Icon name="history" />
                  Bài đã nộp<span className="tab-count">{subs.length}</span>
                </button>
              </div>
              {leftTab === "description" ? (
                <div
                  className="description-body"
                  id="description-content"
                  role="tabpanel"
                  aria-labelledby="description-tab"
                >
                  {problemError ? (
                    <ErrorState error={{ message: errText(problemError) }} />
                  ) : !problem ? (
                    <Loading lines={7} />
                  ) : (
                    <>
                      <div className="problem-heading">
                        <span className="problem-id mono">{problem.code}</span>
                        <h1>{problem.title}</h1>
                        {states[pid] === "ac" && (
                          <span
                            className="solved-indicator"
                            title="Đã giải thành công"
                          >
                            <Icon name="check" />
                          </span>
                        )}
                      </div>
                      <div className="problem-limits">
                        <span>
                          <Icon name="clock" size={14} />
                          {problem.time_limit_ms} ms
                        </span>
                        <span>
                          <Icon name="layers" size={14} />
                          {problem.memory_limit_mb} MB
                        </span>
                        <span className="mono">C++17</span>
                      </div>
                      <div className="statement">
                        <Statement text={problem.statement} />
                      </div>
                      <div className="io-note">
                        <Icon name="terminal" />
                        <span>
                          Đọc từ <code>stdin</code> · Ghi ra <code>stdout</code>
                        </span>
                      </div>
                    </>
                  )}
                </div>
              ) : (
                <div
                  className="submission-history"
                  id="history-content"
                  role="tabpanel"
                  aria-labelledby="history-tab"
                >
                  <div className="history-heading">
                    <h2>Lịch sử bài nộp</h2>
                    <span className="muted">
                      {meta?.code}. {meta?.title}
                    </span>
                  </div>
                  {subError ? (
                    <ErrorState
                      error={{ message: errText(subError) }}
                      retry={loadSubs}
                    />
                  ) : !subsLoaded ? (
                    <Loading />
                  ) : !subs.length ? (
                    <Empty title="Chưa có bài nộp">
                      Nộp mã nguồn để nhận kết quả chấm.
                    </Empty>
                  ) : (
                    <table>
                      <thead>
                        <tr>
                          <th>Bài nộp</th>
                          <th>Kết quả</th>
                          <th>Nhận lúc</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody>
                        {subs.map((x) => (
                          <tr
                            key={x.id}
                            className={
                              current?.id === x.id ? "selected-row" : ""
                            }
                          >
                            <td className="mono">#{x.id}</td>
                            <td>
                              <ResultBadge result={x.result} />
                              {x.result?.status === "completed" && (
                                <span className="table-tests muted">
                                  {x.result.passed_tests}/{x.result.total_tests}{" "}
                                  test
                                </span>
                              )}
                            </td>
                            <td className="muted">{fmtClock(x.received_at)}</td>
                            <td>
                              <button
                                className="icon-button"
                                onClick={() => {
                                  setSelected(x.id);
                                  setCollapsed(false);
                                  setResultTab("verdict");
                                  setMobilePane("solution");
                                }}
                                aria-label={`Xem bài nộp ${x.id}`}
                              >
                                <Icon name="right" size={14} />
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              )}
            </section>
            <div
              className="pane-divider vertical"
              role="separator"
              tabIndex="0"
              aria-label="Điều chỉnh chiều rộng đề bài"
              aria-orientation="vertical"
              aria-valuemin="25"
              aria-valuemax="65"
              aria-valuenow={Math.round(split)}
              onPointerDown={dragDescription}
              onKeyDown={(e) => {
                if (["ArrowLeft", "ArrowRight"].includes(e.key)) {
                  e.preventDefault();
                  setSplit((x) =>
                    Math.max(
                      25,
                      Math.min(65, x + (e.key === "ArrowRight" ? 2 : -2)),
                    ),
                  );
                }
              }}
            />
            <section
              ref={solutionRef}
              className={`solution-panel ${collapsed ? "results-collapsed" : ""}`}
              style={{ "--result-size": `${resultSize}%` }}
            >
              <div className="editor-panel">
                <div className="editor-toolbar">
                  <span className="editor-file">
                    <Icon name="code" />
                    solution.cpp
                    <span className="file-dot" />
                  </span>
                  <span className="spacer" />
                  <span className="language-label">
                    C++17 <span className="muted">g++</span>
                  </span>
                  <span className="toolbar-divider" />
                  <button
                    className="icon-button"
                    onClick={() => fileRef.current.click()}
                    aria-label="Tải lên file C++"
                    title="Tải lên .cpp"
                  >
                    <Icon name="upload" />
                  </button>
                  <button
                    className="icon-button"
                    onClick={() =>
                      downloadBlob(
                        new Blob([source], { type: "text/plain" }),
                        "solution.cpp",
                      )
                    }
                    aria-label="Tải xuống mã nguồn"
                    title="Tải xuống mã nguồn"
                  >
                    <Icon name="download" />
                  </button>
                  <div className="editor-settings" ref={settingsRef}>
                    <button
                      className={`icon-button ${settings ? "active" : ""}`}
                      onClick={() => setSettings(!settings)}
                      aria-expanded={settings}
                      aria-label="Cài đặt editor"
                      title="Cài đặt editor"
                    >
                      <Icon name="settings" />
                    </button>
                    {settings && (
                      <div className="editor-settings-menu">
                        <strong>Trình soạn thảo</strong>
                        <div>
                          <span>Cỡ chữ</span>
                          <button
                            disabled={fontSize <= 12}
                            onClick={() =>
                              setFontSize((n) => Math.max(12, n - 1))
                            }
                            aria-label="Giảm cỡ chữ"
                          >
                            −
                          </button>
                          <span className="mono">{fontSize}px</span>
                          <button
                            disabled={fontSize >= 18}
                            onClick={() =>
                              setFontSize((n) => Math.min(18, n + 1))
                            }
                            aria-label="Tăng cỡ chữ"
                          >
                            +
                          </button>
                        </div>
                        <p>Tab: 4 dấu cách · UTF-8</p>
                        <button
                          className="text-button"
                          onClick={() => {
                            setSplit(42);
                            setResultSize(31);
                            setFontSize(14);
                          }}
                        >
                          Khôi phục bố cục mặc định
                        </button>
                      </div>
                    )}
                  </div>
                  <button
                    className="icon-button focus-button"
                    onClick={() => {
                      setFocus(!focus);
                      setMobilePane("solution");
                    }}
                    aria-pressed={focus}
                    aria-label={
                      focus ? "Thoát chế độ tập trung" : "Tập trung viết mã"
                    }
                    title={
                      focus ? "Thoát chế độ tập trung" : "Tập trung viết mã"
                    }
                  >
                    <Icon name={focus ? "collapse" : "expand"} />
                  </button>
                  <input
                    ref={fileRef}
                    id="file"
                    type="file"
                    accept=".cpp"
                    hidden
                    onChange={importFile}
                  />
                </div>
                <CodeEditor
                  value={source}
                  onChange={updateSource}
                  fontSize={fontSize}
                />
                <div className="editor-status">
                  <span className={saved ? "" : "errors"}>
                    <Icon name={saved ? "check" : "alert"} size={12} />
                    {saved ? "Đã lưu bản nháp" : "Chưa lưu được bản nháp"}
                  </span>
                  <span className="spacer" />
                  <span className={bytes > MAX_SOURCE ? "errors" : ""}>
                    {(bytes / 1024).toFixed(1)} / 64 KB
                  </span>
                  <span>UTF-8</span>
                  <span>Spaces: 4</span>
                </div>
                <div className="submit-toolbar">
                  <span className="submit-hint">
                    <span className="status-dot" />
                    {canSubmit
                      ? "Sẵn sàng nộp bài"
                      : "Phiên không nhận thêm bài nộp"}
                  </span>
                  <button
                    className="primary"
                    id="send"
                    onClick={send}
                    disabled={!canSubmit || sending || !sharing}
                  >
                    {sending ? (
                      <span className="spinner" />
                    ) : (
                      <Icon name="upload" size={14} />
                    )}
                    {sending ? "Đang gửi…" : "Nộp bài"}
                    <kbd>
                      {/Mac|iPhone|iPad/.test(navigator.platform)
                        ? "⌘"
                        : "Ctrl"}{" "}
                      ↵
                    </kbd>
                  </button>
                </div>
              </div>
              <div
                className="pane-divider horizontal"
                role="separator"
                tabIndex={collapsed ? -1 : 0}
                aria-label="Điều chỉnh chiều cao kết quả"
                aria-orientation="horizontal"
                aria-valuemin="20"
                aria-valuemax="55"
                aria-valuenow={Math.round(resultSize)}
                onPointerDown={dragResults}
                onKeyDown={(e) => {
                  if (["ArrowUp", "ArrowDown"].includes(e.key)) {
                    e.preventDefault();
                    setResultSize((x) =>
                      Math.max(
                        20,
                        Math.min(55, x + (e.key === "ArrowUp" ? 2 : -2)),
                      ),
                    );
                  }
                }}
              />
              <section className="results-panel">
                <div className="panel-tabs">
                  <div
                    role="tablist"
                    aria-label="Kết quả và thông tin bài nộp"
                    className="result-tabs"
                    onKeyDown={tabKeys}
                  >
                    <button
                      id="verdict-tab"
                      role="tab"
                      aria-selected={resultTab === "verdict"}
                      tabIndex={resultTab === "verdict" ? 0 : -1}
                      aria-controls="result-content"
                      className={resultTab === "verdict" ? "active" : ""}
                      onClick={() => {
                        setResultTab("verdict");
                        setCollapsed(false);
                      }}
                    >
                      <Icon name="terminal" />
                      Kết quả chấm
                      {result?.status === "completed" && (
                        <span
                          className={`result-dot ${result.verdict === "AC" ? "ok" : "bad"}`}
                        />
                      )}
                    </button>
                    <button
                      id="submission-info-tab"
                      role="tab"
                      aria-selected={resultTab === "info"}
                      tabIndex={resultTab === "info" ? 0 : -1}
                      aria-controls="result-content"
                      className={resultTab === "info" ? "active" : ""}
                      onClick={() => {
                        setResultTab("info");
                        setCollapsed(false);
                      }}
                    >
                      Thông tin bài nộp
                    </button>
                  </div>
                  <span className="spacer" />
                  {current && (
                    <button
                      className="result-sub-id mono"
                      onClick={() => setLeftTab("history")}
                    >
                      #{current.id}
                    </button>
                  )}
                  <button
                    id="collapse-results"
                    className="icon-button"
                    aria-label={
                      collapsed ? "Mở rộng kết quả" : "Thu gọn kết quả"
                    }
                    aria-expanded={!collapsed}
                    onClick={() => setCollapsed(!collapsed)}
                    title={collapsed ? "Mở rộng kết quả" : "Thu gọn kết quả"}
                  >
                    <Icon
                      name="down"
                      size={15}
                      style={{
                        transform: collapsed ? "rotate(180deg)" : undefined,
                      }}
                    />
                  </button>
                </div>
                {!collapsed && (
                  <div
                    className="result-body"
                    id="result-content"
                    role="tabpanel"
                    aria-labelledby={
                      resultTab === "verdict"
                        ? "verdict-tab"
                        : "submission-info-tab"
                    }
                    aria-live="polite"
                  >
                    {sending ? (
                      <ResultPending title="Đang gửi mã nguồn…">
                        Chờ máy chủ xác nhận bài nộp.
                      </ResultPending>
                    ) : subError ? (
                      <ErrorState
                        error={{ message: errText(subError) }}
                        retry={loadSubs}
                      />
                    ) : !subsLoaded ? (
                      <Loading lines={2} />
                    ) : !current ? (
                      <div className="result-idle">
                        <Icon name="terminal" size={25} />
                        <div>
                          <strong>Chưa có kết quả chấm</strong>
                          <p>Nộp mã nguồn để nhận kết quả từ hệ thống.</p>
                          <span className="muted mono">
                            C++17 · Tối đa 64 KB
                          </span>
                        </div>
                      </div>
                    ) : resultTab === "verdict" ? (
                      <JudgeResult
                        submission={current}
                        detail={detail}
                        error={detailError}
                      />
                    ) : (
                      <SubmissionInfo
                        submission={current}
                        detail={detail}
                        error={detailError}
                      />
                    )}
                  </div>
                )}
              </section>
            </section>
          </div>
        </>
      )}
      <div className="workspace-status">
        <span>
          <span className="status-dot" />
          {s.state === "running" && part?.status !== "finished"
            ? "Đang làm bài"
            : STATES[s.state]}
        </span>
        <span>{meta ? `Bài ${meta.code}` : s.name}</span>
        <span className="spacer" />
        <span>Bản nháp lưu trên trình duyệt này</span>
        <span className="mono">C++17</span>
      </div>
    </div>
  );
}

function mergeSubs(old, incoming) {
  const map = new Map(old.map((x) => [x.id, x]));
  for (const x of incoming) {
    const a = map.get(x.id)?.result,
      b = x.result;
    if (
      a &&
      b &&
      (b.run_id < a.run_id ||
        (b.run_id === a.run_id && RANK[b.status] < RANK[a.status]))
    )
      continue;
    map.set(x.id, x);
  }
  return [...map.values()].sort((a, b) => b.id - a.id);
}
function ResultPending({ title, children }) {
  return (
    <div className="result-pending">
      <span className="spinner" />
      <div>
        <strong>{title}</strong>
        <p>{children}</p>
      </div>
    </div>
  );
}
function JudgeResult({ submission, detail, error }) {
  const r =
    detail?.result?.run_id === submission.result?.run_id &&
    detail.result.status === submission.result.status
      ? detail.result
      : submission.result;
  if (!r || ["queued", "judging"].includes(r.status))
    return (
      <ResultPending
        title={
          r?.status === "judging"
            ? "Đang chấm lời giải…"
            : "Đang chờ hệ thống chấm…"
        }
      >
        Bài #{submission.id} đã được tiếp nhận. Kết quả sẽ tự cập nhật.
      </ResultPending>
    );
  const [title, description, kind] =
    r.status === "system_error"
      ? [
          "Lỗi hệ thống",
          "Hệ thống chấm gặp lỗi. Vui lòng liên hệ giáo viên.",
          "bad",
        ]
      : VERDICTS[r.verdict] || [r.verdict, "", "bad"];
  return (
    <div className={`judge-result ${kind}`}>
      <div className="verdict-heading">
        <span className="verdict-icon">
          <Icon name={r.verdict === "AC" ? "check" : "alert"} size={20} />
        </span>
        <h2>{title}</h2>
        <span className="verdict-tests mono">
          {r.passed_tests}/{r.total_tests}
          <span> test</span>
        </span>
      </div>
      <p className="verdict-description">{description}</p>
      <div className="result-metadata">
        <span>
          <span className="muted">Ngôn ngữ</span> C++17
        </span>
        <span>
          <span className="muted">Phiên bản đề</span> v
          {r.problem_version || submission.problem_version}
        </span>
        <span className="muted">{fmtClock(submission.received_at)}</span>
      </div>
      {r.compile_output && (
        <pre className="compile-output">{r.compile_output}</pre>
      )}
      {error && (
        <p className="errors">
          Không tải được thông tin chi tiết: {errText(error)}
        </p>
      )}
      <div className="result-bottom">
        <span className="muted mono">#{submission.id}</span>
        <SourcePreview id={submission.id} />
      </div>
    </div>
  );
}
function SubmissionInfo({ submission, detail, error }) {
  if (error) return <ErrorState error={{ message: errText(error) }} />;
  if (!detail) return <Loading lines={2} />;
  const r = detail.result || {};
  return (
    <div className="submission-info">
      <dl className="kv">
        <dt>Bài nộp</dt>
        <dd className="mono">
          #{detail.id} · {detail.problem_code}
        </dd>
        <dt>Nhận lúc</dt>
        <dd>{fmtTime(detail.received_at)}</dd>
        <dt>Chấm xong</dt>
        <dd>{r.finished_at ? fmtTime(r.finished_at) : "Đang chờ"}</dd>
        <dt>Trình biên dịch</dt>
        <dd>{r.compiler_info || "C++17"}</dd>
      </dl>
      <p className="muted">
        Bộ test được giữ kín. Giáo viên có thể xem chi tiết thời gian và bộ nhớ.
      </p>
      <SourcePreview id={submission.id} />
    </div>
  );
}
function SourcePreview({ id }) {
  const [pending, setPending] = useState(false);
  const open = async () => {
    setPending(true);
    try {
      const src = await request("GET", `/submissions/${id}/source`, {
        as: "text",
      });
      modal(
        html`<div class="breadcrumb">Bài nộp / #${id}</div>
          <h2>Mã nguồn C++17</h2>
          <pre>${src}</pre>`,
      );
    } catch (e) {
      toast(errText(e), "error");
    } finally {
      setPending(false);
    }
  };
  return (
    <button className="text-button" disabled={pending} onClick={open}>
      {pending ? "Đang tải…" : "Xem mã nguồn"}
      <Icon name="right" size={13} />
    </button>
  );
}
function Statement({ text = "" }) {
  const isHeading = (s) =>
    /^#{1,6}\s/.test(s) ||
    /^(Dữ liệu vào|Dữ liệu ra|Đầu vào|Đầu ra|Input|Output|Ví dụ(?: \d+)?|Example(?: \d+)?|Giải thích|Explanation|Giới hạn|Constraints|Yêu cầu|Ghi chú|Lưu ý|Mô tả)$/i.test(
      s.trim(),
    );
  return text.split(/\n{2,}/).map((block, i) => {
    const lines = block.split("\n");
    if (!isHeading(lines[0])) return <p key={i}>{block}</p>;
    const heading = lines.shift().replace(/^#{1,6}\s*/, "");
    if (/^(Input|Output|Đầu vào|Đầu ra)$/i.test(heading))
      return (
        <div className="example-block" key={i}>
          <span>{heading}</span>
          <pre>{lines.join("\n")}</pre>
        </div>
      );
    if (/^(Ví dụ|Example)/i.test(heading)) {
      const content = [],
        preamble = [];
      let label = "",
        data = [];
      const flush = () => {
        if (label)
          content.push(
            <div className="example-block" key={content.length}>
              <span>{label}</span>
              <pre>{data.join("\n")}</pre>
            </div>,
          );
      };
      for (const line of lines) {
        if (/^(Input|Output|Đầu vào|Đầu ra)$/i.test(line.trim())) {
          flush();
          label = line;
          data = [];
        } else if (label) data.push(line);
        else preamble.push(line);
      }
      flush();
      return (
        <section key={i}>
          <h2>{heading}</h2>
          {preamble.length > 0 && <p>{preamble.join("\n")}</p>}
          {content}
        </section>
      );
    }
    return (
      <section key={i}>
        <h2>{heading}</h2>
        {lines.length > 0 && <p>{lines.join("\n")}</p>}
      </section>
    );
  });
}
