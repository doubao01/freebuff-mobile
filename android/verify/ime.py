#!/usr/bin/env python3
"""往当前聚焦的输入框输入任意文本(含中文),用无界面 IME AdbKeyboard。

为什么不用 `input text`:adb 的 `input text` 只吃 ASCII,中文会变成乱码;
UI 上的 Compose 输入框也不可靠地接收 `input keyevent` 组合。AdbKeyboard 是
无界面 IME —— 有真实输入连接、不弹键盘、支持广播注入任意 Unicode。

用法:
  python verify/ime.py 你好,世界
  python verify/ime.py --restore      # 切回原来的输入法
"""
import base64
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adbenv  # noqa: E402
ADB = adbenv.ADB
ADBKB = 'com.android.adbkeyboard/.AdbIME'


def sh(*a):
    try:
        return subprocess.run([ADB, 'shell', *a], capture_output=True,
                              timeout=30).stdout.decode('utf-8', 'ignore')
    except subprocess.TimeoutExpired:
        print('!! adb timeout', a)
        return ''


def current_ime():
    out = sh('settings', 'get', 'secure', 'default_input_method')
    return out.strip()


def activate():
    sh('ime', 'enable', ADBKB)
    sh('ime', 'set', ADBKB)


def has_adb_keyboard():
    """设备上有没有 AdbKeyboard:CI 的模拟器没装它,得走 ASCII 回退。"""
    return 'com.android.adbkeyboard' in sh('ime', 'list', '-s')


SAVED_IME = 'freebuff_saved_ime'

# 「不会改写文本」的输入法:无界面输入法(AdbKeyboard)与语音输入法。
# 把默认输入法停到这些上面就够了 —— 一个都不留地全停,系统会再找一个顶上来,
# 于是「停用 → 被顶回 → 再停」反复折腾,输入法窗口一开一关还会让表单位移,点击全落空(实测踩到)。
SAFE_HINTS = ('adbkeyboard', 'voice', 'tts', 'speech')


def _safe_ime(ime_id):
    low = (ime_id or '').lower()
    return any(h in low for h in SAFE_HINTS)


def _ensure_safe_ime_enabled():
    """停用软键盘前先保证有一个「不会改写文本」的输入法可用。

    一个都没有时系统会拒绍停用(命令看着成功、default_input_method 却没变),
    输入就还是走软键盘 —— 现场完全是「输入没落进去」,看不出真因。
    """
    for ime_id in sh('ime', 'list', '-s').split():
        if ime_id and 'adbkeyboard' not in ime_id.lower() and _safe_ime(ime_id):
            sh('ime', 'enable', ime_id)
            return ime_id
    return None


def ensure_ime_free():
    """把**会改写文本的软键盘**停掉,让 `input text` 直接落进输入框(没有 AdbKeyboard 时的回退路径)。

    为什么必须处理:软键盘会把 `input text` 当成「正在输入」——字符停在 composing 里
    (无障碍树读到的 text 是空的,验证必然失败),而且它会跟着**应用语言**切到中文,
    把 : / . 换成全角(实测 Base URL 落进去变成 `https：／／。。100。22`,grep 什么都对不上)。
    停在无界面/语音输入法上之后,注入的是裸按键,字段直接提交文本。

    必须在**聚焦任何输入框之前**调用:已经聚焦再动输入法,输入连接会被拆掉,
    后面的按键只落进去一半(实测 Base URL 只剩 `https://`)。原输入法记在 secure 设置里,
    收尾由 restore_ime() 还回去。
    """
    if has_adb_keyboard():
        return False
    safe = _ensure_safe_ime_enabled()
    changed = False
    for _ in range(3):
        cur = sh('settings', 'get', 'secure', 'default_input_method').strip()
        if not cur or cur == 'null' or _safe_ime(cur):
            break
        # 只记第一个(原装的)输入法:之后的都是系统顶上来的,不该当成原装恢复
        if sh('settings', 'get', 'secure', SAVED_IME).strip() in ('', 'null'):
            sh('settings', 'put', 'secure', SAVED_IME, cur)
        sh('ime', 'disable', cur)
        print('! 已临时停用输入法 %s(软键盘会把 input text 改写/停在 composing)' % cur, flush=True)
        changed = True
        time.sleep(0.8)          # 等系统把下一个默认输入法顶上来
    # 钉住默认输入法:停用之后系统可能又把可用输入法顶回来(矩阵跑到一半又变回 Gboard 实测过,
    # 表现就是后面的注入被改写/断掉)。显式指到那个不会改写文本的,比依赖系统回落稳。
    now = sh('settings', 'get', 'secure', 'default_input_method').strip()
    if safe and now != safe:
        sh('settings', 'put', 'secure', 'default_input_method', safe)
        print('! 默认输入法已钉到 %s' % safe, flush=True)
        changed = True
    elif now and not _safe_ime(now):
        print('!! 只停成了一半:当前输入法仍是 %s(输入可能被软键盘改写)' % now, flush=True)
    return changed


def restore_ime():
    """把 ensure_ime_free() 摘掉的输入法装回去(幂等:没摘过就什么都不做)。"""
    cur = sh('settings', 'get', 'secure', SAVED_IME).strip()
    if not cur or cur == 'null':
        return False
    sh('ime', 'enable', cur)
    sh('ime', 'set', cur)
    sh('settings', 'delete', 'secure', SAVED_IME)
    print('输入法已恢复:', cur, flush=True)
    return True


def type_text(text):
    if not has_adb_keyboard():
        # 没有 AdbKeyboard 时退回 `input text`(只吃 ASCII)。矩阵提示词是 `case xxx`,
        # 全是 ASCII,够用;真出现中文就明说会乱码,而不是悄悄写错东西。
        if not text.isascii():
            print('!! 没有 AdbKeyboard,非 ASCII 文本在端上会乱码:', text[:20])
        ensure_ime_free()
        # 分段输入:`input text` 一次递长串会吞尾巴 —— 实测 57 字符只落地 40,
        # 31 字符的 Base URL 也少过 3 个(界面显示 `https://10.0.2.2:88`,看着像输入法在改,
        # 其实是注进去的尾巴丢了)。按短块递、块间留一拍,落点与短句一致。
        step = 8
        for i in range(0, len(text), step):
            sh('input', 'text', text[i:i + step].replace(' ', '%s'))
            time.sleep(0.35)
        print('typed', len(text), 'chars (input text, %d 段)' % ((len(text) + step - 1) // step))
        return
    activate()
    b64 = base64.b64encode(text.encode('utf-8')).decode('ascii')
    sh('am', 'broadcast', '-a', 'ADB_INPUT_B64', '--es', 'msg', b64)
    print('typed', len(text), 'chars')


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--restore':
        sh('ime', 'set', 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME')
        print('ime restored')
    else:
        type_text(sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read())
