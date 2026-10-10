#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
翻译完整性检查(可直接进 CI)。

多语言最容易坏在两处,这里都对上:
1. **漏翻译**:默认语言加了一句,values-zh 没跟上 —— 运行期静默回落成英文,用户看到中英混排;
2. **格式参数不一致**:默认 `%1$s` 但译文写成 `%s`/漏掉 —— 轻则参数不代入,重则 `IllegalFormatException` 崩溃。

规则:
- 每个模块的 `values/`(默认语言)是所有 locale 的基准:key 集合必须完全一致(多了少了都报错);
- 每条 string 的 `%n$s` 占位符集合必须一致(顺序可比,数量与序号必须同)。
输出:逐条列出问题,末尾给汇总;有问题时退出码 1。

用法:python scripts/check-translations.py   (在 android/ 目录下执行)
"""
import os
import re
import sys
import glob
import xml.etree.ElementTree as ET

# Windows 控制台默认 GBK:强制 UTF-8 输出,否则中文报告在本地看是乱码
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ARGS = re.compile(r"%(\d+)\$[sd]|%[sd]")

# 无需翻译的占位(与代码里的 translatable="false" 对应,这里再兜一层防线)
IGNORE_KEYS = {"app_name", "welcome_brand"}


def find_strings_files():
    """→ [(模块目录, sourceSet, locale, 文件路径)];locale 为 '' 表示默认(values/)。"""
    out = []
    for path in glob.glob("**/src/*/res/values*/strings.xml", recursive=True):
        if any(seg in path.split(os.sep) for seg in ("build", ".toolchain", ".gradle")):
            continue
        parts = path.split(os.sep)
        res_i = parts.index("res")
        source_set = parts[res_i - 1]
        qualifier = parts[res_i + 1]
        locale = qualifier[len("values-"):] if qualifier.startswith("values-") else ""
        module = os.sep.join(parts[:res_i - 1]) or "."
        out.append((module, source_set, locale, path))
    return sorted(out)


def read_strings(path):
    """→ {key: 占位符集合}"""
    root = ET.parse(path).getroot()
    out = {}
    for el in root:
        if el.tag != "string":
            continue
        key = el.get("name")
        if not key:
            continue
        text = "".join(el.itertext())
        out[key] = set(ARGS.findall(text))
    return out


def main():
    groups = {}
    for module, source_set, locale, path in find_strings_files():
        groups.setdefault((module, source_set), {})[locale] = (path, read_strings(path))

    problems = 0
    checked = 0
    for (module, source_set), locales in sorted(groups.items()):
        if "" not in locales:
            print("!! %s/%s: 缺少默认语言 values/strings.xml,无法作为基准" % (module, source_set))
            problems += 1
            continue
        base_path, base = locales[""]
        base = {k: v for k, v in base.items() if k not in IGNORE_KEYS}
        for locale, (path, strings) in sorted(locales.items()):
            if locale == "":
                continue
            checked += 1
            strings = {k: v for k, v in strings.items() if k not in IGNORE_KEYS}
            missing = sorted(set(base) - set(strings))
            extra = sorted(set(strings) - set(base))
            fmt = sorted(k for k in set(base) & set(strings) if base[k] != strings[k])
            for k in missing:
                print("!! %s %s: 缺翻译 %s (基准 %s)" % (module, locale, k, base_path))
                problems += 1
            for k in extra:
                print("!! %s %s: 多出 key %s (基准里没有)" % (module, locale, k))
                problems += 1
            for k in fmt:
                print("!! %s %s: %s 的格式参数不一致 默认=%s 译文=%s"
                      % (module, locale, k, sorted(base[k]), sorted(strings[k])))
                problems += 1

    print("检查了 %d 组 locale(基准 %d 组);问题 %d 条" % (checked, len(groups), problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
