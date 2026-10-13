#!/usr/bin/env python3
"""设备 UI 步骤执行器:把一串步骤写成 JSON/内联,逐条执行并打印屏幕文本。

为什么要它:通用的 tap 会点「可点祖先」的中心,遇到同名的标题(顶部)+按钮(底部)
会点到标题上;这里 tap 默认取**位置最低**的同名节点,正好是按钮(要点任意同名节点时用 tap_any)。

用法:
  python verify/flow.py '[{"tap":"设置"},{"sleep":1},{"texts":1}]'
步骤类型:
  {"tap":"文本"}        点最低的同名节点(精确匹配优先,再退化为包含)
  {"tap_any":"文本"}    点任意同名节点
  {"type":"内容"}       往当前聚焦的输入框输入(ASCII)
  {"wait":"文本", "t":6} 等文本出现
  {"seq":[ ... ]}       依次执行的子步骤
  {"sleep":1.2}         等待秒数
  {"texts":1}           打印当前屏幕文本
  {"scroll":-3}         滚屏(正=内容上移,负=内容下移)
  {"key":"back"}        按键
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adbenv  # noqa: E402  本地 .toolchain / CI 的 PATH 共用一份 adb 解析
ADB = adbenv.ADB


def use_utf8_stdout():
    """把 stdout/stderr 切到 UTF-8。

    为什么每个入口都要调:矩阵/引导的断言与现场打印里既有中文,也有「✦ ▣ ↻ ⓘ」这类字形图标。
    Windows 控制台默认 GBK,遇到图标字直接 `UnicodeEncodeError` —— 更糟的是它发生在
    **打印失败现场的时候**(dump_screen),于是真正的错误信息被一个编码异常盖掉。
    入口脚本自己调一次,不在 import 时改全局。
    """
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding='utf-8')       # py3.7+
        except Exception:
            pass


def wake_device():
    """把屏幕叫醒、去掉锁屏、并设成常亮。

    模拟器刚启动时可能停在一张黑屏/锁屏上:那时 uiautomator 拿不到根节点(dump 报
    `null root node`),整个跑批看起来像「应用没起来」。这一步是幂等的,每次跑批开始都做。
    """
    sh('input', 'keyevent', 'KEYCODE_WAKEUP', timeout=20)
    sh('wm', 'dismiss-keyguard', timeout=20)
    sh('svc', 'power', 'stayon', 'true', timeout=20)
    end = time.time() + 30
    while time.time() < end:
        t = dump_xml()
        if len(list(t.iter('node'))) > 1:
            return True
        time.sleep(1.0)
    print('!! 叫醒了但界面树还是空的', flush=True)
    return False


def pin_app_locale(pkg, tag='zh-Hans'):
    """把「应用级语言」钉成简体中文,返回设备上实际生效的值。

    为什么需要:矩阵与引导脚本点/断言的都是**中文文案**(「发送」「发起任务」「自定义模型」),
    而界面文案已经迁进资源文件(默认英文)。英文设备上应用渲染英文,所有点击静默 MISS,
    29 个场景会一起假 FAIL —— 看起来像应用全坏,其实只是语言不匹配。

    用系统的应用级语言(API 33+ 的 `cmd locale`)而不是改设备语言:不动系统、不用重启,
    而且和设置页的应用内语言开关是同一套机制(AppLanguage)。
    """
    sh('cmd', 'locale', 'set-app-locales', pkg, '--locales', tag, timeout=60)
    time.sleep(1.0)
    got = sh('cmd', 'locale', 'get-app-locales', pkg, timeout=60).stdout.decode('utf-8', 'ignore').strip()
    print('应用级语言:', got, flush=True)
    return got


def grant_notifications(pkg):
    """预先授予通知权限(API 33+),避免首启弹系统对话框。

    为什么必须做:App 在 MainActivity 里就请求 POST_NOTIFICATIONS —— **全新安装**的首启
    会被系统弹窗挡在前面,引导脚本等不到欢迎页就直接失败(实测报「启动后 60s 内既没看到
    欢迎页也没看到会话列表」,现场就是 Allow / Don't allow)。

    为什么用 `pm grant` 而不是去点弹窗:系统弹窗跟**设备语言**走(不受应用级语言影响),
    「Allow」/「允许」得两边都认;直接授权既确定又少一次点击。
    """
    r = sh('pm', 'grant', pkg, 'android.permission.POST_NOTIFICATIONS', timeout=30)
    err = (r.stderr or b'').decode('utf-8', 'ignore').strip()
    print('pm grant 通知权限:', 'ok' if not err else err.splitlines()[0], flush=True)


def dismiss_notification_dialog(timeout=12.0):
    """兜底:系统弹窗真出现了就点掉(旧系统没有 pm grant 的权限名、或引导前忘了授权)。

    按**设备语言**匹配(弹窗不由应用渲染),中文/英文两种写法都试。
    """
    end = time.time() + timeout
    while time.time() < end:
        t = dump_xml()
        for want in ('允许', 'Allow'):
            hit = [n for n in t.iter('node') if (n.get('text') or '').strip() == want]
            if hit:
                target = clickable_self_or_ancestor(t, hit[0])
                x, y = center(target.get('bounds'))
                sh('input', 'tap', str(x), str(y), timeout=20)
                print('dismiss 系统弹窗:', want, x, y, flush=True)
                return True
        time.sleep(0.8)
    return False


def sh(*a, **kw):
    """带超时的 adb shell:uiautomator 偶尔挂住不返回,没 timeout 会卡死整个跑批。"""
    kw.setdefault('timeout', 30)
    try:
        return subprocess.run([ADB, 'shell', *a], capture_output=True, **kw)
    except subprocess.TimeoutExpired:
        print('!! adb timeout', a)
        return subprocess.CompletedProcess([ADB, 'shell', *a], -1, b'', b'timeout')


def dump_xml():
    """拿当前界面 XML。必须先删旧文件:uiautomator 在动画/冷启动时会失败但不报错,
    旧文件还在就会被当成当前界面(白白误导一轮排查)。"""
    for _ in range(3):
        sh('rm', '-f', '/sdcard/ui.xml', timeout=20)
        sh('uiautomator', 'dump', '/sdcard/ui.xml', timeout=40)
        try:
            raw = subprocess.run([ADB, 'exec-out', 'cat', '/sdcard/ui.xml'],
                                 capture_output=True, timeout=40).stdout.decode('utf-8', 'ignore')
        except subprocess.TimeoutExpired:
            print('!! dump read timeout')
            raw = ''
        if raw.lstrip().startswith('<?xml'):
            open('verify/ui.xml', 'w', encoding='utf-8').write(raw)
            try:
                return ET.fromstring(raw)
            except Exception:
                pass
        time.sleep(1.0)
    print('!! dump failed')
    return ET.Element('empty')


def center(b):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b))
    return (x1 + x2) // 2, (y1 + y2) // 2


def candidates(t, want):
    """按文本找节点:精确匹配的优先;都没有则退化为包含匹配。"""
    hit = [n for n in t.iter('node') if (n.get('text') or '') == want]
    if not hit:
        hit = [n for n in t.iter('node') if want in (n.get('text') or '')]
    return hit


def clickable_self_or_ancestor(t, n):
    parents = {c: p for p in t.iter() for c in p}
    cur = n
    while True:
        if cur.get('clickable') == 'true':
            return cur
        if cur not in parents:
            return n
        cur = parents[cur]


def ime_shown():
    """软键盘窗口是否盖着屏幕。

    只看 mInputShown —— mIsInputViewShown 在收起之后仍然是 true,拿它判断会一直误判。
    """
    p = sh('dumpsys', 'input_method', timeout=40)
    return 'mInputShown=true' in (p.stdout or b'').decode('utf-8', 'ignore')


def hide_ime(tries=3):
    """收掉 IME,并在收起来之后等布局稳定。

    两个都必要:IME 盖着时点底部按钮等于点空;而 IME 收起会改变窗口 inset,
    面板会重新布局 —— 立刻按老坐标点还是会落空(实测「保存」第一下点不中就是这个)。
    """
    hid = False
    for _ in range(tries):
        if not ime_shown():
            break
        sh('input', 'keyevent', 'KEYCODE_BACK', timeout=20)
        hid = True
        time.sleep(0.9)
    ok = not ime_shown()
    if hid:
        time.sleep(1.2)
    return ok


def do_tap(want, lowest=True, timeout=10.0):
    end = time.time() + timeout
    while time.time() < end:
        t = dump_xml()
        hit = candidates(t, want)
        if hit:
            node = max(hit, key=lambda n: int(re.findall(r'\d+', n.get('bounds'))[1])) if lowest else hit[0]
            target = clickable_self_or_ancestor(t, node)
            x, y = center(target.get('bounds'))
            sh('input', 'tap', str(x), str(y), timeout=20)
            print('tap', want, x, y, '(self)' if target is node else '(ancestor)')
            return True
        time.sleep(0.6)
    print('MISS', want)
    return False


def do_wait(want, timeout=8.0):
    end = time.time() + timeout
    while time.time() < end:
        if any((n.get('text') or '') == want or want in (n.get('text') or '') for n in dump_xml().iter('node')):
            print('ok', want)
            return True
        time.sleep(0.5)
    print('TIMEOUT', want)
    return False


def do_scroll(times):
    for _ in range(abs(times)):
        if times > 0:
            sh('input', 'swipe', '540', '1600', '540', '900', '300')   # 内容上移
        else:
            sh('input', 'swipe', '540', '900', '540', '1600', '300')   # 内容下移


def run(steps):
    for s in steps:
        if 'seq' in s:
            run(s['seq'])
        elif 'tap' in s:
            do_tap(s['tap'], lowest=s.get('lowest', True), timeout=s.get('t', 10.0))
        elif 'tap_any' in s:
            do_tap(s['tap_any'], lowest=False, timeout=s.get('t', 10.0))
        elif 'type' in s:
            sh('input', 'text', s['type'].replace(' ', '%s'))
            print('typed', s['type'])
        elif 'wait' in s:
            do_wait(s['wait'], timeout=s.get('t', 8.0))
        elif 'sleep' in s:
            time.sleep(s['sleep'])
        elif 'key' in s:
            sh('input', 'keyevent', 'KEYCODE_' + s['key'].upper())
        elif 'scroll' in s:
            do_scroll(s['scroll'])
        elif 'texts' in s:
            for n in dump_xml().iter('node'):
                if n.get('text'):
                    print(' ', repr(n.get('text')))
        else:
            print('unknown step', s)


if __name__ == '__main__':
    run(json.loads(sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()))
