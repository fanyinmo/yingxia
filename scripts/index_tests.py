"""Rebuild the static Kotlin @Test inventory; never execute tests or Android tools.

Usage: python scripts/index_tests.py [--date YYYY-MM-DD] [--check]
Counts concrete annotated functions once, not parameterized invocations or cases.
"""
import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
STATUS = "SOURCE_INVENTORY_ONLY_NOT_EXECUTION"
OUTPUT = ROOT / "docs/testing"
ANNOTATION = r"@[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*(?:\s*\([^()]*\))?"
MODIFIER = r"\b(?:public|private|protected|internal|open|final|override|suspend|inline|tailrec|operator|infix|abstract|external)\b"
TEST_FUN = re.compile(
    r"@(?:org\.junit\.)?Test\b(?:\s*\([^()]*\))?\s*"
    rf"(?P<modifiers>(?:(?:{ANNOTATION}|{MODIFIER})\s*)*)"
    r"fun\s+(?P<name>`[^`\n]+`|[A-Za-z_]\w*)\s*\("
)
ASSUMPTION = re.compile(r"\b(?:Assume\.)?assume\w*\s*\(")


def mask_noncode(source):
    """Preserve offsets/newlines while masking comments and string/char literals."""
    chars = list(source)
    i = 0
    while i < len(source):
        start = i
        if source.startswith("//", i):
            end = source.find("\n", i)
            i = len(source) if end < 0 else end
        elif source.startswith("/*", i):
            i += 2
            depth = 1
            while i < len(source) and depth:
                if source.startswith("/*", i):
                    depth += 1
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = len(source) if end < 0 else end + 3
        elif source[i] in "\"'":
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
        else:
            i += 1
            continue
        for pos in range(start, min(i, len(source))):
            if chars[pos] not in "\r\n":
                chars[pos] = " "
    return "".join(chars)


def close_parenthesis(code, start):
    depth = 1
    for pos in range(start + 1, len(code)):
        if code[pos] == "(":
            depth += 1
        elif code[pos] == ")":
            depth -= 1
            if depth == 0:
                return pos
    raise ValueError("Unclosed annotated function parameter list")


def inventory(compilation_date):
    classes = []
    for group, source_set in [("JVM", "test"), ("Android", "androidTest")]:
        for path in sorted((ROOT / f"app/src/{source_set}").rglob("*.kt")):
            raw = path.read_bytes()
            source = raw.decode("utf-8-sig")
            code = mask_noncode(source)
            methods = []
            details = []
            for match in TEST_FUN.finditer(code):
                if re.search(r"\b(?:abstract|external)\b", match["modifiers"]):
                    continue
                close = close_parenthesis(code, match.end() - 1)
                body = re.search(r"[={]|\b(?:fun|class)\b|@", code[close + 1:])
                if body is None or body.group() not in {"=", "{"}:
                    continue
                name = match["name"].strip("`")
                methods.append(name)
                details.append({
                    "name": name,
                    "annotation_line": code.count("\n", 0, match.start()) + 1,
                    "has_declared_parameters": bool(code[match.end():close].strip()),
                    "status": STATUS,
                })
            annotations = len(re.findall(r"@(?:org\.junit\.)?Test\b", code))
            if annotations != len(methods):
                raise ValueError(f"Review unindexed/non-concrete @Test declarations: {path.relative_to(ROOT)}")
            if not methods:
                continue
            if not re.search(rf"\bclass\s+{re.escape(path.stem)}\b", code):
                raise ValueError(f"Review test class/file ownership: {path.relative_to(ROOT)}")
            if len(methods) != len(set(methods)):
                raise ValueError(f"Review duplicate annotated method names: {path.relative_to(ROOT)}")
            classes.append({
                "group": group,
                "class": path.stem,
                "source": path.relative_to(ROOT).as_posix(),
                "source_sha256": hashlib.sha256(raw).hexdigest(),
                "status": STATUS,
                "test_methods": methods,
                "method_details": details,
                "assumption_call_lines": [code.count("\n", 0, m.start()) + 1 for m in ASSUMPTION.finditer(code)],
                "ignore_annotation_present": bool(re.search(r"@(?:org\.junit\.)?Ignore\b", code)),
                "parameterized_or_dynamic_markers_present": bool(re.search(r"\b(?:Parameterized|TestFactory|DynamicTest|TestTemplate|RepeatedTest)\b", code)),
            })
    return {
        "date": compilation_date,
        "status": STATUS,
        "generator": "scripts/index_tests.py",
        "counting_rule": "One concrete Kotlin @Test fun declaration once; comments/string literals excluded; no execution or pass inference.",
        "scope": ["app/src/test/**/*.kt", "app/src/androidTest/**/*.kt"],
        "limitations": [
            "Static method counts do not expand parameterized/data-driven/dynamic invocations or loops, and are not test-manual case counts.",
            "Assumptions, opt-in arguments, device/network/SAF conditions and ignored tests may prevent assertions from running; source presence is never PASS.",
            "Assumption lines are class-level source hints, not a call-graph analysis or a claim that every method in the class is conditional.",
            "Java/JUnit3/non-@Test declarations, parser-lab JavaScript tests and generated sources are outside this Kotlin fun inventory.",
            "New annotation syntax or class/file ownership patterns fail generation for review rather than silently inventing a count.",
        ],
        "jvm_count": sum(len(c["test_methods"]) for c in classes if c["group"] == "JVM"),
        "android_count": sum(len(c["test_methods"]) for c in classes if c["group"] == "Android"),
        "classes": classes,
    }


