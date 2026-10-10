#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CI 入口:对**发布 APK**跑完 29 场景矩阵,并把报告贴到 Step Summary。

由 .github/workflows/release.yml 在「打 Release」之前调用 —— 有 FAIL 就非零退出,
发版直接卡住。整条链是:装机 → 起 TLS mock → 引导 App(访客模式 + 自定义模型)
→ 跑矩阵 → 汇总。本地同一条命令就能演练(不需要 GitHub)。

为什么要在发版前用**真装真跑**的方式验:单元测试过的是策略,这一层过的是「用户装上去
点下去到底什么反应」—— 矩阵里 29 个场景全是端到端断言(含杀进程修复、熔断、HTTP 失败态)。

用法(在 android/ 下,设备/模拟器已就绪):
  python verify/ci_run_matrix.py --apk dist/FreebuffMobile-0.0.3-release.apk
  python verify/ci_run_matrix.py --apk ... --skip-install     # 已装好同一份包,省 1 分钟
"""
import argparse
import hashlib
import os
import socket
import ssl
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adbenv  # noqa: E402

ADB = adbenv.ADB
PKG = 'com.freebuff.mobile'
MOCK_PORT = 8899
REPORT = 'verify/matrix_report.md'
STATE = 'verify/matrix_state.jsonl'
MOCK_LOG = 'verify/mock_matrix.log'
ENV = dict(os.environ, PYTHONIOENCODING='utf-8')


def try_utf8_stdout():
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding='utf-8')          # noqa: E501 (py3.7+:CI 上默认可能不是 utf-8)
        except Exception:
            pass


def sh(*a, timeout=120):
    return subprocess.run([ADB, *a], capture_output=True, timeout=timeout)


def out(*a, timeout=120):
    return sh(*a, timeout=timeout).stdout.decode('utf-8', 'ignore').strip()


def die(msg):
    print('!! ' + msg, flush=True)
    raise SystemExit(1)


def wait_device(timeout=300):
    """等设备上线 + 开机完成(冷启动的模拟器没这么快)。"""
    end = time.time() + timeout
    while time.time() < end:
        if 'device' in out('devices', timeout=30):
            if out('shell', 'getprop', 'sys.boot_completed', timeout=30) == '1':
                return True
        time.sleep(3)
    return False


def adb_root():
    """读库要 root。google_apis 镜像可以,playstore 镜像不行 —— 不行就早点说清楚。"""
    o = out('root', timeout=60)
    print('adb root:', o.replace('\n', ' '), flush=True)
    if 'cannot run as root' in o or 'not allowed' in o:
        die('这个镜像不能 adb root,矩阵读不了设备库(用 google_apis 而不是 playstore 镜像)')
    time.sleep(2)
    subprocess.run([ADB, 'wait-for-device'], capture_output=True, timeout=180)
    end = time.time() + 60
    while time.time() < end:
        if 'uid=0' in out('shell', 'id', timeout=30):
            return True
        time.sleep(2)
    die('adb root 之后 60s 内没拿到 uid=0')


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def installed_apk_path():
    line = out('shell', 'pm', 'path', PKG)
    return line.split('package:')[-1].strip() if line.startswith('package:') else ''


def install(apk):
    """全新安装(先卸载)。发布包签名是 CI 临时 debug 密钥,跨版本覆盖装不上,
    而这里要的就是「用户第一次装」那条路径 —— 引导脚本也才会被真正走到。"""
    out('uninstall', PKG, timeout=120)
    time.sleep(2)
    r = sh('install', '-r', os.path.abspath(apk), timeout=600)
    log = (r.stdout + r.stderr).decode('utf-8', 'ignore')
    print(log.strip()[-400:], flush=True)
    if 'Success' not in log:
        die('安装失败:' + apk)
    ver = out('shell', 'dumpsys', 'package', PKG, timeout=120)
    for key in ('versionName', 'versionCode'):
        for ln in ver.splitlines():
            if key in ln:
                print('   ', ln.strip(), flush=True)
                break


def verify_installed_matches(apk):
    """装机自证:设备上那份 base.apk 的 sha256 必须等于要测的产物。

    不做这一步,「矩阵 PASS」可能只是测了个旧包 —— 发布门禁最怕这种假绿。
    """
    dev = installed_apk_path()
    if not dev:
        die('设备上找不到 %s' % PKG)
    want = sha256_file(apk)
    got = out('shell', 'sha256sum', dev, timeout=300).split()[0]
    print('设备包 sha256:', got, flush=True)
    print('待测包 sha256:', want, flush=True)
    if got != want:
        die('设备上装的不是这次要测的产物(sha256 不一致)')
    return want


def start_mock():
    """起 TLS mock:release 包未声明明文流量,http 会被系统直接拦掉,
    所以用自签证书 + 模型的 skipTLS 跑在 8899。"""
    if mock_alive():
        print('8899 已在监听,复用现有 mock', flush=True)
        return None
    p = subprocess.Popen([sys.executable, 'verify/mock_matrix_tls.py'],
                         stdout=open('verify/mock_tls.out', 'w', encoding='utf-8'),
                         stderr=subprocess.STDOUT, env=ENV)
    end = time.time() + 30
    while time.time() < end:
        if mock_alive():
            print('mock(TLS)已就绪 :%d' % MOCK_PORT, flush=True)
            return p
        time.sleep(0.5)
    p.kill()
    die('mock 起不来,看 verify/mock_tls.out')


def mock_alive():
    """真做一次 TLS 握手 —— 只探端口会把「明文 mock 占着 8899」当成就绪。"""
    try:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        with socket.create_connection(('127.0.0.1', MOCK_PORT), timeout=3) as s:
            with ctx.wrap_socket(s, server_hostname='10.0.2.2'):
                return True
    except Exception:
        return False


def fresh_artifacts():
    """清掉上一轮的日志/状态:矩阵的部分断言是「日志里有这句」,
    留着旧行会让一个没真跑到的场景假 PASS —— 门禁必须是一次性的。"""
    for f in (MOCK_LOG, STATE, REPORT):
        if os.path.exists(f):
            os.remove(f)
    for f in os.listdir('verify'):
        if f.startswith('fail_') and (f.endswith('.png') or f.endswith('.log')):
            os.remove(os.path.join('verify', f))


def run_step(name, cmd, timeout):
    print('\n==== %s ====' % name, flush=True)
    t0 = time.time()
    r = subprocess.run(cmd, env=ENV, timeout=timeout)
    print('---- %s 结束,%.1fs,退出码 %d ----' % (name, time.time() - t0, r.returncode), flush=True)
    return r.returncode


def summarize(code, apk_sha):
    if not os.path.exists(REPORT):
        print('!! 没有产出报告,矩阵可能没跑到最后(看上面的输出)', flush=True)
        return code or 1
    body = open(REPORT, encoding='utf-8').read()
    lines = [l for l in body.splitlines() if l.startswith('| ') and '---' not in l]
    rows = [l for l in lines if not l.startswith('| 场景')]
    passed = sum(1 for l in rows if '| PASS |' in l)
    fails = [l.split('|')[1].strip() for l in rows if '| FAIL |' in l]
    mock_pass = 0
    if os.path.exists(MOCK_LOG):
        mock_pass = sum(1 for l in open(MOCK_LOG, encoding='utf-8') if 'PASS ' in l)

    head = ['### 端到端矩阵:{} / {}'.format(passed, len(rows)), '',
            '- APK sha256:`{}`'.format(apk_sha[:16] + '…'),
            '- 服务端断言:{} 条'.format(mock_pass)]
    if fails:
        head += ['- **FAIL 场景**:' + ', '.join(fails)]
    print('\n'.join(head), flush=True)

    summary_path = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary_path:
        with open(summary_path, 'a', encoding='utf-8') as f:
            f.write('\n'.join(head) + '\n\n' + body + '\n')
        print('报告已写入 Step Summary', flush=True)
    return 0 if not fails else 1


def main():
    try_utf8_stdout()
    ap = argparse.ArgumentParser()
    ap.add_argument('--apk', required=True, help='要对它跑矩阵的发布 APK')
    ap.add_argument('--base', default='https://10.0.2.2:8899/v1')
    ap.add_argument('--model', default='Mock', help='自定义模型显示名')
    ap.add_argument('--skip-install', action='store_true', help='复用设备上已装好的包(本地迭代用)')
    ap.add_argument('--skip-bootstrap', action='store_true', help='复用设备上已配好的模型(本地迭代用)')
    ap.add_argument('--login', action='store_true',
                    help='引导时走「登录 Freebuff 账号」而不是访客模式')
    a = ap.parse_args()

    if not os.path.exists(a.apk):
        die('找不到 APK:' + a.apk)
    if not wait_device():
        die('设备/模拟器没就绪(先起 emulator)')
    adb_root()

    apk_sha = sha256_file(a.apk)
    if not a.skip_install:
        install(a.apk)
    verify_installed_matches(a.apk)

    # 应用级语言钉成中文:矩阵断言的是中文文案,而界面文案已迁进资源(默认英文)。
    # 英文设备上不钉这一步,所有点击会静默 MISS、29 个场景一起假 FAIL。
    import flow  # noqa: E402  (与引导/矩阵共用同一份 adb 解析)
    # 首启会请求通知权限,系统弹窗会盖住欢迎页(引导因此失败)
    flow.grant_notifications(PKG)
    got = flow.pin_app_locale(PKG)
    if 'zh' not in got:
        die('应用级语言没钉上(现在 %r)—— 矩阵带的是中文文案,先手动跑:\n'
            '   adb shell cmd locale set-app-locales %s --locales zh-Hans' % (got, PKG))

    start_mock()
    fresh_artifacts()

    if not a.skip_bootstrap:
        rc = run_step('引导(访客模式 + 自定义模型)', [
            sys.executable, 'verify/ci_bootstrap.py',
            '--base', a.base, '--name', a.model]
            + (['--login'] if a.login else []), timeout=900)
        if rc != 0:
            die('引导失败 —— 设备没被带到可跑状态,后面跑了也没意义')

    # 矩阵自己带断点续跑,但门禁要的是「这一份包跑满全量」,所以强制 --fresh
    code = run_step('29 场景矩阵', [sys.executable, 'verify/matrix.py', '--fresh'], timeout=3600)
    return summarize(code, apk_sha)


if __name__ == '__main__':
    sys.exit(main())
