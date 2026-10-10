#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""场景矩阵跑批:29 个端到端场景,逐条向设备发「case xxx」并断言落库/界面/日志。

用法:
  python verify/matrix.py               # 全量
  python verify/matrix.py killtool      # 只跑指定场景

前置:mock(verify/mock_matrix.py, :8899)在跑、设备模型 base 已指向 mock,
     且「深度思考」= 开启(thinking / thinkreject 断言的正是思考参数带不带;全新安装
     默认是「自动」= 只给认得的模型族加字段,而矩阵的模型名是自造的 —— verify/ci_bootstrap.py 会打开它)。
kill 三场景(killstart/killmid/killtool)走独立驱动 run_kill:流式生成中 `am force-stop`
制造中断 → 冷启动断言自动修复(repairAbandonedMessages)→ 幂等 → 界面无陈旧步骤。
"""
import json as _json
import os
import re
import socket
import ssl
import subprocess
import sys
import time

sys.path.insert(0, os.path.abspath('verify'))
import dbdump  # noqa: E402
import flow  # noqa: E402
import ime  # noqa: E402

ADB = flow.ADB
REPORT = 'verify/matrix_report.md'
STATE = 'verify/matrix_state.jsonl'

CASES = (
    ('emptyargs', '无参工具(arguments 为空串)→ 仍能执行'),
    ('parallel', '一次两个调用、都不带 index(Gemini 形态)→ 按 id 分槽'),
    ('multiline', '一个事件的 JSON 拆多条 data: 行 → 按帧拼接'),
    ('badargs', '参数带栅栏/单引号/尾逗号 → 修复后执行'),
    ('errframe', '流中夹 {"error": …}(HTTP 200)→ 显示 ⚠ 不静默'),
    ('legacy', '遗留 function_call、无 finish_reason → 靠 flush 收齐'),
    ('thinking', '思考参数规则:首轮带、回传轮不带(模型无关,含 grok)'),
    ('single', '一次工具 → 正文 + 显式收工(task_completed)'),
    ('progress', '动过工具却只回文本 → 提醒续跑(nudge)'),
    ('unknown', '调用不存在的工具 → 结果回给模型,不弹卡住循环'),
    ('toolerr', '工具自身失败 → 失败结果回给模型'),
    ('repeatcall', '同一调用重放 → 复用结果 + 连续 2 轮停'),
    ('cap', '每轮换参数(不算重复)→ 打到 30 轮上限并注明'),
    ('http500', '首次 500(可重试)→ 第二次正常'),
    ('http401', '401 鉴权失败(不可重试)→ 直接报错'),
    ('trunc', '流中途断开(声明长度 > 实际)→ 不卡死'),
    ('empty', '无正文、无工具调用 → 给「模型没有返回内容」提示'),
    ('memsave', 'save_memory(CONFIRM 级)→ 确认后落库与回传'),
    ('reason', '先 reasoning_content 再工具 → 思考存档'),
    ('longtext', '长正文流 → 流式输出与自动跟随滚动'),
    ('thinkreject', '端点明确拒绝思考参数 → 去字段重发一次并成功'),
    ('long', '长会话压测:24 轮工具调用+收工,端上连续运行 rounds>=25'),
    ('argerr', '参数名写错 → 信封给处方,按建议改对后自愈'),
    ('breaker', '同一工具连败三次 → 端上熔断拒绝执行(CIRCUIT_OPEN),不再只劝模型'),
    ('mergerr', '一次带三个自造参数 → ①②③ 合并处方,照处方改对后一次执行成功'),
    ('notools', '端点拒 tools → 去字段重发并提示'),
    # kill 三场景排最后:它们要 force-stop 制造中断,CI 上偶发「拉起后界面不再回来」,
    # 排尾就不会连累其它场景(实测 App 被顶到桌面后,后面用例全在启动器上静默假 FAIL)。
    ('killstart', '流开但零字节时杀进程 → 重启修成「上次生成被中断」'),
    ('killmid', '半途正文时杀进程 → 重启保留正文并清步骤(全量扫路径)'),
    ('killtool', '确认弹窗等待时杀进程 → 重启把 waiting 卡片标未完成'),

)


def sh(*a):
    try:
        return subprocess.run([ADB, 'shell', *a], capture_output=True,
                              timeout=40).stdout.decode('utf-8', 'ignore')
    except subprocess.TimeoutExpired:
        return ''


def ui_text():
    return [n.get('text') for n in flow.dump_xml().iter('node') if n.get('text')]


def tap(want, timeout=12.0):
    # 底部按钮(发送/发起任务/允许执行)会被 IME 窗口吃掉或错位 —— 先收 IME 再点
    flow.hide_ime()
    return flow.do_tap(want, lowest=True, timeout=timeout)


def streaming():
    """「正在生成」判定:确认弹窗也算生成中;出现发送/发起任务按钮即已结束。

    无障碍树读空(dump 失败)时不能当成「还在生成」—— 模拟器慢的时候偶发,
    上一次 http401 实测就是这么超时的:应用日志里 `op finish` 早打完了,界面也回到了发送态。
    先重读一次,仍读不到才沿用旧口径。
    """
    t = ui_text()
    if not t:
        time.sleep(1.0)
        t = ui_text()
    if any('工具执行确认' in (x or '') for x in t):
        return True
    if '停止' in t:
        return True
    if '发送' in t or '发起任务' in t:
        return False
    return True


def send(text):
    """新会话的空态输入框是「例如:修复登录页」+「发起任务」;续聊才是「描述任务…」+「发送」。

    返回是否真的发出去了:输入框/按钮没点到的静默失败会让后面整片用例假 FAIL
    (CI 上 App 掉回桌面时就是这样,断言一直在读旧状态)。
    """
    if flow.do_tap('例如:修复登录页', lowest=True, timeout=8.0):
        button = '发起任务'
    elif flow.do_tap('描述任务…', lowest=True, timeout=8.0):
        button = '发送'
    else:
        print('!! 找不到输入框,消息没发出去', flush=True)
        return False
    time.sleep(0.6)
    ime.type_text(text)
    time.sleep(0.6)
    return tap(button)


def send_or_recover(prompt):
    """发一条消息;第一次没发出去就先确保 App 在聊天页,再发一次。"""
    if send(prompt):
        return True
    ensure_chat_screen(prompt)
    return send(prompt)


def op_log():
    return logcat_recent()


_SETTLE_BASE = ''


def agent_rounds():
    """本轮(settle 记录的 base 之后)最新的 op finish rounds;没有则 None。

    共享会话模式下同一 sid 有历史 finish 行,必须排除 base 里已有的。
    """
    m = re.findall(r'op finish sid=\S+ rounds=(\d+)', op_log())
    return int(m[-1]) if m else None


def settle(timeout=300.0, dismiss=True):
    """等整个对话操作结束:等新的 `op begin`,再等同 sid 的 `op finish|failed|cancelled`。

    旧版靠两次 1.2s 流静默判定 —— 工具执行/续跑提醒的流间隙会误判,断言拿到中间态
    (progress 实测:mock rnd=1..4 全走完,矩阵却停在 rnd=1 正文)。
    ChatViewModel 的 op 日志是操作级确定性信号(所有工具轮次结束才打 finish)。
    15s 内等不到 begin(形态外入口)时回退旧的静默判定。
    """
    base = op_log()
    global _SETTLE_BASE
    _SETTLE_BASE = base
    end = time.time() + 15
    sid = None
    while time.time() < end and sid is None:
        if dismiss and any('工具执行确认' in (x or '') for x in ui_text()):
            tap('允许执行')
            time.sleep(1.0)
        for ln in op_log().splitlines():
            if 'op begin sid=' in ln and ln not in base:
                sid = ln.split('sid=')[1].split()[0]
                break
        time.sleep(0.5)
    if sid is None:
        end = time.time() + 12
        while time.time() < end and not streaming():
            time.sleep(0.8)
        end = time.time() + timeout
        while time.time() < end:
            if not streaming():
                time.sleep(1.2)
                if not streaming():
                    return True
            if dismiss and any('工具执行确认' in (x or '') for x in ui_text()):
                tap('允许执行')
                time.sleep(1.0)
            time.sleep(1.2)
        print('!! settle 超时:15s 内没等到新的 op begin(静默判定也没收敛)'.format(), flush=True)
        dump_ui_for_debug()
        return False
    end = time.time() + timeout
    while time.time() < end:
        # 必须是本轮新出现的结束行:共享会话里同一 sid 有历史 finish(如上一场景的 rounds=3)
        for ln in op_log().splitlines():
            if ln in base:
                continue
            if ('op finish sid={} '.format(sid) in ln
                    or 'op failed sid={}'.format(sid) in ln
                    or 'op cancelled sid={}'.format(sid) in ln):
                return True
        if dismiss and any('工具执行确认' in (x or '') for x in ui_text()):
            tap('允许执行')
            time.sleep(1.0)
        time.sleep(1.0)
    # 超时现场必须留证:以前只回一个 settle=False,看不出是界面没回到发送态
    # 还是日志没等到结束行(两者修法完全不同)。只在超时打印,不影响正常路径耗时。
    print('!! settle 超时:sid={} 等不到收尾行'.format(sid), flush=True)
    dump_ui_for_debug()
    return False


def dump_ui_for_debug(tag='超时界面'):
    """把当前屏幕文本打进日志(失败现场)。"""
    t = ui_text()
    print('--- {} ({} 个文本节点) ---'.format(tag, len(t)), flush=True)
    for x in t[:40]:
        print('     UI>', x, flush=True)


def agent_row(sid=None):
    """取(当前会话)末条 agent 消息的正文/步骤/卡片/思考。"""
    if sid is None:
        sid = latest_session_id()
    if not sid:
        return '', [], [], ''
    r = db("select text, stepsJson, toolsJson, reasoning from messages "
           "where sessionId=? and role='agent' order by rowid desc limit 1", (sid,))
    if not r:
        return '', [], [], ''
    row = r[0]
    try:
        steps = _json.loads(row['stepsJson'] or '[]')
    except Exception:
        steps = []
    try:
        cards = _json.loads(row['toolsJson'] or '[]')
    except Exception:
        cards = []
    return (row['text'] or '', steps, cards, row['reasoning'] or '')


def mock_log():
    try:
        with open('verify/mock_matrix.log', encoding='utf-8') as f:
            return f.read()
    except OSError:
        return ''


def logcat_recent(n=4000):
    # 定向读 ChatViewModel:全量长跑时全量 logcat 会把早期日志卷出窗口
    try:
        return subprocess.run([ADB, 'logcat', '-d', '-t', str(n), '-s', 'ChatViewModel'],
                              capture_output=True, timeout=60).stdout.decode('utf-8', 'ignore')
    except subprocess.TimeoutExpired:
        return ''


def logcat_dump(n=2500):
    """全量 logcat 尾部,含 crash buffer。**不筛 tag** —— 只留 ChatViewModel 时,
    「App 没起来/进程崩了」这类现场日志是空的,等于白留(CI 踩过)。"""
    out = []
    for args in (['-b', 'crash'], ['-b', 'main', 'system']):
        try:
            p = subprocess.run([ADB, 'logcat', '-d', '-v', 'threadtime', '-t', str(n)] + args,
                               capture_output=True, timeout=60).stdout.decode('utf-8', 'ignore')
            out.append('===== logcat {} =====\n{}'.format(' '.join(args), p))
        except subprocess.TimeoutExpired:
            out.append('===== logcat {}: TIMEOUT ====='.format(' '.join(args)))
    return '\n'.join(out)


def fail_evidence(case):
    """FAIL 时留现场:截图 + 全量 logcat + 前台/进程/界面状态(CI 排查全靠它)。"""
    try:
        png = subprocess.run([ADB, 'exec-out', 'screencap', '-p'],
                             capture_output=True, timeout=60).stdout
        with open('verify/fail_{}.png'.format(case), 'wb') as fh:
            fh.write(png)
        with open('verify/fail_{}.log'.format(case), 'w', encoding='utf-8') as fh:
            fh.write('--- app_foreground={} process_alive={} ---\n'.format(
                app_foreground(), process_alive()))
            fh.write('--- ui texts ---\n')
            fh.write(' | '.join(x for x in ui_text() if x) + '\n')
            fh.write(logcat_dump())
    except Exception as e:            # 现场留存失败不该盖掉真正的失败原因
        print('!! 现场留存失败', e)


def memory_has(text):
    for row in db("select content from memories where block='user'"):
        if text in (row['content'] or ''):
            return True
    for row in db("select content from memory_entries"):
        if text in (row['content'] or ''):
            return True
    return False


KILL_APP = 'com.freebuff.mobile'
INTERRUPTED = '(上次生成被中断'
GENERATING = '正在生成'


def db(sql, args=()):
    c = dbdump.pull()
    c.row_factory = __import__('sqlite3').Row
    return c.execute(sql, args).fetchall()


def latest_session_id():
    r = db("select id from sessions order by rowid desc limit 1")
    return r[0]['id'] if r else None


def last_agent(sid):
    r = db("select text, stepsJson, toolsJson, time from messages "
           "where sessionId=? and role='agent' order by rowid desc limit 1", (sid,))
    if not r:
        return {}
    row = dict(r[0])
    return dict(text=row.get('text') or '', time=row.get('time') or '',
                steps=_json.loads(row.get('stepsJson') or '[]'),
                cards=_json.loads(row.get('toolsJson') or '[]'))


def shape_of(s):
    """可比较的形态摘要(幂等断言直接比它)。"""
    return dict(text=s.get('text', ''), time=s.get('time', ''),
                steps=[x.get('name') if isinstance(x, dict) else x for x in s.get('steps', [])],
                cards=[(c.get('tool'), c.get('state'), (c.get('output') or '')[:24]) for c in s.get('cards', [])])


def is_clean(s):
    """修复后:无 running/waiting 卡片、无「正在生成」、无过程性步骤。"""
    return (not any(st in ('running', 'waiting') for _t, st, _o in s['cards'])
            and GENERATING not in s['time']
            and not any(('连接' in (n or '')) or (n or '').startswith('第 ') for n in s['steps']))


def reset_mock(port=8899):
    """让 mock 清零「按 case 的轮次计数」(见 mock_matrix.py 的 /__reset)。

    mock 把某个 case 服务到第几轮记在进程内存里;复用一个已跑过几轮的 mock 时,
    新会话的第一个请求就会拿到 rnd>1,剧本走兜底 —— 整片用例假 FAIL(实测踩过)。
    每次 --fresh 开跑前清零,保证从 rnd=1 开始。
    """
    crlf = bytes([13, 10])
    req = b'GET /__reset HTTP/1.1' + crlf + b'Host: 10.0.2.2' + crlf + b'Connection: close' + crlf + crlf
    try:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        with socket.create_connection(('127.0.0.1', port), timeout=3) as sk:
            with ctx.wrap_socket(sk, server_hostname='10.0.2.2') as w:
                w.sendall(req)
                w.recv(200)
        print('mock 轮次计数已重置', flush=True)
    except Exception as e:
        print('!! mock reset 未生效(本地 mock 没起?): %r' % (e,), flush=True)


def app_foreground():
    """App 的 MainActivity 是否真的处于前台(ResumedActivity)。

    重启不能只靠 sleep:模拟器一忙,App 会被拉起来又落回后台 —— 进程还在,
    但既不再 onCreate(冷启动修复不跑、没有 repaired 日志),dump 到的也全是
    启动器/上一屏。此时后面每个用例都会「静默跑在旧状态上」,一路假 FAIL。
    """
    o = subprocess.run([ADB, 'shell', 'dumpsys', 'activity', 'activities'],
                       capture_output=True, timeout=40).stdout.decode('utf-8', 'ignore')
    return any(('ResumedActivity' in ln) and (KILL_APP in ln) for ln in o.splitlines())


def process_alive():
    """App 进程还在不在。只看 ResumedActivity 会被骗:CI 实测「前台=True」但进程
    其实已经死了、界面永远画不出来 —— 两个一起看才可靠。"""
    try:
        out = subprocess.run([ADB, 'shell', 'pidof', KILL_APP],
                             capture_output=True, timeout=20).stdout.decode('utf-8', 'ignore')
        return out.strip() != ''
    except subprocess.TimeoutExpired:
        return False


def main_ui_shown():
    """主界面(会话列表/聊天页)的文本是否已经画出来。"""
    return any((x or '') == '会话' or ('描述任务…' in (x or '')) or ('例如:' in (x or ''))
               for x in ui_text())


def app_interactive():
    """App 已经画出可交互界面:主界面,或未登录时的欢迎页(点「先逛逛」就能继续)。"""
    return any((x or '') == '会话' or ('描述任务…' in (x or '')) or ('例如:' in (x or ''))
               or ('先逛逛' in (x or '')) for x in ui_text())


def relaunch_app(tries=3):
    """冷启动 App,并**确认真回到前台、界面也画出来了**(修复是否重跑、UI 是否可用全看它)。

    am start 比 monkey 确定(monkey 只注入一个 LAUNCHER 事件,App 半死/被 freezer
    冻住时不一定把 Activity 拉到前台),但 CI 实测:只看 ResumedActivity 会被骗 ——
    App 被拉到前台后几秒又掉回桌面/被杀,之后所有点按全打空、后面用例一路假 FAIL。
    所以成功条件是「进程活着 + 前台 + 主界面文本出现」,不满足就整轮重来。
    """
    main = KILL_APP + '/com.freebuff.app.MainActivity'
    for attempt in range(1, tries + 1):
        sh('am', 'force-stop', KILL_APP)
        time.sleep(1.0)
        sh('am', 'start', '-n', main)
        end = time.time() + 30
        while time.time() < end:
            if app_foreground() and process_alive() and app_interactive():
                time.sleep(3.0)   # 首帧稳定 + 等冷启动修复落库(日志/库断言依赖它)
                return True
            time.sleep(2.0)
        print('!! 第 {}/{} 次拉起没成功(前台={} 进程={})'.format(
            attempt, tries, app_foreground(), process_alive()), flush=True)
        subprocess.run([ADB, 'shell', 'monkey', '-p', KILL_APP,
                        '-c', 'android.intent.category.LAUNCHER', '1'], capture_output=True)
        time.sleep(2.0)
    print('!! 重启后 App 没有回到前台(force-stop/adb 状态?)', flush=True)
    return False


def wait_clean(sid, timeout=45):
    end = time.time() + timeout
    s = shape_of(last_agent(sid))
    while time.time() < end:
        if is_clean(s):
            return s
        time.sleep(1.5)
        s = shape_of(last_agent(sid))
    return s


def clear_sweep_flag():
    """删掉「全量扫已做过」标记 → 下次冷启动走全量扫那条路径(列名以库为准,避免写死)。"""
    cols = [r['name'] for r in db("select name from pragma_table_info('settings')")]
    keycol = next((c for c in ('key', 'k', 'name', 'id') if c in cols), None)
    if keycol is None:
        return False
    subprocess.run([sys.executable, 'verify/dbexec.py',
                    "delete from settings where {}='abandonedRepairSwept'".format(keycol)],
                   capture_output=True)
    time.sleep(1.0)
    return True


def startup_logs():
    """冷启动修复的日志行。**按 tag 过滤**,不筛「最后 4000 行」:长跑时全量
    logcat 会把早期日志卷出窗口,repaired 明明打了却查不到 —— 假 FAIL。"""
    out = subprocess.run([ADB, 'logcat', '-d', '-s', 'FreebuffStartup'],
                         capture_output=True).stdout.decode('utf-8', 'ignore')
    return [l for l in out.splitlines() if 'FreebuffStartup' in l]


def tap_first_conversation():
    """列表页兜底:点开最上面那条会话行。

    行容器一般只有 bounds 没有 text(文字在子节点上),所以按几何挑:宽度超过半屏、
    高度像一行(120~600px)、在顶部栏之下 —— 取最靠上的一条,点它的中心。
    列表预览文案不固定(标题被截断、预览可能是修复后的「(上次生成被中断…)」),
    按文本找不到时全靠它。
    """
    t = flow.dump_xml()
    best = None
    for n in t.iter('node'):
        if n.get('clickable') != 'true' or (n.get('text') or ''):
            continue
        b = [int(x) for x in re.findall(r'\d+', n.get('bounds') or '')]
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = b
        if (x2 - x1) < 540 or not (120 <= y2 - y1 <= 600):
            continue
        if y1 < 600 or y2 > 2100:
            continue
        if best is None or y1 < best[0]:
            best = (y1, (x1 + x2) // 2, (y1 + y2) // 2)
    if best is None:
        return False
    sh('input', 'tap', str(best[1]), str(best[2]))
    print('tap 会话行', best[1], best[2], flush=True)
    return True


def ensure_chat_screen(prompt=None):
    """把 App 带回聊天页(必要时先重启):重启后落在会话列表,要先把目标会话点开。

    以前「找不到就按 BACK」—— CI 实测连按几次 BACK 会把 Activity 顶掉、App 退回
    桌面,后面所有用例都静默打在启动器上。现在:不在前台/进程没了 → 重新拉起;
    界面没画出来 → 等;在列表 → 按提示词/修复文案/会话行几何兜底点开;不按 BACK。
    """
    for _ in range(5):
        if not (app_foreground() and process_alive()):
            relaunch_app()
            continue
        t = ui_text()
        if any('先逛逛' in (x or '') for x in t):
            # 未登录的访客态:冷启动会回到欢迎页(登录态才直接进主界面)。
            # CI 实测:没跑 --login 时访客模式跑完 26 例,但第一例 force-stop 之后
            # 冷启动回欢迎页,kill 三连全卡死 —— 这里兜住,点「先逛逛」继续。
            tap('先逛逛')
            time.sleep(3.0)
            continue
        if not main_ui_shown():
            time.sleep(2.0)          # 界面还没画出来,给它一拍
            continue
        if any(('描述任务…' in (x or '')) or ('例如:' in (x or '')) for x in t):
            return True              # 已经在聊天页
        if prompt and flow.do_tap(prompt, lowest=False, timeout=5.0):
            time.sleep(2.5)
            continue
        if flow.do_tap(INTERRUPTED[:6], lowest=False, timeout=4.0):
            time.sleep(2.5)
            continue
        if flow.do_tap('case ', lowest=False, timeout=4.0):
            time.sleep(2.5)
            continue
        if tap_first_conversation():
            time.sleep(2.5)
            continue
    return any(('描述任务…' in (x or '')) or ('例如:' in (x or '')) for x in ui_text())


def wait_stale_card(sid, timeout=30):
    """等卡片以 running/waiting 落库(一轮已收尾,卡在工具执行或确认弹窗上)。"""
    end = time.time() + timeout
    while time.time() < end:
        s = shape_of(last_agent(sid))
        if any(st in ('running', 'waiting') for _t, st, _o in s['cards']):
            return s
        time.sleep(1.0)
    return shape_of(last_agent(sid))


def run_kill(case, first=False):
    """制造中断 → 冷启动修复 → 按库断言。三种形态:

      killstart  流开、零字节就杀        → 空正文 + 过程性步骤(「只扫末尾一条」路径)
      killmid    半途正文时杀            → 正文保留、步骤清干净(「首次全量扫」路径,先删标记)
      killtool   确认弹窗等待时杀         → waiting 卡片标未完成,正文保留
    """
    prompt = 'case ' + case
    if not send_or_recover(prompt):      # 空态/续聊两种输入框 send() 自己挑
        return False, '发送失败:App 没回到聊天页/输入框不可用'

    sid = None
    for _ in range(12):
        time.sleep(0.8)
        sid = latest_session_id()
        if sid:
            break
    if not sid:
        relaunch_app()
        ensure_chat_screen(prompt)
        return False, '建会话失败'

    # ---- 制造中断 ----
    if case == 'killtool':
        pre = wait_stale_card(sid)
        opened = any(st in ('running', 'waiting') for _t, st, _o in pre['cards'])
    else:
        opened = False
        end = time.time() + 25
        while time.time() < end:
            if streaming():
                opened = True
                break
            time.sleep(0.6)
        time.sleep(2.5 if case == 'killmid' else 2.0)   # 等部分正文落地
        pre = shape_of(last_agent(sid))

    sh('am', 'force-stop', KILL_APP)                    # ← 中断就发生在这里
    time.sleep(1.5)
    killed = shape_of(last_agent(sid))
    ev = ['opened={}'.format(opened),
          '杀前={}'.format(_json.dumps(pre, ensure_ascii=False))[:110],
          '半成品={}'.format(_json.dumps(killed, ensure_ascii=False))[:130]]

    # 断言 1:确实是半成品,且没被谁顺手收尾
    if case == 'killstart':
        ok_half = killed['text'].strip() == '' and not is_clean(killed)
    elif case == 'killmid':
        ok_half = '我先查一下时间' in killed['text'] and not is_clean(killed)
    else:
        ok_half = (any(st in ('running', 'waiting') for _t, st, _o in killed['cards'])
                   and killed['text'].strip() != '' and not is_clean(killed))
    if not ok_half:
        relaunch_app()
        ensure_chat_screen(prompt)
        return False, '中断形态不符 ' + ' '.join(ev)

    # ---- 断言 2:冷启动修复 ----
    if case == 'killmid':
        ev.append('clear_sweep={}'.format(clear_sweep_flag()))
    relaunch_app()
    after = wait_clean(sid)
    logs = startup_logs()
    ev.append('修复后={}'.format(_json.dumps(after, ensure_ascii=False))[:140])
    ev.append('日志=' + ('; '.join(l.split('FreebuffStartup: ')[-1] for l in logs[-2:]) or '(无)'))
    ok = is_clean(after) and any('repaired' in l for l in logs)
    if case == 'killstart':
        ok = ok and after['text'].strip().startswith(INTERRUPTED) and not after['cards']
    elif case == 'killmid':
        ok = ok and '我先查一下时间' in after['text'] and not after['cards']
    else:
        ev.append('卡片=' + _json.dumps(after['cards'], ensure_ascii=False)[:80])
        ok = (ok and len(after['cards']) == 1 and after['cards'][0][1] == 'error'
              and after['cards'][0][0] == 'save_memory'
              and '未完成' in after['cards'][0][2] and '我先记一下这个偏好' in after['text'])

    # ---- 断言 3:幂等(再冷启动一次,形态不得变化) ----
    relaunch_app()
    time.sleep(4.0)
    again = shape_of(last_agent(sid))
    ok = ok and again == after
    ev.append('二次重启一致={}'.format(again == after))

    # ---- 断言 4:进会话看界面(用户看到的就是这个) ----
    ok_chat = ensure_chat_screen(prompt)
    ui = ' | '.join(x for x in ui_text() if x)
    stale = [x for x in ('正在生成', '连接模型并开始生成') if x in ui]
    ok = ok and ok_chat and not stale
    ev.append('UI={}'.format('无陈旧步骤' if not stale else '仍有' + '/'.join(stale)))
    if not ok_chat:
        ev.append('回聊天页失败')
    return ok, ' '.join(ev)[:420]


def check(case, ui, text, steps, cards, reasoning):
    ct = _json.dumps(cards, ensure_ascii=False)
    all_ui = ' | '.join(x for x in ui if x)
    if case == 'emptyargs':
        return (('current_time' in ct) and ('未完成' not in ct) and ('"state": "done"' in ct)), ct[:140]
    if case == 'parallel':
        return (('current_time' in ct) and ('calculator' in ct) and ('42' in ct)), ct[:160]
    if case == 'multiline':
        return ('144' in ct), ct[:120]
    if case == 'badargs':
        return ('15' in ct), ct[:120]
    if case == 'errframe':
        return (('上游限流' in text) or ('上游限流' in all_ui)), text[:80]
    if case == 'legacy':
        return ('7' in ct), ct[:120]
    if case == 'thinkreject':
        # 三段都要齐:第一次带着思考参数被端点顶回 → 去掉该字段重发成功。
        # 只查「重发成功」会假 PASS —— 端上压根没带过思考参数时,第一次就直接成功了。
        return (('去掉思考参数后照常回答' in text)
                and ('PASS thinkreject-retry-without-think' in mock_log())
                and ('PASS thinkreject-first-attempt' in mock_log())), text[:80]
    if case == 'breaker':
        ok = ('CIRCUIT_OPEN' in ct and '这次调用没有运行' in ct
              and 'PASS breaker-第3次端上熔断拒绝执行' in mock_log()
              and 'PASS breaker-第2次劝换路' in mock_log()
              and 'PASS breaker-第1次给处方式信封' in mock_log())
        return ok, (ct[-140:] + ' / ' + ('三段断言齐' if ok else '缺断言,查 verify/mock_matrix.log'))
    if case == 'mergerr':
        ok = ('PASS mergerr-合并信封' in mock_log()
              and 'PASS mergerr-照处方改对后一次执行成功' in mock_log()
              and '70' in text)
        return ok, ('合并讲齐={} 一次执行成功={} 正文命中={}'.format(
            'PASS mergerr-合并信封' in mock_log(),
            'PASS mergerr-照处方改对后一次执行成功' in mock_log(),
            '70' in text))
    if case == 'notools':
        return ('端点不支持工具调用' in text), text[:80]
    if case == 'thinking':
        return '两轮都正常' in text, text[:60]
    if case == 'single':
        return (('42' in text) and ('task_completed' in ct)), text[:60] + ' / ' + ct[:60]
    if case == 'progress':
        return (('时间拿到了' in text) and ('我再看看时间' in text) and ('nudge #1' in logcat_recent())), text[:80] + ' nudge#1=' + str('nudge #1' in logcat_recent())
    if case == 'unknown':
        return (('那个工具我这没有' in text) and ('PASS unknown-tool-result' in mock_log())), text[:60]
    if case == 'toolerr':
        return (('计算表达式有问题' in text) and ('PASS tool-error-result' in mock_log())), text[:60]
    if case == 'argerr':
        return (('改对参数后算出来了' in text) and ('PASS argerr-信封有处方' in mock_log())
                and ('PASS argerr-按建议改对后执行成功' in mock_log())), text[:60]
    if case == 'repeatcall':
        return (('"state": "reused"' in ct or '重复调用' in text) and ('连续' in text)), text[-70:] + ' / ' + ct[:90]
    if case == 'cap':
        return ('已达 30 轮工具调用上限' in text), text[-120:]
    if case == 'http500':
        return ('重试之后成功了' in text), text[:60]
    if case == 'http401':
        t = text.strip()
        return (t.startswith('⚠') and ('401' in t or '鉴权' in t or '密钥' in t or 'api key' in t.lower())), t[:110]
    if case == 'trunc':
        t = text.strip()
        return (('⚠' in t) and ('end of stream' in t or '中断' in t or '流' in t)), t[:130]
    if case == 'empty':
        return ('模型没有返回内容' in text), text[:80]
    if case == 'long':
        rnds = agent_rounds()
        return (rnds is not None and rnds >= 25 and '长会话 25 轮全部完成' in text), \
            'rounds={} text={}'.format(rnds, text[:50])
    if case == 'memsave':
        return (('记住了' in text) and memory_has('用户偏好:回答尽量短')), text[:60]
    if case == 'reason':
        return (('时间在上面那张卡里' in text) and len(reasoning) > 10), 'reasoning_len={}'.format(len(reasoning))
    if case == 'longtext':
        return ('第 25 段' in text), 'text_len={}'.format(len(text))
    return False, '未定义断言'


def _load_state():
    """读 JSONL 状态,返回 {case: (ok, desc, detail)} —— 同一 case 取最后一行。"""
    latest = {}
    try:
        with open(STATE, encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    r = _json.loads(line)
                except ValueError:
                    continue
                latest[r['case']] = (bool(r.get('ok')), r.get('desc', ''), r.get('detail', ''))
    except OSError:
        pass
    return latest


def _save_row(case, desc, ok, detail):
    """出结果立刻落盘(追加一行 JSON)—— 进程被杀也不丢已完成场景的进度。"""
    with open(STATE, 'a', encoding='utf-8') as f:
        f.write(_json.dumps({'case': case, 'desc': desc, 'ok': bool(ok), 'detail': detail[:200],
                             'ts': time.strftime('%m-%d %H:%M:%S')}, ensure_ascii=False) + '\n')


def wait_main_ui(timeout=60.0):
    """冷启动等主界面(会话列表/聊天页)出现。

    实测 Displayed 要 ~9.5s,原来写死 sleep(5) 会让「+/下一步」全 miss,
    矩阵随后落在会话列表上白等 120s 静默超时。
    """
    end = time.time() + timeout
    while time.time() < end:
        t = ui_text()
        if any(x == '会话' or ('描述任务…' in (x or '')) or ('例如:' in (x or ''))
               or ('先逛逛' in (x or '')) for x in t):
            return True
        time.sleep(1.0)
    print('!! 主界面等待超时', flush=True)
    return False


def tap_new_task_fab(timeout=10.0):
    """点右下角「+」开新会话。

    FAB 上是**字形**不是文字:无障碍树里它只有 content-desc(「发起新任务」)。
    按文字 `+` 找会先撞上空态提示里那句话(「点右下角「+」发起你的第一个任务」)——
    点上去毫无反应,向导打不开,整轮 29 例全 FAIL("发送失败:App 没回到聊天页")。
    """
    end = time.time() + timeout
    while time.time() < end:
        t = flow.dump_xml()
        for n in t.iter('node'):
            desc = (n.get('content-desc') or '').strip()
            if '发起新任务' in desc or (n.get('text') or '').strip() == '+':
                target = flow.clickable_self_or_ancestor(t, n)
                x, y = flow.center(target.get('bounds'))
                flow.sh('input', 'tap', str(x), str(y), timeout=20)
                print('tap FAB「发起新任务」', x, y, flush=True)
                return True
        time.sleep(0.6)
    print('MISS FAB「发起新任务」', flush=True)
    return False


def new_chat(timeout=25.0):
    """点 + 走完向导(选择仓库 → 选择模型 → 描述任务),直到新会话输入框出现。"""
    for _ in range(3):
        if any('例如:' in (x or '') for x in ui_text()):
            return True
        if any('先逛逛' in (x or '') for x in ui_text()):
            tap('先逛逛')
            time.sleep(2.0)
        if not tap_new_task_fab(timeout=10.0):
            continue
        time.sleep(2.5)
        tap('下一步', timeout=8.0)
        time.sleep(1.5)
        tap('下一步', timeout=8.0)
        end = time.time() + timeout
        while time.time() < end:
            if any('例如:' in (x or '') for x in ui_text()):
                return True
            time.sleep(1.0)
    return False


def main():
    flow.use_utf8_stdout()
    fresh = '--fresh' in sys.argv
    if fresh:
        reset_mock()
    only = {a for a in sys.argv[1:] if not a.startswith('--')}
    app = 'com.freebuff.mobile'
    sh('am', 'force-stop', app)
    time.sleep(1)
    subprocess.run([ADB, 'shell', 'monkey', '-p', app, '-c', 'android.intent.category.LAUNCHER', '1'],
                   capture_output=True, timeout=60)
    wait_main_ui()
    if not new_chat():
        print('!! 新会话没建起来,后面场景大概率全 FAIL', flush=True)

    prev = {} if fresh else _load_state()
    rows = []
    todo = [c for c in CASES if not only or c[0] in only]
    ran = 0   # 本轮实跑计数:决定新会话首条消息的输入框形态与 kill 场景的 first
    for case, desc in todo:
        named = case in only
        # 前置自检:App 若已经掉到后台(force-stop 后没起来 / 被 freezer 冻住),
        # 后面所有用例都会 dump 到启动器、读库里旧状态 —— 一路假 FAIL。先拉回来。
        if not (app_foreground() and process_alive()):
            print('... App 不在前台/进程不存在(%s),先重启并回到会话' % case, flush=True)
            ensure_chat_screen(prompt='case ')      # 内部会先 relaunch
        # 断点续跑:全量跑时,上次已 PASS 的场景直接沿用结果,只补没过的;
        # 点名的场景永远实跑(点名就是要重跑它);--fresh 全部实跑
        if not named and not fresh and case in prev and prev[case][0]:
            _ok, _d, detail = prev[case]
            rows.append((case, desc, True, detail))
            print('{:<11} {:<4} {} (沿用上次结果)'.format(case, 'PASS', detail[:120]))
            sys.stdout.flush()
            continue
        ran += 1
        if case in ('killstart', 'killmid', 'killtool'):
            ok, detail = run_kill(case, first=(ran == 1))
            if not ok:
                fail_evidence(case)
            rows.append((case, desc, ok, detail))
            print('{:<11} {:<4} {}'.format(case, 'PASS' if ok else 'FAIL', detail))
            sys.stdout.flush()
            _save_row(case, desc, ok, detail)
            continue
        prompt = 'case ' + case
        if not send_or_recover(prompt):
            ok, detail = False, '发送失败:App 没回到聊天页/输入框不可用'
            fail_evidence(case)
            rows.append((case, desc, ok, detail))
            print('{:<11} {:<4} {}'.format(case, 'FAIL', detail))
            sys.stdout.flush()
            _save_row(case, desc, ok, detail)
            continue
        ok_settle = settle(timeout=300 if case in ('cap', 'long') else 120)
        time.sleep(1.0)
        text, steps, cards, reasoning = agent_row()
        ok, detail = check(case, ui_text(), text, steps, cards, reasoning)
        ok = ok and ok_settle
        detail = 'settle={} {}'.format(ok_settle, detail)
        if not ok:
            fail_evidence(case)
        rows.append((case, desc, ok, detail))
        print('{:<11} {:<4} {}'.format(case, 'PASS' if ok else 'FAIL', detail))
        sys.stdout.flush()
        _save_row(case, desc, ok, detail)

    # 报告合并:本轮结果 + 上次状态里「未进本轮」的场景行,按 CASES 顺序合出完整报告。
    # (点名补跑时,报告仍覆盖全量场景:本轮点名的用新结果,其余沿用上次。)
    merged = {r[0]: r for r in rows}
    for case, (ok, desc, detail) in prev.items():
        if case not in merged:
            merged[case] = (case, desc, ok, detail)
    order = {c[0]: i for i, c in enumerate(CASES)}
    final = sorted(merged.values(), key=lambda r: order.get(r[0], 999))
    with open(REPORT, 'w', encoding='utf-8') as f:
        f.write('# 场景矩阵结果\n\n| 场景 | 说明 | 结果 | 证据 |\n|---|---|---|---|\n')
        for case, desc, ok, detail in final:
            f.write('| {} | {} | {} | {} |\n'.format(case, desc, 'PASS' if ok else 'FAIL',
                                                     detail.replace('|', '/')[:200]))
    passed = sum(1 for r in final if r[2])
    print('\n报告: {}  通过 {}/{}(本轮实跑 {}, 沿用 {})'.format(
        REPORT, passed, len(final), len(rows), len(final) - len(rows)))
    bad = [r[0] for r in final if not r[2]]
    if bad:
        print('FAIL 场景: ' + ', '.join(bad), flush=True)
    return 0 if not bad else 1


if __name__ == '__main__':
    sys.exit(main())
