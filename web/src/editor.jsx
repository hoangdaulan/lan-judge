import React, { useRef, useState } from "react";
const tokens =
  /(R"[^\s()\\]{0,16}\([\s\S]*?\)[^\s()\\]{0,16}"|\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|^[ \t]*#[^\n]*|\b(?:alignas|alignof|auto|bool|break|case|catch|char|char16_t|char32_t|class|const|constexpr|continue|decltype|default|delete|do|double|else|enum|explicit|extern|false|float|for|friend|if|inline|int|long|mutable|namespace|new|noexcept|nullptr|operator|private|protected|public|register|return|short|signed|sizeof|static|static_assert|struct|switch|template|this|thread_local|throw|true|try|typedef|typename|union|unsigned|using|virtual|void|volatile|wchar_t|while)\b|\b(?:0[xX][\da-fA-F]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)[uUlLfF]*\b)/gm;
function highlight(value) {
  const out = [];
  let start = 0;
  for (const m of value.matchAll(tokens)) {
    if (m.index > start) out.push(value.slice(start, m.index));
    const s = m[0],
      type =
        s.startsWith("//") || s.startsWith("/*")
          ? "comment"
          : /^[ \t]*#/.test(s)
            ? "preprocessor"
            : /^(?:["']|R")/.test(s)
              ? "string"
              : /^\d/.test(s)
                ? "number"
                : "keyword";
    out.push(
      <span key={m.index} className={`syntax-${type}`}>
        {s}
      </span>,
    );
    start = m.index + s.length;
  }
  out.push(value.slice(start) + "\n");
  return out;
}

const pairs = { "{": "}", "(": ")", "[": "]", '"': '"', "'": "'" };
const closingBrackets = new Set(["}", ")", "]"]);
const indentUnit = "    ";

function escaped(value, index) {
  let slashes = 0;
  while (index > 0 && value[--index] === "\\") slashes++;
  return slashes % 2 === 1;
}

function numericPrefix(value, end) {
  let start = end;
  while (start > 0 && /[\w.']/.test(value[start - 1])) start--;
  return /^\d/.test(value.slice(start, end));
}

// Ignore brackets in comments and literals, including C++ raw strings and
// apostrophes used as numeric separators (1'000, 0xA'B).
function contextAt(value, end) {
  let context = "code",
    rawEnd = "";
  for (let i = 0; i < end; i++) {
    const c = value[i],
      next = value[i + 1];
    if (context === "line-comment") {
      if (c === "\n") context = "code";
    } else if (context === "block-comment") {
      if (c === "*" && next === "/" && i + 1 < end) {
        context = "code";
        i++;
      }
    } else if (context === "raw-string") {
      if (value.startsWith(rawEnd, i) && i + rawEnd.length <= end) {
        context = "code";
        i += rawEnd.length - 1;
      }
    } else if (context === "string" || context === "character") {
      if (c === "\\") i++;
      else if (c === (context === "string" ? '"' : "'")) context = "code";
    } else if (c === "/" && next === "/" && i + 1 < end) {
      context = "line-comment";
      i++;
    } else if (c === "/" && next === "*" && i + 1 < end) {
      context = "block-comment";
      i++;
    } else if (c === "R" && next === '"') {
      const raw = /^R"([^\s()\\]{0,16})\(/.exec(value.slice(i));
      if (raw && i + raw[0].length <= end) {
        context = "raw-string";
        rawEnd = `)${raw[1]}"`;
        i += raw[0].length - 1;
      }
    } else if (c === '"') context = "string";
    else if (
      c === "'" &&
      !(numericPrefix(value, i) && /[\da-fA-F]/.test(next || " "))
    )
      context = "character";
  }
  return context;
}

export function CodeEditor({ value, onChange, fontSize }) {
  const textarea = useRef(null),
    gutter = useRef(null),
    layer = useRef(null);
  const [cursor, setCursor] = useState([1, 1]),
    [scroll, setScroll] = useState(0);
  const position = () => {
    const t = textarea.current,
      before = t.value.slice(0, t.selectionStart).split("\n");
    setCursor([before.length, before.at(-1).length + 1]);
  };
  const key = (e) => {
    // Let IMEs finish composing and preserve shortcuts such as Cmd+Enter.
    const altGraph = e.getModifierState("AltGraph");
    if (
      e.nativeEvent.isComposing ||
      e.keyCode === 229 ||
      e.metaKey ||
      (e.ctrlKey && !altGraph) ||
      (e.altKey && !altGraph && e.key.length !== 1)
    )
      return;
    if (e.key === "Escape") {
      e.currentTarget
        .closest(".editor-panel")
        ?.querySelector("button")
        ?.focus();
      return;
    }
    const t = e.currentTarget,
      { selectionStart: a, selectionEnd: b, value: v } = t;
    const replace = (text, start, end, caret, selectionEnd = caret) => {
      e.preventDefault();
      t.setSelectionRange(start, end);
      const expected = v.slice(0, start) + text + v.slice(end);
      // Native editing keeps these changes in the browser's Undo history.
      // setRangeText is the fallback for browsers without this command.
      try {
        t.ownerDocument.execCommand(
          text ? "insertText" : "delete",
          false,
          text,
        );
      } catch {
        // Fall back to the textarea editing API below.
      }
      if (t.value !== expected) t.setRangeText(text, start, end, "end");
      t.setSelectionRange(caret, selectionEnd);
      onChange(t.value);
      position();
    };
    if (e.key === "Tab") {
      if (!e.shiftKey && a === b) {
        replace(indentUnit, a, b, a + indentUnit.length);
        return;
      }
      const start = v.lastIndexOf("\n", a - 1) + 1,
        block = v.slice(start, b),
        changed = e.shiftKey
          ? block.replace(/^ {1,4}/gm, "")
          : block.replace(/^/gm, indentUnit);
      replace(changed, start, b, start, start + changed.length);
      return;
    }
    if (
      e.key !== "Enter" &&
      e.key !== "Backspace" &&
      !Object.hasOwn(pairs, e.key) &&
      !closingBrackets.has(e.key)
    )
      return;
    const context = contextAt(v, a),
      before = v[a - 1],
      after = v[b],
      quote = e.key === '"' || e.key === "'";
    if (
      a === b &&
      e.key === after &&
      ((context === "code" && closingBrackets.has(e.key)) ||
        (!escaped(v, a) &&
          ((e.key === '"' && context === "string") ||
            (e.key === "'" && context === "character"))))
    ) {
      e.preventDefault();
      t.setSelectionRange(a + 1, a + 1);
      position();
      return;
    }
    if (e.key === "Backspace") {
      if (
        a === b &&
        Object.hasOwn(pairs, before) &&
        pairs[before] === after &&
        (context === "code" ||
          ((before === '"' || before === "'") &&
            !escaped(v, a - 1) &&
            context === (before === '"' ? "string" : "character")))
      )
        replace("", a - 1, b + 1, a - 1);
      return;
    }
    if (e.key === "Enter") {
      const line = v.slice(v.lastIndexOf("\n", a - 1) + 1, a),
        indent = /^[\t ]*/.exec(line)[0],
        opening = line.trimEnd().at(-1),
        extra = context === "code" && "{[(".includes(opening) ? indentUnit : "",
        closing = /^[\t ]*([}\])])/.exec(v.slice(b));
      if (a === b && extra && closing?.[1] === pairs[opening]) {
        replace(
          `\n${indent}${extra}\n${indent}`,
          a,
          b + closing[0].length - 1,
          a + 1 + indent.length + extra.length,
        );
      } else if (indent || extra) {
        replace(
          `\n${indent}${extra}`,
          a,
          b,
          a + 1 + indent.length + extra.length,
        );
      }
      return;
    }
    if (!Object.hasOwn(pairs, e.key) || context !== "code") return;
    if (a !== b) {
      replace(e.key + v.slice(a, b) + pairs[e.key], a, b, a + 1, b + 1);
      return;
    }
    // Opening a pair before an identifier should remain an ordinary keystroke.
    if (after && !/[\s)\]};,:]/.test(after)) return;
    if (quote && (escaped(v, a) || /[\w$]/.test(before || " "))) return;
    replace(e.key + pairs[e.key], a, b, a + 1);
  };
  return (
    <div
      className="code-editor-wrap"
      style={{
        "--editor-font-size": `${fontSize}px`,
        "--editor-line-height": `${Math.round(fontSize * 1.7)}px`,
      }}
    >
      <span className="sr-only" id="editor-help">
        Tự đóng cặp ngoặc và dấu nháy. Enter để xuống dòng và tự thụt lề. Tab để
        thụt dòng, Shift + Tab để giảm thụt dòng. Escape chuyển đến thanh công
        cụ. Ctrl hoặc Command + Enter để nộp bài.
      </span>
      <div className="code-editor">
        <div className="gutter" id="gutter" ref={gutter} aria-hidden="true">
          {Array.from({ length: value.split("\n").length }, (_, i) => (
            <div key={i} className={cursor[0] === i + 1 ? "current-line" : ""}>
              {i + 1}
            </div>
          ))}
        </div>
        <div className="code-area">
          <div
            className="active-editor-line"
            style={{
              top: 18 + (cursor[0] - 1) * Math.round(fontSize * 1.7) - scroll,
            }}
          />
          <pre className="syntax-layer" ref={layer} aria-hidden="true">
            {highlight(value)}
          </pre>
          <textarea
            ref={textarea}
            id="code"
            className="code"
            aria-label="Mã nguồn C++17"
            aria-describedby="editor-help"
            value={value}
            onChange={(e) => {
              onChange(e.target.value);
              position();
            }}
            onKeyDown={key}
            onSelect={position}
            onScroll={(e) => {
              layer.current.scrollTop = e.target.scrollTop;
              layer.current.scrollLeft = e.target.scrollLeft;
              gutter.current.scrollTop = e.target.scrollTop;
              setScroll(e.target.scrollTop);
            }}
            spellCheck="false"
            autoComplete="off"
            autoCorrect="off"
            autoCapitalize="off"
            placeholder={
              "// Viết lời giải C++17 của bạn tại đây.\n// Hoặc tải lên một file .cpp từ thanh công cụ."
            }
          />
        </div>
      </div>
      <div className="cursor-position mono">
        Ln {cursor[0]}, Col {cursor[1]}
      </div>
    </div>
  );
}
