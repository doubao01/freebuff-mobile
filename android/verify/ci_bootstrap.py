#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把一台**全新安装**的设备带到「能跑矩阵」的状态(CI 首启,本地新机同样适用)。

矩阵只负责发 `case xxx` 并断言,它默认模型已经配好 —— 这一步补上的就是那个前提:
  访客模式 → 设置 → 自定义模型 → 添加 → 填 名称/Base URL → 打开「跳过 TLS 证书校验」
  → 拉取列表 → 点选模型 ID → 保存 → 选中为当前模型

为什么走 UI 而不是直接改库:自定义模型的 API Key 是 Keystore 加密的(库外写不进去),
而且这条路径本身就是用户第一次使用的真实路径 —— 顺带验证新手引导没坏。

**踩过的坑**:AdbKeyboard 只是「没有键盘界面」,它的 IME 窗口照样是 shown 状态并盖住
表单底部 —— 不把它收起来,「保存 / 拉取列表 / 保存」这些落在下半屏的按钮点上去毫无反应
(界面不动、库里也不写、还没有任何报错,极难查)。所以每次输入完都要先收 IME 再点。

用法(在 android/ 下,mock 已在跑):
  python verify/ci_bootstrap.py                         # 默认 https://10.0.2.2:8899/v1
  python verify/ci_bootstrap.py --clean                 # 先 pm clear,模拟全新安装
