// Real Chrome, isolated API fixtures. These are never production judge results.
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { launch } from "./cdp.mjs";
import { startFixtures } from "./fixture-server.mjs";
const chrome =
  process.env.CHROME ||
  (existsSync("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
    ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    : "google-chrome");
const server = await startFixtures();
let browser,
  checks = 0;
const exceptions = [];
const check = (label, value) => {
  assert.ok(value, label);
  checks++;
  console.log("✓ " + label);
};
const click = (p, selector) =>
  p.eval(`document.querySelector(${JSON.stringify(selector)}).click()`);
async function text(p, selector, value) {
  await p.eval(
    `(() => { const t=document.querySelector(${JSON.stringify(selector)});t.focus();t.select(); })()`,
  );
  await p.send("Input.insertText", { text: value });
}
const viewport = (p, width, height) =>
  p.send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: width < 701,
  });
const fits = (p) =>
  p.eval(
    'document.documentElement.scrollWidth <= innerWidth && document.documentElement.scrollHeight <= innerHeight + 1 && document.querySelector(".header-actions").getBoundingClientRect().right <= innerWidth',
  );
async function shot(p, name) {
  await p.eval("document.fonts.ready.then(()=>true)");
  await new Promise((r) => setTimeout(r, 240));
  writeFileSync(`artifacts/ui/${name}.png`, await p.screenshot());
}
async function go(p, hash, selector) {
  await p.eval(`location.hash=${JSON.stringify(hash)}`);
  await p.waitFor(`document.querySelector(${JSON.stringify(selector)})`);
}
async function login(p, code) {
  await p.goto(server.url);
  await p.waitFor('document.querySelector("#login-form")');
  await text(p, "[name=code]", code);
  await text(p, "[name=password]", "demo");
  await p.eval('document.querySelector("#login-form").requestSubmit()');
  await p.waitFor('document.querySelector("#who")');
}
const code =
  '#include <iostream>\n#include <unordered_map>\nusing namespace std;\n\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n\n    int n, k;\n    cin >> n >> k;\n    unordered_map<int, int> seen;\n\n    for (int i = 1; i <= n; ++i) {\n        int x; cin >> x;\n        if (seen.count(k - x)) {\n            cout << seen[k - x] << " " << i;\n            return 0;\n        }\n        seen[x] = i;\n    }\n    cout << -1;\n    return 0;\n}';
