#!/usr/bin/env python3
"""dist/update.json(App 内「检查更新」的数据源)的生成与校验。

生成 —— 发版工作流用,把标签版本与提交标题写进清单并回推 master:
    python3 scripts/update-manifest.py 1.2.0 [--prev v1.1.0] [--apk-file <产物路径>]
校验 —— CI 用,清单与产品版本号不允许漂移:
    python3 scripts/update-manifest.py --check
可读变更说明 —— 发版工作流用它生成 GitHub Release 正文:
    python3 scripts/update-manifest.py 1.2.0 --release-notes [--prev v1.1.0]

清单格式(UpdateRepository 解析):
    {
      "version": "1.2.0",
      "url": "https://github.com/<owner>/<repo>/releases/latest",
      "summary": "一句话可读摘要(更新面板顶部那行)",
      "notes": ["..."],
      "apk": {
        "url": "https://github.com/<owner>/<repo>/releases/download/v1.2.0/FreebuffMobile-1.2.0-release.apk",
        "sha256": "<64 位小写十六进制>",
        "size": 1488526
      }
    }

`apk` 是可选块:带上它 App 就能在面板里**直接下载并校验安装包**而不是只跳发布页;
没有(老清单、或本地没传 --apk-file)时 App 退化成「前往下载」。

校验规则:
  * JSON 合法;version 为 x.y.z;url 为 https 链接;notes 是非空字符串数组
  * summary 若存在必须是非空字符串;apk 若存在:url 必须 https、sha256 必须 64 位十六进制、size 必须为正
  * version 不得高于 android/version.properties 的 versionName
    (高于 = 所有用户都会看到一个永远装不上的更新)
  * version 低于 versionName 只警告:未发版的版本号提升属正常,发版工作流会自动对齐
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
MANIFEST = REPO_ROOT / "dist" / "update.json"
VERSION_PROPS = REPO_ROOT / "android" / "version.properties"
FALLBACK_SLUG = "doubao01/freebuff-mobile"
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
APK_ASSET = "FreebuffMobile-{label}-release.apk"

# 更新说明分组(顺序即展示顺序):同一个提交标题只会落进一组,`其他` 兜底。
NOTE_GROUPS = ("修复", "新增", "改进", "文档", "发版与工程", "其他")
_GROUP_HINTS = (
    ("发版与工程", ("发版", "刷新检查更新数据源", "工作流", "构建", "签名", "ci:", "[skip ci]")),
    ("修复", ("修复", "修回", "修掉", "修正", "fix", "bug", "回归")),
    ("文档", ("文档", "说明书", "readme", "docs")),
    ("新增", ("新增", "加入", "支持", "引入", "落地", "接入", "打通")),
    ("改进", ("改进", "优化", "简化", "加固", "统一", "打磨", "重构", "收敛")),
)
# 子串兜底时把「发版与工程」放最后:一条提交里顺带提到发版,不该盖掉它本身的修复/新增属性。
_GROUP_HINTS_SUBSTR = tuple(sorted(_GROUP_HINTS, key=lambda kv: kv[0] == "发版与工程"))


def fail(msg: str) -> None:
    print("::error::" + msg, file=sys.stderr)
    raise SystemExit(1)


def warn(msg: str) -> None:
    print("::warning::" + msg)


def app_version_name() -> str:
    """读 android/version.properties 的 versionName(版本号唯一来源)。"""
    if not VERSION_PROPS.exists():
        fail(f"缺少 {VERSION_PROPS} —— 版本号唯一来源,由 app 与 core:model 共同派生")
    for raw in VERSION_PROPS.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        if key.strip() == "versionName":
            version = value.strip()
            if not VERSION_RE.match(version):
                fail(f"android/version.properties 的 versionName 非法: '{version}'")
            return version
    fail("android/version.properties 里没有 versionName")


def repo_slug() -> str:
    """owner/repo:CI 用 GITHUB_REPOSITORY,本机从 origin 推导。"""
    slug = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if slug:
        return slug
    out = git("remote", "get-url", "origin")
    if out:
        url = out.strip().removesuffix(".git")
        if url.startswith("git@github.com:"):
            return url[len("git@github.com:"):]
        if "github.com/" in url:
            return url.split("github.com/", 1)[1]
    return FALLBACK_SLUG


def git(*args: str) -> str | None:
    """跑一条 git 命令并返回 stdout。

    encoding 必须显式钉成 utf-8:提交标题是 UTF-8,而 text=True 在 Windows 上默认按本地编码
    (GBK)解码 —— 解不开的字节会在 subprocess 的**读线程**里抛 UnicodeDecodeError,异常被线程
    吞掉、git() 静默返回 None,更新说明就整段退化成「自动发版」而不报错(本机实测踩到)。
    """
    try:
        done = subprocess.run(
            ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True,
            encoding="utf-8", errors="replace", check=True,
        )
        return done.stdout
    except (OSError, subprocess.CalledProcessError):
        return None


def resolve_ref(version: str, ref: str) -> str:
    """把版本号落到一个真实存在的 git 引用上:优先显式 --ref,其次 v<version>,最后 <version>。

    发版工作流跑在标签上,标签名带 v 前缀;直接拿裸版本号当 revision 会静默失败,
    更新说明就退化成默认文案。
    """
    for cand in ([ref] if ref else []) + [f"v{version}", version]:
        if git("rev-parse", "--verify", f"{cand}^{{commit}}"):
            return cand
    # 解析不出来时退回 HEAD:直接把不存在的名字交给 git log 会被当成路径,
    # 拿到的是一堆不相干的提交标题。
    warn(f"git 里找不到 {ref or version} 对应的提交,更新说明改用 HEAD 的最近提交")
    return "HEAD"


def notes_from_git(prev: str, ref: str) -> list[str]:
    """更新说明 = 上一个标签到本次版本之间的提交标题。"""
    if prev:
        log = git("log", "--pretty=format:%s", f"{prev}..{ref}")
        notes = [line.strip() for line in (log or "").splitlines() if line.strip()]
        if notes:
            return notes
    log = git("log", "-n", "20", "--pretty=format:%s", ref)
    notes = [line.strip() for line in (log or "").splitlines() if line.strip()]
    if not notes:
        warn("读不到提交历史(git log 失败或为空),更新说明退化为「自动发版」")
    return notes


def strip_ci(note: str) -> str:
    return re.sub(r"\s*\[skip ci\]\s*$", "", note).strip()


def classify(note: str) -> str:
    """把一条提交标题归入可读分组。先看开头(标题的主语在开头),再退回子串匹配。"""
    head = strip_ci(note)
    for title, hints in _GROUP_HINTS:
        if any(head.startswith(h) for h in hints):
            return title
    for title, hints in _GROUP_HINTS_SUBSTR:
        if any(h in head for h in hints):
            return title
    return "其他"


def summarize(notes: list[str]) -> str:
    """一句话摘要:优先挑一条修复,其次新增/改进,都没有就退回第一条。"""
    cleaned = [strip_ci(n) for n in notes if strip_ci(n)]
    if not cleaned:
        return ""
    for want in ("修复", "新增", "改进"):
        for note in cleaned:
            if classify(note) == want:
                return note
    return cleaned[0]


def apk_asset_name(label: str) -> str:
    return APK_ASSET.format(label=label)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def apk_block(version: str, apk_file: str, apk_url: str) -> dict | None:
    """产物指纹块。没传 --apk-file 就不写(老清单/本地干跑),App 会退化成跳发布页。"""
    if not apk_file:
        return None
    path = Path(apk_file)
    if not path.is_file():
        warn(f"--apk-file 指向的文件不存在({apk_file}),清单不带 apk 块 —— App 将只能跳到发布页下载")
        return None
    url = apk_url.strip() or f"https://github.com/{repo_slug()}/releases/download/v{version}/{apk_asset_name(version)}"
    if not url.startswith("https://"):
        fail(f"apk.url 必须是 https 链接(当前 {url!r})")
    return {"url": url, "sha256": sha256_file(path), "size": path.stat().st_size}


def release_notes_markdown(version: str, notes: list[str], interval: str) -> str:
    """GitHub Release 正文里的可读变更说明:一句话摘要 + 分组条目。"""
    grouped: dict[str, list[str]] = {g: [] for g in NOTE_GROUPS}
    for note in notes:
        text = strip_ci(note)
        if text:
            grouped[classify(text)].append(text)
    lines = [
        f"自动发布 v{version}({interval},共 {len(notes)} 项)。",
        "",
        "## 变更摘要",
        "",
        "**" + (summarize(notes) or "常规维护") + "**",
        "",
        "## 全部变更",
        "",
    ]
    for title in NOTE_GROUPS:
        if not grouped[title]:
            continue
        lines.append(f"### {title}({len(grouped[title])})")
        lines.extend(f"- {item}" for item in grouped[title])
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def parse_version(v: str) -> tuple[int, ...]:
    return tuple(int(p) for p in v.split("."))


def read_version(path: Path) -> str:
    """读清单里的版本号;文件不存在/不可解析时返回空串。"""
    if not path.exists():
        return ""
    try:
        value = json.loads(path.read_text(encoding="utf-8")).get("version", "")
    except (json.JSONDecodeError, UnicodeDecodeError, AttributeError):
        return ""
    return value if isinstance(value, str) and VERSION_RE.match(value) else ""


def cmd_release_notes(version: str, prev: str, ref: str = "") -> None:
    resolved = resolve_ref(version, ref)
    notes = notes_from_git(prev, resolved) or ["自动发版"]
    interval = "首个版本" if not prev else f"区间 {prev}..{resolved}"
    sys.stdout.write(release_notes_markdown(version, notes, interval))


def cmd_write(version: str, prev: str, ref: str = "", out: Path = MANIFEST,
              apk_file: str = "", apk_url: str = "") -> None:
    if not VERSION_RE.match(version):
        fail(f"版本号必须是 x.y.z 形态: '{version}'")
    current = read_version(out)
    if current and parse_version(version) < parse_version(current):
        fail(
            f"拒绝把清单降级:{out.name} 已是 v{current},本次却是 v{version} —— "
            "补发旧标签会让已升级的用户看到「新版本」;确实要回滚请手工改该文件"
        )
    resolved = resolve_ref(version, ref)
    notes = notes_from_git(prev, resolved) or ["自动发版"]
    payload = {
        "version": version,
        "url": f"https://github.com/{repo_slug()}/releases/latest",
        "summary": summarize(notes),
        "notes": notes,
    }
    apk = apk_block(version, apk_file, apk_url)
    if apk:
        payload["apk"] = apk
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    apk_note = f"apk={apk['size']}B sha256={apk['sha256'][:12]}…" if apk else "apk=未提供(App 只能跳发布页)"
    print(
        f"{out} -> {payload['version']}, {len(notes)} notes "
        f"(区间 {'<root>' if not prev else prev}..{resolved}), url={payload['url']}, {apk_note}"
    )


def cmd_check(path: Path = MANIFEST) -> None:
    if not path.exists():
        fail("dist/update.json 不存在 —— 它是 App 内「检查更新」的数据源")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        fail(f"dist/update.json 不是合法 JSON: {e}")
    if not isinstance(data, dict):
        fail("dist/update.json 顶层必须是对象")

    version = data.get("version")
    if not isinstance(version, str) or not VERSION_RE.match(version):
        fail(f"dist/update.json 的 version 必须是 x.y.z 形态: {version!r}")

    url = data.get("url", "")
    if not isinstance(url, str) or not url.startswith("https://"):
        fail(f"dist/update.json 的 url 必须是 https 链接(当前 {url!r})")

    notes = data.get("notes")
    if not isinstance(notes, list) or not notes or not all(
        isinstance(n, str) and n.strip() for n in notes
    ):
        fail("dist/update.json 的 notes 必须是非空字符串数组")

    summary = data.get("summary")
    if summary is not None and (not isinstance(summary, str) or not summary.strip()):
        fail(f"dist/update.json 的 summary 必须是非空字符串(当前 {summary!r})")

    # apk 是可选的:带上它 App 才能直接下载并校验安装包。
    apk = data.get("apk")
    if apk is not None:
        if not isinstance(apk, dict):
            fail("dist/update.json 的 apk 必须是对象")
        apk_url = apk.get("url", "")
        if not isinstance(apk_url, str) or not apk_url.startswith("https://"):
            fail(f"dist/update.json 的 apk.url 必须是 https 链接(当前 {apk_url!r})")
        digest = apk.get("sha256", "")
        if not isinstance(digest, str) or not SHA256_RE.match(digest):
            fail(f"dist/update.json 的 apk.sha256 必须是 64 位小写十六进制(当前 {digest!r})")
        size = apk.get("size")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            fail(f"dist/update.json 的 apk.size 必须是正整数(当前 {size!r})")
        apk_desc = f"apk={apk_url.rsplit('/', 1)[-1]} sha256={digest[:12]}… {size}B"
    else:
        warn("dist/update.json 没有 apk 块 —— App 内只能跳到发布页,无法直接下载校验")
        apk_desc = "apk=未提供"

    app = app_version_name()
    if parse_version(version) > parse_version(app):
        fail(
            f"dist/update.json 的 v{version} 高于产品版本 v{app}"
            " —— 用户会看到一个永远装不上的更新。发版请用发布工作流(它会从标签重建清单)"
        )
    if version != app:
        warn(f"dist/update.json 仍是 v{version},产品版本已是 v{app}(未发版属正常,发版后自动对齐)")
    print(f"dist/update.json OK: v{version}, {len(notes)} notes, url={url}, {apk_desc} (产品版本 v{app})")


def main() -> None:
    parser = argparse.ArgumentParser(description="生成/校验 dist/update.json")
    parser.add_argument("version", nargs="?", help="写入的版本号(x.y.z,通常等于发版标签去掉 v)")
    parser.add_argument("--prev", default="", help="更新说明的起点标签,如 v1.1.0")
    parser.add_argument("--ref", default="", help="本次版本的 git 引用(默认按 v<version> 探测)")
    parser.add_argument("--check", action="store_true", help="校验现有 dist/update.json 后退出")
    parser.add_argument("--out", default=str(MANIFEST), help="清单路径(默认 dist/update.json)")
    parser.add_argument("--apk-file", default="", help="本次发版的 APK 产物:写入 apk.sha256/size")
    parser.add_argument("--apk-url", default="", help="apk 直链(默认按 releases/download/v<version>/<资产名> 推导)")
    parser.add_argument("--release-notes", action="store_true", help="打印可读的 Release 正文(分组摘要),不写文件")
    args = parser.parse_args()

    path = Path(args.out)
    if args.check:
        cmd_check(path)
        return
    if not args.version and not args.release_notes:
        parser.error("需要给出版本号,或用 --check 校验")
    if args.release_notes:
        cmd_release_notes(args.version or app_version_name(), args.prev, args.ref)
        return
    cmd_write(args.version, args.prev, args.ref, path, args.apk_file, args.apk_url)


if __name__ == "__main__":
    main()