def markdown(data):
    out = ["# 当前自动测试源索引", "", f"编制日期：{data['date']}。状态：`{STATUS}`。", "",
           "仅扫描当前源码，不判定本轮是否执行、跳过或通过；实际结果须另查指定构建/设备的完整执行报告。", "",
           f"JVM `@Test fun` 方法 **{data['jvm_count']}** 个；Android **{data['android_count']}** 个。每个具体方法声明只计一次，不是手册用例数。", "",
           "重建：`python scripts/index_tests.py`；只读检查一致性：`python scripts/index_tests.py --check`；固定日期可加 `--date YYYY-MM-DD`。脚本只读源码并生成这两份索引，不执行测试、ADB或构建。", "",
           "参数化、动态生成、循环内数据集及重复运行不展开计数。`assume`、显式opt-in、设备/网络/SAF条件及`@Ignore`可能使断言未执行，不能把JUnit汇总数字或本索引条目直接记为PASS。下列assume位置是类级提示，不等于类内每个方法均有条件。", "",
           "范围：`app/src/test/**/*.kt`与`app/src/androidTest/**/*.kt`的具体`@Test fun`；排除注释/字符串、Java/JUnit3/非@Test、parser-lab JavaScript与生成源码。", ""]
    for group in ["JVM", "Android"]:
        out.extend([f"## {group}", ""])
        for item in data["classes"]:
            if item["group"] != group:
                continue
            out.extend([f"### {item['class']} ({len(item['test_methods'])})", "", f"源码：[{item['source']}](../../{item['source']})", ""])
            notes = []
            if item["assumption_call_lines"]:
                notes.append("存在assume条件调用（源码行 " + ", ".join(map(str, item["assumption_call_lines"])) + "），须另核实opt-in/实际执行条件")
            if item["ignore_annotation_present"]:
                notes.append("存在@Ignore，源码登记不表示断言执行")
            if item["parameterized_or_dynamic_markers_present"]:
                notes.append("存在参数化/动态标记，调用次数不由本索引推算")
            if any(m["has_declared_parameters"] for m in item["method_details"]):
                notes.append("存在有参数的方法声明，不推算参数集或JUnit可运行性")
            if notes:
                out.extend(["条件边界：" + "；".join(notes) + "。", ""])
            out.extend(f"- `{m['name']}`（源码行 {m['annotation_line']}；仅源码登记）" for m in item["method_details"])
            out.append("")
    return "\n".join(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", default=date.today().isoformat(), type=lambda value: date.fromisoformat(value).isoformat())
    parser.add_argument("--check", action="store_true", help="Compare expected files without modifying them")
    args = parser.parse_args()
    data = inventory(args.date)
    expected = {
        OUTPUT / "automatic_test_index.json": json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        OUTPUT / "AUTOMATED_TEST_INDEX.md": markdown(data),
    }
    if args.check:
        stale = [str(path.relative_to(ROOT)) for path, text in expected.items() if not path.is_file() or path.read_text(encoding="utf-8-sig") != text]
        if stale:
            parser.exit(1, "Source inventory differs: " + ", ".join(stale) + "\n")
    else:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        for path, text in expected.items():
            path.write_text(text, encoding="utf-8", newline="\n")
    print(f"{STATUS}: JVM={data['jvm_count']} Android={data['android_count']} classes={len(data['classes'])}")


if __name__ == "__main__":
    main()