mkdirSync("artifacts/ui", { recursive: true });
try {
  browser = await launch({ chrome, port: 9337 });
  browser.on((m) => {
    if (m.method === "Runtime.exceptionThrown")
      exceptions.push(
        m.params.exceptionDetails.exception?.description ||
          m.params.exceptionDetails.text,
      );
  });
  const p = await browser.newPage();
  await viewport(p, 1440, 900);
  // Test-only capture stream. The production screen module still validates
  // monitor capture and locks/relocks the room through its actual lifecycle.
  await p.send("Page.addScriptToEvaluateOnNewDocument", {
    source: `navigator.mediaDevices.getDisplayMedia=async()=>{const c=document.createElement('canvas');c.width=640;c.height=360;const stream=c.captureStream(5);stream.getVideoTracks()[0].getSettings=()=>({displaySurface:'monitor'});return stream;};`,
  });
  await p.goto(server.url);
  await p.waitFor('document.querySelector("#login-form")');
  check(
    "Dark theme is the default",
    await p.eval('document.documentElement.dataset.theme === "dark"'),
  );
  check("Login fills the viewport", await fits(p));
  await shot(p, "login-dark");
  await text(p, "[name=code]", "SV001");
  await text(p, "[name=password]", "wrong");
  await p.eval('document.querySelector("#login-form").requestSubmit()');
  await p.waitFor('document.querySelector("#login-err")');
  check(
    "Authentication errors are visible",
    await p.eval(
      'document.querySelector("#login-err").innerText.includes("Sai mã")',
    ),
  );
  await text(p, "[name=password]", "demo");
  await p.eval('document.querySelector("#login-form").requestSubmit()');
  await p.waitFor('document.querySelector(".session-table")');
  check(
    "Session list uses API data",
    await p.eval(
      'document.querySelectorAll(".session-table tbody tr").length === 5',
    ),
  );
  check(
    "Application shell spans the viewport",
    await p.eval(
      'document.querySelector(".page-main").getBoundingClientRect().width === innerWidth',
    ),
  );
  await shot(p, "sessions-dark");
  await text(p, "input[type=search]", "không tồn tại");
  await p.waitFor('document.querySelector(".empty-state")');
  check("Session search empty state works", true);
  await text(p, "input[type=search]", "");
  await p.waitFor('document.querySelector(".session-table")');
  await go(p, "#/sessions/1/1", ".lock");
  check(
    "Active exam requires screen sharing",
    await p.eval('!document.querySelector(".lock").hidden'),
  );
  await click(p, "#share-btn");
  await p.waitFor('document.querySelector(".lock").hidden');
  await p.waitFor('document.querySelector(".description-body h1")');
  await p.waitFor('document.querySelector("#conn").classList.contains("on")');
  check(
    "Sharing unlocks real submission controls",
    await p.eval('!document.querySelector("#send").disabled'),
  );
  await text(p, "#code", code);
  check(
    "Problem-specific draft is saved",
    await p.eval(
      `localStorage.getItem('lanjudge.draft.1.1')===${JSON.stringify(code)}`,
    ),
  );
  await p.eval('document.querySelector("#code").setSelectionRange(0,0)');
  await p.send("Input.dispatchKeyEvent", {
    type: "keyDown",
    key: "Tab",
    code: "Tab",
  });
  check(
    "Editor preserves Tab indentation",
    await p.eval('document.querySelector("#code").value.startsWith("    ")'),
  );
  await p.send("Input.dispatchKeyEvent", {
    type: "keyDown",
    key: "Escape",
    code: "Escape",
  });
  check(
    "Escape exits editor keyboard trap",
    await p.eval('document.activeElement.tagName === "BUTTON"'),
  );
  const editorKey = async (key, code, modifiers = 0) => {
    await p.send("Input.dispatchKeyEvent", {
      type: "keyDown",
      key,
      code,
      modifiers,
      ...(key.length === 1 && !(modifiers & 6) ? { text: key } : {}),
      ...(key === "Enter" ? { text: "\r", windowsVirtualKeyCode: 13 } : {}),
      ...(key === "Backspace" ? { windowsVirtualKeyCode: 8 } : {}),
    });
    await p.send("Input.dispatchKeyEvent", {
      type: "keyUp",
      key,
      code,
      modifiers,
    });
  };
  const selectCode = async (value, start, end = start) => {
    await text(p, "#code", value || " ");
    if (!value) {
      await p.eval('document.querySelector("#code").select()');
      await editorKey("Backspace", "Backspace");
    }
    await p.eval(
      `document.querySelector('#code').setSelectionRange(${start},${end})`,
    );
  };
  const editorState = () =>
    p.eval(
      `(() => { const t=document.querySelector('#code');return [t.value,t.selectionStart,t.selectionEnd]; })()`,
    );
  await selectCode("", 0);
  await editorKey("{", "BracketLeft", 8);
  check(
    "Typing { inserts a pair with the caret between braces",
    JSON.stringify(await editorState()) === JSON.stringify(["{}", 1, 1]),
  );
  await editorKey("Enter", "Enter");
  check(
    "Enter creates an indented line inside braces",
    JSON.stringify(await editorState()) ===
      JSON.stringify(["{\n    \n}", 6, 6]),
  );
  check(
    "Auto-paired edits update the saved draft",
    await p.eval('localStorage.getItem("lanjudge.draft.1.1")==="{\\n    \\n}"'),
  );
  for (const [open, close, keyCode] of [
    ["(", ")", "Digit9"],
    ["[", "]", "BracketLeft"],
    ['"', '"', "Quote"],
    ["'", "'", "Quote"],
  ]) {
    await selectCode("", 0);
    await editorKey(open, keyCode);
    check(
      `Typing ${open} inserts its matching delimiter`,
      JSON.stringify(await editorState()) ===
        JSON.stringify([open + close, 1, 1]),
    );
    await editorKey(close, keyCode);
    check(
      `Typing the existing ${close} moves past it without duplication`,
      JSON.stringify(await editorState()) ===
        JSON.stringify([open + close, 2, 2]),
    );
  }
  await selectCode("    if (ok) {}", 13);
  await editorKey("Enter", "Enter");
  check(
    "Nested blocks keep the closing brace aligned",
    JSON.stringify(await editorState()) ===
      JSON.stringify(["    if (ok) {\n        \n    }", 22, 22]),
  );
  await selectCode("{}", 1);
  await editorKey("}", "BracketRight", 8);
  check(
    "Closing braces are not duplicated",
    JSON.stringify(await editorState()) === JSON.stringify(["{}", 2, 2]),
  );
  await selectCode("{}", 1);
  await editorKey("Backspace", "Backspace");
  check(
    "Backspace removes an empty bracket pair",
    JSON.stringify(await editorState()) === JSON.stringify(["", 0, 0]),
  );
  await selectCode("answer", 0, 6);
  await editorKey("(", "Digit9", 8);
  check(
    "Typing an opener wraps selected code and preserves its selection",
    JSON.stringify(await editorState()) === JSON.stringify(["(answer)", 1, 7]),
  );
  for (const value of [
    "// comment ",
    "/* comment ",
    'const char* s = "text',
    'auto s = R"tag(text',
    "auto n = 1'000; // comment ",
  ]) {
    await selectCode(value, value.length);
    await editorKey("{", "BracketLeft", 8);
    check(
      "Comments and strings keep literal brackets: " + value,
      (await editorState())[0] === value + "{",
    );
  }
  await selectCode("auto n = 1", 10);
  await editorKey("'", "Quote");
  await p.send("Input.insertText", { text: "000; " });
  await editorKey("{", "BracketLeft", 8);
  check(
    "C++ numeric separators do not interfere with bracket completion",
    (await editorState())[0] === "auto n = 1'000; {}",
  );
  await selectCode("", 0);
  await editorKey("{", "BracketLeft", 8);
  await p.send("Input.dispatchKeyEvent", {
    type: "keyDown",
    key: "z",
    code: "KeyZ",
    commands: ["undo"],
  });
  check(
    "Undo removes an auto-inserted pair together",
    (await editorState())[0] === "",
  );
  await p.send("Input.dispatchKeyEvent", {
    type: "keyDown",
    key: "z",
    code: "KeyZ",
    commands: ["redo"],
  });
  check("Redo restores the full pair", (await editorState())[0] === "{}");
  await selectCode("", 0);
  check(
    "IME composition keeps its own keystrokes",
    await p.eval(
      `(() => { const t=document.querySelector('#code');const event=new KeyboardEvent('keydown',{key:'{',code:'BracketLeft',isComposing:true,bubbles:true,cancelable:true});t.dispatchEvent(event);return !event.defaultPrevented && t.value===''; })()`,
    ),
  );
  await text(p, "#code", code);
  await click(p, '[data-p="2"]');
  await p.waitFor(
    'document.querySelector(".description-body h1")?.innerText.includes("Dãy con")',
  );
  await text(p, "#code", "// draft B");
  await click(p, '[data-p="1"]');
  await p.waitFor(
    'document.querySelector("#code")?.value.includes("unordered_map")',
  );
  check("Independent drafts survive problem navigation", true);
  const widthBefore = await p.eval(
    'document.querySelector(".description-panel").getBoundingClientRect().width',
  );
  await p.eval('document.querySelector(".pane-divider.vertical").focus()');
  await p.send("Input.dispatchKeyEvent", {
    type: "keyDown",
    key: "ArrowRight",
    code: "ArrowRight",
  });
  check(
    "Horizontal split is keyboard-resizable",
    await p.eval(
      `document.querySelector('.description-panel').getBoundingClientRect().width > ${widthBefore}`,
    ),
  );
  const heightBefore = await p.eval(
    'document.querySelector(".results-panel").getBoundingClientRect().height',
  );
  await p.eval('document.querySelector(".pane-divider.horizontal").focus()');
  await p.send("Input.dispatchKeyEvent", {
    type: "keyDown",
    key: "ArrowUp",
    code: "ArrowUp",
  });
  check(
    "Vertical split is keyboard-resizable",
    await p.eval(
      `document.querySelector('.results-panel').getBoundingClientRect().height > ${heightBefore}`,
    ),
  );
  await click(p, "#collapse-results");
  check(
    "Result panel collapses",
    await p.eval('!document.querySelector(".result-body")'),
  );
  await click(p, "#collapse-results");
  await click(p, '[aria-label="Cài đặt editor"]');
  await click(p, '[aria-label="Tăng cỡ chữ"]');
  check(
    "Editor font size is adjustable",
    await p.eval(
      'getComputedStyle(document.querySelector("#code")).fontSize === "15px"',
    ),
  );
  await click(p, '[aria-label="Cài đặt editor"]');
  await click(p, '[data-p="2"]');
  await p.waitFor(
    'document.querySelector(".description-body h1")?.innerText.includes("Dãy con")',
  );
  check(
    "Panel sizes and font preference persist",
    await p.eval(
      'document.querySelector(".pane-divider.vertical").getAttribute("aria-valuenow")==="44" && document.querySelector(".pane-divider.horizontal").getAttribute("aria-valuenow")==="33" && getComputedStyle(document.querySelector("#code")).fontSize==="15px"',
    ),
  );
  await click(p, '[data-p="1"]');
  await p.waitFor(
    'document.querySelector("#code")?.value.includes("unordered_map")',
  );
  await click(p, '[aria-label="Cài đặt editor"]');
  await click(p, ".editor-settings-menu .text-button");
  await click(p, '[aria-label="Cài đặt editor"]');
  for (const verdict of ["AC", "WA", "TLE", "MLE", "RE", "CE"]) {
    await text(p, "#code", code + "\n// TEST_VERDICT:" + verdict);
    await click(p, "#send");
    const label = {
      AC: "Accepted",
      WA: "Wrong Answer",
      TLE: "Time Limit Exceeded",
      MLE: "Memory Limit Exceeded",
      RE: "Runtime Error",
      CE: "Compilation Error",
    }[verdict];
    await p.waitFor(
      `document.querySelector('.verdict-heading h2')?.innerText===${JSON.stringify(label)}`,
    );
    check("WebSocket result renders " + verdict, true);
  }
  await p.waitFor('document.querySelector(".compile-output")');
  check(
    "Compilation output is loaded from real detail response",
    await p.eval(
      'document.querySelector(".compile-output").innerText.includes("expected semicolon")',
    ),
  );
  check(
    "Students do not call teacher-only test API",
    !server.calls.some((x) => /\/submissions\/\d+\/tests$/.test(x.path)),
  );
  await click(p, "#history-tab");
  check(
    "Every submission remains in history",
    await p.eval(
      'document.querySelectorAll(".submission-history tbody tr").length===6',
    ),
  );
  await click(p, ".result-bottom .text-button");
  await p.waitFor('document.querySelector(".modal")');
  check(
    "Source preview is accessible",
    await p.eval(
      'document.querySelector(".modal").getAttribute("aria-modal")==="true"',
    ),
  );
  await p.send("Input.dispatchKeyEvent", {
    type: "keyDown",
    key: "Escape",
    code: "Escape",
  });
  check(
    "Escape closes dialogs",
    await p.eval('!document.querySelector(".modal")'),
  );
  await click(p, "#description-tab");
  await click(p, '[aria-label="Tập trung viết mã"]');
  check(
    "Focus mode uses full application width",
    await p.eval(
      'document.querySelector(".solution-panel").getBoundingClientRect().width===innerWidth',
    ),
  );
  await click(p, '[aria-label="Thoát chế độ tập trung"]');
  server.broadcast("announcement", {
    session_id: 1,
    text: "Thông báo kiểm thử",
    created_at: new Date().toISOString(),
  });
  for (const [w, h] of [
    [1366, 768],
    [1440, 900],
    [1920, 1080],
    [2560, 1080],
    [768, 1024],
    [390, 844],
  ]) {
    await viewport(p, w, h);
    check(`Full-screen workspace fits ${w}×${h}`, await fits(p));
    check(
      `Workspace touches both edges at ${w}px`,
      await p.eval(
        'Math.round(document.querySelector(".workspace-grid").getBoundingClientRect().width)===innerWidth',
      ),
    );
    if (w < 701) {
      await click(p, ".mobile-workspace-tabs button:last-child");
      check(
        "Mobile editor has a usable height",
        await p.eval(
          'document.querySelector(".editor-panel").getBoundingClientRect().height>220',
        ),
      );
    }
    await p.eval(
      'document.querySelector("#code").scrollTop=0;document.querySelector("#code").dispatchEvent(new Event("scroll"))',
    );
    await shot(p, `workspace-${w}`);
  }
  await viewport(p, 1440, 900);
  await click(p, ".header-actions>.icon-button");
  check(
    "Light theme is available",
    await p.eval('document.documentElement.dataset.theme==="light"'),
  );
  await shot(p, "workspace-light");
  await click(p, ".header-actions>.icon-button");
  await p.eval('import("/assets/screen.js").then(m=>m.stopShare())');
  await p.waitFor('!document.querySelector(".lock").hidden');
  check("Stopping sharing relocks the exam", true);
  await click(p, "#share-btn");
  await p.waitFor('document.querySelector(".lock").hidden');
  await p.eval(
    'window.confirm=()=>true;document.querySelector("#finish").click()',
  );
  await p.waitFor('!document.querySelector("#finish")');
  check(
    "Finishing disables submissions",
    await p.eval('document.querySelector("#send").disabled'),
  );
  await go(p, "#/sessions", ".session-table");
  await viewport(p, 390, 844);
  check("Session browser fits mobile", await fits(p));
  await shot(p, "sessions-mobile");
  await viewport(p, 1440, 900);
  await go(p, "#/password", "#pw");
  await text(p, "[name=cur]", "demo");
  await text(p, "[name=new1]", "one");
  await text(p, "[name=new2]", "two");
  await p.eval('document.querySelector("#pw").requestSubmit()');
  await p.waitFor('document.querySelector("#pw [role=alert]")');
  check("Password mismatch is blocked", true);
  await click(p, "#who");
  await click(p, "#logout");
  await p.waitFor('document.querySelector("#login-form")');
  check(
    "Logout clears authentication",
    !(await p.eval('!!document.querySelector("#who")')),
  );
  await viewport(p, 390, 844);
  check("Login fits mobile", await fits(p));
  await shot(p, "login-mobile");
  const t = await browser.newPage();
  await viewport(t, 1440, 900);
  await login(t, "GV001");
  await t.waitFor('document.querySelector(".exam-table")');
  check("Teacher exam library renders", true);
  await shot(t, "teacher-exams");
  for (const [hash, sel, name] of [
    ["#/students", "#list table", "students"],
    ["#/exams/1", "#new-problem", "exam"],
    ["#/sessions/1/board", ".board", "board"],
    ["#/sessions/1/submissions", "#subs table", "submissions"],
    ["#/sessions/1/participants", "#tab table", "participants"],
    ["#/sessions/1/announcements", "#ann", "announcements"],
    ["#/sessions/1/screens", "#scr-rows", "screens"],
    ["#/submissions/121", "#rj", "submission"],
    ["#/queue", ".queue-meter", "queue"],
  ]) {
    await go(t, hash, sel);
    check(`Teacher ${name} renders`, true);
    await shot(t, "teacher-" + name);
    await viewport(t, 390, 844);
    check(`Teacher ${name} fits mobile`, await fits(t));
    await viewport(t, 1440, 900);
  }
  await go(t, "#/exams/1", "#problem-search");
  await text(t, "#problem-search", "Dãy con");
  check(
    "Problem search filters existing data",
    await t.eval(
      '[...document.querySelectorAll("[data-problem-row]")].filter(x=>!x.hidden).length===1',
    ),
  );
  await click(t, "#new-problem");
  await t.waitFor('document.querySelector(".modal [name=statement]")');
  check(
    "Problem form retains limits and statement",
    await t.eval('!!document.querySelector(".modal [name=time_limit_ms]")'),
  );
  await click(t, ".modal-close");
  check("No uncaught JavaScript exceptions", exceptions.length === 0);
  console.log(`\n${checks} browser checks passed. Screenshots: artifacts/ui/`);
} catch (e) {
  console.error(e);
  console.error("Browser exceptions:", exceptions);
  process.exitCode = 1;
} finally {
  browser?.close();
  await server.stop();
}
