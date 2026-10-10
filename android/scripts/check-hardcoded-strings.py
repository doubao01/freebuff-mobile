#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
硬编码中文检查(可直接进 CI)。

`check-translations.py` 只能保证「已经搬进 strings.xml 的文案」各语言一致;
看不见**还写在 Kotlin 里、英文设备上会直接冒出来的中文**。这个脚本补上那一半:

- 扫 `app/`、`core/`、`feature/` 下的 .kt(测试源码不算:测试断言的本来就是数据层的原文),
  找出代码里的中文字面量(注释、KDoc、Log 调用不算);
- 与 `scripts/hardcoded-strings-baseline.txt` 逐文件比**数量**:
  - 比基线多 → **失败**(新写死的中文,应该放进 `res/values/strings.xml`);
  - 比基线少 → 提示把基线收紧(迁移完成就顺手 `--update`);
  - 基线里没有的新文件只要出现中文也算失败。

为什么用「基线 + 棘轮」而不是白名单:提示词、工具说明书这类**故意不翻译**的文案
(见 docs/i18n-and-accessibility.md §2)也在同一个文件里,白名单只能整文件放行,
棘轮则能挡住「在已迁移的文件里又写死一句中文」,同时不逼着人先搬完存量。

用法(在 android/ 目录下):
    python scripts/check-hardcoded-strings.py            # 检查,增量即失败(退出码 1)
    python scripts/check-hardcoded-strings.py --update   # 按当前代码重写基线
    python scripts/check-hardcoded-strings.py --list     # 打印每处位置(排查用)
"""
import argparse
import io
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOTS = ["app", "core", "feature"]
SCAN_DIRS = ("/src/main/",)          # 只看 main 源码:测试里的中文是断言用的数据
BASELINE = os.path.join("scripts", "hardcoded-strings-baseline.txt")

ZH = re.compile(u"[\u4e00-\u9fff]")
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
LOGCALL = re.compile(r"Log\.[iwed]\(")


def strip_line(line, in_block):
    """→ (去注释后的代码, 是否仍在块注释里)。

    字符串里的 `//` 不构成注释,所以按状态机走一遍:块注释、字符串、字符三个状态互斥。
    缺点:模板字符串里的 `${...}` 不单独分析 —— 这里的判定只需要大致正确。
    """
    out = []
    i = 0
    in_str = False
    while i < len(line):
        ch = line[i]
        nxt = line[i + 1] if i + 1 < len(line) else ""
        if in_block:
            if ch == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            out.append(ch)
            if ch == "\\":
                if nxt:
                    out.append(nxt)
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == "/" and nxt == "/":
            break                                   # 行注释,后面都不要
        if ch == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if ch == '"':
            in_str = True
        out.append(ch)
        i += 1
    return "".join(out), in_block


def literals_in(path):
    """→ [(行号, 字面量)]:该文件代码里出现的中文字面量。"""
    found = []
    in_block = False
    try:
        raw = io.open(path, encoding="utf-8", errors="ignore").read()
    except OSError:
        return found
    for lineno, line in enumerate(raw.split("\n"), 1):
        code, in_block = strip_line(line, in_block)
        if not code or LOGCALL.search(code):
            continue
        for m in LITERAL.finditer(code):
            if ZH.search(m.group(1)):
                found.append((lineno, m.group(1)))
    return found


def scan():
    """→ {相对路径: [(行号, 字面量)]},只含出现中文的文件。"""
    hits = {}
    for root in ROOTS:
        for dirpath, _dirs, files in os.walk(root):
            norm = dirpath.replace(os.sep, "/")
            if "/build" in norm or ".toolchain" in norm:
                continue
            if not any(d in norm + "/" for d in SCAN_DIRS):
                continue
            for f in sorted(files):
                if not f.endswith(".kt"):
                    continue
                p = os.path.join(dirpath, f)
                found = literals_in(p)
                if found:
                    hits[p.replace(os.sep, "/")] = found
    return hits


def read_baseline():
    if not os.path.exists(BASELINE):
        return {}
    out = {}
    for line in io.open(BASELINE, encoding="utf-8").read().split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1].isdigit():
            out[parts[0]] = int(parts[1])
    return out


def write_baseline(hits):
    io.open(BASELINE, "w", encoding="utf-8", newline="\n").write(
        "# 硬编码中文基线:路径 + 允许的字面量数量。\n"
        "# 由 scripts/check-hardcoded-strings.py --update 生成;数值只能降不能升。\n"
        "# 判定规则与「为什么留这些」见该脚本头部与 docs/i18n-and-accessibility.md §2/§4。\n"
        + "".join("%s %d\n" % (p, len(v)) for p, v in sorted(hits.items()))
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--update", action="store_true", help="按当前代码重写基线")
    ap.add_argument("--list", action="store_true", help="打印每一处位置")
    args = ap.parse_args()

    hits = scan()
    if args.list:
        for p, found in sorted(hits.items()):
            for lineno, lit in found:
                print("%s:%d: %s" % (p, lineno, lit))
    if args.update:
        write_baseline(hits)
        print("已写入 %s(%d 个文件)" % (BASELINE, len(hits)))
        return 0

    base = read_baseline()
    if not base:
        print("!! 找不到 %s,先跑一次 --update" % BASELINE)
        return 1

    grown = []      # 新增/变多:失败
    shrunk = []     # 变少:提示收紧基线
    for p in sorted(set(hits) | set(base)):
        now = len(hits.get(p, []))
        was = base.get(p, 0)
        if now > was:
            grown.append((p, was, now, hits.get(p, [])))
        elif now < was:
            shrunk.append((p, was, now))

    for p, was, now, found in grown:
        print("!! %s:硬编码中文 %d → %d 处(基线 %d)" % (p, was, now, was))
        for lineno, lit in found:
            print("     %s:%d: %s" % (p, lineno, lit))
    for p, was, now in shrunk:
        print("~~ %s:已降到 %d 处(基线 %d),可以 --update 收紧" % (p, now, was))

    total = sum(len(v) for v in hits.values())
    print("扫了 %d 个文件、%d 处硬编码中文;超基线 %d 个文件,%d 个文件可收紧"
          % (len(hits), total, len(grown), len(shrunk)))
    return 1 if grown else 0


if __name__ == "__main__":
    sys.exit(main())