"""
import argparse
import os
import re
import sqlite3
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dbdump  # noqa: E402
import flow    # noqa: E402
import ime     # noqa: E402

PKG = 'com.freebuff.mobile'

# 设置页「模型」行的固定副标题(values-zh 的 set_model_sub):标题会随当前模型变,它不会。
SET_MODEL_SUB = '点击选择对话使用的模型'


def die(msg):
    print('!! ' + msg, flush=True)
    dump_screen()
    raise SystemExit(1)


def texts():
    return [(n.get('text') or '').strip() for n in flow.dump_xml().iter('node')
            if (n.get('text') or '').strip()]


def has(want):
    return any(want in x for x in texts())


def dump_screen(tag='当前界面'):
    """失败时把界面文本整屏打出来:CI 上没有截图可看,文字就是现场。"""
    print('--- %s ---' % tag, flush=True)
    for x in texts():
        print('   ', x, flush=True)


def dump_fields(tag='输入框'):
    print('--- %s ---' % tag, flush=True)
    for n in flow.dump_xml().iter('node'):
        if 'EditText' in (n.get('class') or ''):
            print('    %r  %s' % ((n.get('text') or '').strip(), n.get('bounds')), flush=True)


def wait_for(needles, timeout, exact=False):
    end = time.time() + timeout
    while time.time() < end:
        t = texts()
        for n in needles:
            if (n in t) if exact else any(n in x for x in t):
                return True
        time.sleep(1.0)
    return False


def top_of(node):
    return int(re.findall(r'\d+', node.get('bounds'))[1])


def tap_xy(x, y):
    flow.sh('input', 'tap', str(x), str(y), timeout=20)
    time.sleep(0.8)


def scroll(times):
    """正 = 内容上移(往下翻),负 = 回到上面。"""
    flow.do_scroll(times)


# 收 IME 的能力放在 flow 里(矩阵也用同一份,别再复制一遍)
ime_shown = flow.ime_shown
hide_ime = flow.hide_ime


def tap_text(want, timeout=8.0):
    """收 IME 之后再点 —— 否则落在下半屏的按钮会被 IME 窗口吃掉。"""
    hide_ime()
    return flow.do_tap(want, lowest=True, timeout=timeout)


def tap_raw(want):
    """点某个文本节点自己的中心(不走「往上找可点祖先」)。

    Compose 合并语义节点时,祖先 bounds 可能横跨一整条(比如页脚的「取消 | 保存」),
    取祖先中心就点在两个按钮中间 —— 什么都不会发生。
    """
    hide_ime()
    t = flow.dump_xml()
    hit = [n for n in t.iter('node') if (n.get('text') or '').strip() == want]
    if not hit:
        return False
    node = max(hit, key=top_of)
    x, y = flow.center(node.get('bounds'))
    print('tap_raw', want, x, y, flush=True)
    tap_xy(x, y)
    return True


def tap_left(want, frac=0.28, timeout=8.0):
    """点某个文本节点的**左侧**。

    设置页的行是通栏,行中心 x=540 正好压在底部导航栏的 + 上(会打开新会话向导);
    而 Compose 合并语义后祖先中心也可能落在按钮之间。取「左侧 28% 处」最稳。
    """
    hide_ime()
    end = time.time() + timeout
    while time.time() < end:
        t = flow.dump_xml()
        hit = [n for n in t.iter('node') if (n.get('text') or '').strip() == want]
        if hit:
            node = max(hit, key=top_of)
            target = flow.clickable_self_or_ancestor(t, node)
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', target.get('bounds')))
            x = int(x1 + (x2 - x1) * frac)
            y = (y1 + y2) // 2
            print('tap_left', want, x, y, '(self)' if target is node else '(ancestor)', flush=True)
            tap_xy(x, y)
            return True
        time.sleep(0.6)
    print('MISS(left)', want, flush=True)
    return False


def tap_row_option(label, option):
    """点某一行里的选项 chip。

    设置页有一堆「开启/关闭」(工具调用、上下文记忆、深度思考…),只按文本点会点错行;
    按「同标签纵向最近」挑,才能定位到那一行自己的 chip。
    """
    hide_ime()
    t = flow.dump_xml()
    labs = [n for n in t.iter('node') if (n.get('text') or '').strip() == label]
    opts = [n for n in t.iter('node') if (n.get('text') or '').strip() == option]
    if not labs or not opts:
        print('!! 找不到「%s」行的「%s」选项' % (label, option), flush=True)
        return False
    ly = top_of(labs[0])
    node = min(opts, key=lambda n: abs(top_of(n) - ly))
    x, y = flow.center(node.get('bounds'))
    print('tap_row_option %s → %s @ (%d,%d)' % (label, option, x, y), flush=True)
    tap_xy(x, y)
    return True



def field_for_label(label):
    """找某个标签下方的输入框。

    不能记坐标:键盘弹出会把表单顶起来。按「标签下面最近的那个 EditText」定位,
    布局位移后依然对得上。
    """
    for _ in range(3):
        t = flow.dump_xml()
        labs = [n for n in t.iter('node') if (n.get('text') or '').strip() == label]
        edits = [n for n in t.iter('node') if 'EditText' in (n.get('class') or '')]
        if labs and edits:
            ly = top_of(labs[0])
            below = [e for e in edits if top_of(e) >= ly - 20]
            node = min(below, key=top_of) if below else min(edits, key=lambda n: abs(top_of(n) - ly))
            return flow.center(node.get('bounds'))
        time.sleep(1.0)
    return None


def clear_field(n=48):
    """清空当前聚焦的输入框。

    重试幂等地清一次:上一次的残留拼在新内容后面(实测 Base URL 拼成两遍,`https：／／…https//…`),
    验证永远不可能通过,现场还看不出是哪一步错。只按退格 —— 全选删除在 Compose 上没有可靠入口。
    """
    flow.sh('input', 'keyevent', 'KEYCODE_MOVE_END', timeout=20)
    flow.sh('input', 'keyevent', *(['KEYCODE_DEL'] * n), timeout=60)
    # 必须等退格处理完:48 个按键是异步消化的,不等就直接输入,尾部退格会把刚打的字删掉
    # (实测 Base URL 只剩 `https://10.0`,看着像「吞尾巴」,其实是自己删的)
    time.sleep(1.0)


def focused_edit():
    """当前真正聚焦的 EditText(输入会落到它身上);没有则 None。"""
    for n in flow.dump_xml().iter('node'):
        if 'EditText' in (n.get('class') or '') and n.get('focused') == 'true':
            return n
    return None


def tap_field(xy, label):
    """点进输入框并**确认真的聚焦了**。

    只按坐标点是不够的:表单会因键盘/输入法窗口开关而整体位移,拿旧坐标点会点空,
    后面的输入就落在空气上(现象是「输入没落进去」,真正的原因是根本没聚焦)。
    """
    for _ in range(3):
        tap_xy(*xy)
        time.sleep(0.8)
        if focused_edit() is not None:
            return True
        xy = field_for_label(label) or xy
    return False


def type_into(xy, text, label):
    """输入并**验证真的落进输入框** —— 只 print('typed') 不算数。"""
    # 每换一个输入框前再确认一次软键盘是摘掉的:必须赶在聚焦之前(聚焦后再动输入法,
    # 注入的按键只落进去一截)。矩阵中途重启 App 等操作可能让系统又把软键盘顶回来。
    ime.ensure_ime_free()
    if not tap_field(xy, label):
        print('!! 「%s」输入框点不进去(没有聚焦的 EditText)' % label, flush=True)
        return False
    for attempt in (1, 2):
        clear_field()
        ime.type_text(text)
        time.sleep(0.9)
        hide_ime()
        if any(text in (n.get('text') or '') for n in flow.dump_xml().iter('node')
               if 'EditText' in (n.get('class') or '')):
            return True
        print('!! 第 %d 次输入没落进「%s」,重试' % (attempt, label), flush=True)
        xy = field_for_label(label) or xy
        if not tap_field(xy, label):
            return False
    return False


def _db():
    c = dbdump.pull()
    c.row_factory = sqlite3.Row
    return c


def db_rows(sql, args=()):
    return _db().execute(sql, args).fetchall()


def setting_get(key):
    """settings 是 k/v 表,列名以库为准(避免写死 key/value 猜错)。"""
    c = _db()
    cols = [r['name'] for r in c.execute("select name from pragma_table_info('settings')")]
    kcol = next((x for x in ('key', 'k', 'name', 'id') if x in cols), None)
    vcol = next((x for x in ('value', 'v', 'val') if x in cols), None)
    if not kcol or not vcol:
        return None
    r = c.execute('select {} from settings where {} = ?'.format(vcol, kcol), (key,)).fetchone()
    return r[0] if r else None


def ensure_app_foreground():
    """连按 BACK 会把设置页也弹掉(退到桌面)—— 兜一下,别让整条链断在这。"""
    t = ' '.join(texts())
    if '会话' in t or '设置' in t:
        return True
    print('!! 退到了非 App 界面,重新拉起', flush=True)
    flow.sh('cmd', 'statusbar', 'collapse', timeout=20)
    subprocess.run([flow.ADB, 'shell', 'monkey', '-p', PKG, '-c',
                    'android.intent.category.LAUNCHER', '1'], capture_output=True, timeout=60)
    return wait_for(('会话',), 30)


def open_settings():
    """回到设置页。面板还开着就先关掉,但要按「面板是否还在」判断,别数 BACK 次数。"""
    for _ in range(3):
        if has('管理你自己的 API 端点'):        # 自定义模型面板的副标题 = 面板还开着
            flow.sh('input', 'keyevent', 'KEYCODE_BACK', timeout=20)
            time.sleep(1.3)
            continue
        if has('外观') or has('工具权限') or has('自定义模型') or has('上下文记忆'):
            return True
        break
    ensure_app_foreground()
    if not tap_left('设置', timeout=10.0):
        return False
    return wait_for(('外观', '工具权限', '自定义模型'), 15)


def set_reasoning_on():
    """打开「深度思考」。

    矩阵里 thinking / thinkreject 两个场景断言的就是「思考参数带不带」,前提是这项得开着:
    全新安装默认「自动」= 只给认得的模型族加字段,而矩阵用的 mock 模型名是自造的。
    """
    if setting_get('reasoningMode') == 'on':
        return True
    if not open_settings():
        return False
    for _ in range(8):
        if has('深度思考'):
            break
        scroll(1)
        time.sleep(0.5)
    if not tap_row_option('深度思考', '开启'):
        return False
    time.sleep(1.5)
    got = setting_get('reasoningMode')
    print('reasoningMode =', got, flush=True)
    return got == 'on'



def ensure_model_selected(name, model_id):
    """全新安装没有「当前模型」,聊天与向导都发不出去 —— 必须显式选上。"""
    if setting_get('modelId') == model_id:
        print('当前模型已是', name, flush=True)
        return True
    if not open_settings():
        print('!! 回不到设置页', flush=True)
        return False
    # 模型行的标题是「当前模型名 or 选择模型」:设备上已经选过别的模型时,
    # 标题就不是「选择模型」了 —— 只认那一句话会让整步 MISS。补上固定的副标题。
    entry = ('选择模型', name, SET_MODEL_SUB)
    for _ in range(8):                       # 模型行在设置页顶部
        if any(has(x) for x in entry):
            break
        scroll(-1)
        time.sleep(0.5)
    if any(tap_left(x) for x in entry if has(x)):
        time.sleep(1.5)
        tap_left(name)
        time.sleep(1.5)
    else:
        print('!! 找不到「选择模型」入口', flush=True)
    return setting_get('modelId') == model_id


def main():
    flow.use_utf8_stdout()
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='https://10.0.2.2:8899/v1', help='端点(release 包禁明文,要用 https)')
    ap.add_argument('--name', default='Mock', help='模型显示名称')
    ap.add_argument('--model', default='mock-thinker', help='拉取列表后点选的模型 ID')
    ap.add_argument('--clean', action='store_true', help='先 pm clear,从全新安装开始(本地演练用)')
    ap.add_argument('--login', action='store_true',
                    help='用「登录 Freebuff 账号」进入(而不是访客模式)')
    a = ap.parse_args()

    if a.clean:
        print('pm clear…', flush=True)
        subprocess.run([flow.ADB, 'shell', 'pm', 'clear', PKG], capture_output=True, timeout=60)
        time.sleep(2)

    # 刚启动的模拟器可能停在黑屏/锁屏上:先叫醒、去锁屏、设常亮(dump 拿不到根节点时
    # 后面每一步都会报「看不到界面」)
    flow.wake_device()

    # 没有 AdbKeyboard 时(CI)先摘掉软键盘:必须在**聚焦任何输入框之前**做 ——
    # 已经聚焦再摘,输入连接会被拆掉,后面的按键只落进去一半(实测 Base URL 只剩 `https://`)。
    ime.ensure_ime_free()

    # 引导全在点中文文案(「先逛逛」「设置」「自定义模型」…):设备是英文界面时
    # 第一步就等不到欢迎页。先把应用级语言钉成简体中文(与设置页开关同一套机制)。
    got = flow.pin_app_locale(PKG)
    if 'zh' not in got:
        print('!! 应用级语言没钉上(%r),引导会因界面不是中文而 MISS' % got, flush=True)

    # 1) 冷启动:全新安装先是欢迎页,访客模式进会话列表
    flow.grant_notifications(PKG)    # 先授权:否则首启会被系统弹窗挡在欢迎页前面
    flow.sh('cmd', 'statusbar', 'collapse', timeout=20)    # 通知栏展开会盖住 App
    flow.sh('am', 'force-stop', PKG, timeout=40)
    time.sleep(1)
    subprocess.run([flow.ADB, 'shell', 'monkey', '-p', PKG, '-c',
                    'android.intent.category.LAUNCHER', '1'], capture_output=True, timeout=60)
    flow.dismiss_notification_dialog()    # 兜底:授权失败时弹窗还在
    if not wait_for(('先逛逛', '会话'), 60):
        die('启动后 60s 内既没看到欢迎页也没看到会话列表')
    if a.login:
        if tap_text('登录 Freebuff 账号'):          # 已登录过的话没有这一步
            print('已用「登录 Freebuff 账号」进入', flush=True)
            time.sleep(3.0)
        got = setting_get('signedIn')
        print('signedIn =', got, flush=True)
        if got != 'true':
            die('走了登录但 signedIn=%r,没落库' % (got,))
    elif tap_text('先逛逛'):                    # 访客模式(已登录过就没有这一步)
        print('已进入访客模式', flush=True)
        time.sleep(3.0)
    if not wait_for(('会话',), 30):
        die('没进到会话列表')
    if not tap_text('设置'):
        die('找不到「设置」入口')
    if not wait_for(('外观',), 15):
        die('设置页没打开')

    # 2) 自定义模型(在「集成」分组,通常在下面)
    # 小屏(CI 的 AVD 没带设备配置时特别明显)要滚更多次才到「集成」分组;
    # 固定 6 次会在小屏上够不着,引导直接失败。上限放宽,每步都检查。
    for i in range(16):
        if has('自定义模型'):
            break
        scroll(1)
        time.sleep(0.6)
    if not tap_left('自定义模型'):
        die('找不到「自定义模型」行')
    if not wait_for(('添加自定义模型',), 15):
        die('自定义模型面板没打开')
    if not tap_text('添加自定义模型'):
        die('点不动「添加自定义模型」')
    if not wait_for(('Base URL',), 15):
        die('表单没打开')

    # 3) 填 显示名称 / Base URL(API Key 留空:mock 不校验;模型 ID 交给「拉取列表」)
    for label, val in (('显示名称', a.name), ('Base URL', a.base)):
        xy = field_for_label(label)
        if not xy:
            die('找不到「%s」输入框' % label)
        print('填 %s = %s' % (label, val), flush=True)
        if not type_into(xy, val, label):
            die('「%s」输入框写了两次都没落进去' % label)

    # 4) 打开「跳过 TLS 证书校验」:release 包默认禁明文,而端点又是自签证书,必须开
    if not has('跳过 TLS 证书校验'):
        scroll(2)
        time.sleep(0.8)
    if not tap_text('跳过 TLS 证书校验'):
        die('找不到「跳过 TLS 证书校验」开关')

    # 5) 拉取列表 → 点选模型 ID(只 GET /v1/models,不校验 key)
    # 开关开没开在界面上看不出来(只是个色块),只能拿请求结果反推:
    # 报 TLS 校验失败就补点一次开关再试 —— 否则「点空了」会一路错到保存。
    for attempt in range(1, 4):
        if not tap_text('拉取列表'):
            die('点不动「拉取列表」')
        if wait_for(('端点可用模型',), 20):
            break
        if has('TLS'):
            print('第 %d 次拉取报 TLS 校验失败,补点开关重试' % attempt, flush=True)
            tap_text('跳过 TLS 证书校验')
            continue
        if attempt == 3:
            die('拉取列表没有回音 —— 端点 %s 可达吗?mock 起了吗?' % a.base)
        time.sleep(2.0)
    else:
        die('拉取列表连试三次都失败(端点 %s)' % a.base)
    picked = False
    for cand in (a.model, 'mock-thinker', 'claude-3-7-sonnet'):
        if has(cand) and tap_text(cand, timeout=6.0):
            picked = True
            break
    if not picked:
        die('拉取回来的列表里没有可点的模型 ID')

    # 6) 保存 + 落库自证(表单里开关点没点上,界面看不出来,只有库说了算)
    dump_fields('保存前各输入框')
    if not tap_text('保存'):
        die('点不动「保存」')
    time.sleep(2.5)
    if has('保存'):                          # 面板还在 = 没保存成功,再用原始坐标补一刀
        print('保存后表单还在,原始坐标重试', flush=True)
        tap_raw('保存')
        time.sleep(2.5)
    rows = db_rows('select id, name, apiId, base, skipTLS from custom_models order by sort')
    if not rows:
        print('!! 保存后库里没有自定义模型', flush=True)
        dump_screen('保存后的界面')
        raise SystemExit(1)
    row = rows[-1]
    print('落库:', dict(row), flush=True)
    if row['name'] != a.name:
        die('名称不对:%s ≠ %s' % (row['name'], a.name))
    if row['base'].rstrip('/') != a.base.rstrip('/'):
        die('端点不对:%s ≠ %s' % (row['base'], a.base))
    if not row['skipTLS']:
        die('「跳过 TLS 证书校验」没打开(release 包会直接把自签端点拦掉)')

    # 7) 选中为当前模型
    if not ensure_model_selected(a.name, row['id']):
        die('模型没能选中为当前模型(modelId=%s)' % setting_get('modelId'))

    # 8) 打开「深度思考」:thinking / thinkreject 两个场景的前提(矩阵默认它是开的)
    if not set_reasoning_on():
        die('「深度思考」没打开(reasoningMode=%s),thinking 场景会误报 FAIL'
            % setting_get('reasoningMode'))
    print('bootstrap OK:模型 %s(%s)已就绪,skipTLS 已开' % (row['name'], row['apiId']), flush=True)
    ime.restore_ime()          # type_text 可能临时摘掉了软键盘,收尾还回去
    return 0


if __name__ == '__main__':
    sys.exit(main())
