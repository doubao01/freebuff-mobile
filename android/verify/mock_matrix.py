#!/usr/bin/env python3
"""场景矩阵 mock(8899):按「最后一条 user 消息里的 `case xxx`」选剧本,服务端自己断言。

分两类:
· 解析/适配类(都收在这个 mock 里,一处即可跑完整矩阵)
    emptyargs parallel multiline badargs errframe legacy notools thinking
· 循环/体验类
    single       一次工具 → 正文 + 显式收工
    progress     已动过工具却只回文本(进度播报)→ 端上应提醒续跑
    unknown      调用不存在的工具 → 结果要回给模型(且不该弹确认卡住循环)
    toolerr      工具自身失败 → 失败结果要回给模型,不能卡死
    repeatcall   同一调用重放 → 端上应「复用结果 + 连续 2 轮停」
    cap          每轮换参数(不算重复)→ 应打到 30 轮上限并注明
    http500      首次 500(可重试)→ 第二次正常
    http401      401 鉴权失败(不可重试)→ 直接报错
    trunc        流中途断开(声明长度 > 实际写入)→ 不能卡死
    empty        无正文、无工具调用 → 端上应给「模型没有返回内容」提示
    memsave      调 save_memory(CONFIRM 级)→ 确认后落库与回传
    reason       先 reasoning_content 再工具 → 端上应存下思考
    longtext     长正文流 → 验证流式与自动跟随滚动
    thinkreject  端点明确拒绝思考参数 → 端上应去掉该字段重发一次并成功
    argerr       自造参数名(calculator 带 mode)→ 端上要给带处方的错误信封,改对后能跑通
    breaker      同一工具连败三次(每轮换参数避开重复复用)→ 第 3 次端上应熔断拒绝执行(CIRCUIT_OPEN)
    mergerr      一次四个参数错 → 端上给 ①②③④ 合并处方式信封,模型照处方**一次改对**

注意:提醒消息是**用户角色**且带「(系统提醒)」前缀,case 识别要跳过它,否则 nudge 之后场景丢失。
断言写在 verify/mock_matrix.log,设备端跑完由 verify/matrix.py 汇总。
"""
import hashlib
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8899
LOG = "verify/mock_matrix.log"
NL = chr(10)   # 避免源码里出现转义换行,便于脚本化维护
NL2 = NL + NL
CASES = (
    "emptyargs", "parallel", "multiline", "badargs", "errframe", "legacy", "notools", "thinking",
    "single", "progress", "unknown", "toolerr", "repeatcall", "cap", "http500", "http401",
    "trunc", "empty", "memsave", "reason", "longtext", "thinkreject", "long", "argerr",
    "killstart", "killmid", "killtool", "breaker", "mergerr",
)
rounds = {}        # case -> 已服务轮次
conv_rounds = {}   # 首个 user 消息哈希 -> 轮次(无 case 的收尾/旁路调用)


def log(line):
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + NL)


def chunk(delta, finish=None):
    return "data: " + json.dumps(
        {"id": "mock", "object": "chat.completion.chunk",
         "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]},
        ensure_ascii=False) + NL2


def thinking(text, n=3):
    step = max(1, len(text) // n)
    return "".join(chunk({"reasoning_content": text[i:i + step]}) for i in range(0, len(text), step))


def tc(call_id, name, args, index=None):
    entry = {"id": call_id, "type": "function", "function": {"name": name, "arguments": args}}
    if index is not None:
        entry["index"] = index
    return chunk({"tool_calls": [entry]})


def done(summary="场景验证完成", call_id="done_1"):
    return tc(call_id, "task_completed", {"summary": summary}, index=0) + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2


def done_text(text, summary="场景验证完成", call_id="done_1"):
    """「给用户看的正文 + 显式收工」——规范模型的收尾形态(只回文本会触发续跑提醒)。"""
    return (chunk({"content": text})
            + tc(call_id, "task_completed", {"summary": summary}, index=0)
            + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)


def stop(text):
    return chunk({"content": text}) + chunk({}, finish="stop") + "data: [DONE]" + NL2


def case_of(msgs):
    """最后一条**真实用户**消息里的 `case xxx` 决定场景(跳过端上注入的续跑提醒)。"""
    for m in reversed(msgs):
        if m.get("role") != "user":
            continue
        raw = m.get("content") or ""
        if "(系统提醒)" in raw:
            continue
        text = raw.strip().lower()
        for name in CASES:
            if ("case " + name) in text or text == name:
                return name
        return None
    return None


def scripted(case, rnd, tool_hist, msgs):
    if case == "emptyargs":
        if rnd == 1:
            return tc("call_empty_1", "current_time", "", index=0) + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2
        return done()
    if case == "parallel":
        if rnd == 1:
            return (thinking("并行两个调用,都不带 index。")
                    + chunk({"tool_calls": [{"id": "call_a", "function": {"name": "current_time"}}]})
                    + chunk({"tool_calls": [{"id": "call_b", "function": {"name": "calculator",
                                                                          "arguments": {"expression": "6*7"}}}]})
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        return done()
    if case == "multiline":
        if rnd == 1:
            line1 = 'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_ml","type":"function",'
            line2 = 'data: "function":{"name":"calculator","arguments":"{\\"expression\\":\\"144\\"}"}}]}}]}'
            return line1 + NL + line2 + NL2 + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2
        return done()
    if case == "badargs":
        if rnd == 1:
            return tc("call_bad", "calculator", "```json" + NL + "{'expression': '7+8',}" + NL + "```", index=0) \
                + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2
        return done()
    if case == "errframe":
        if rnd == 1:
            return (chunk({"content": "先试一下。" + NL})
                    + 'data: {"error":{"message":"上游限流(测试用错误帧)","type":"rate_limit"}}' + NL2
                    + "data: [DONE]" + NL2)
        return done()
    if case == "legacy":
        if rnd == 1:
            # 遗留 function_call(单数),且不带 finish_reason
            return (chunk({"function_call": {"name": "calculator", "arguments": '{"expression":"3+4"}'}})
                    + "data: [DONE]" + NL2)
        # 末尾事件故意不跟空行:必须靠 decoder.flush 收齐
        return done("遗留协议场景完成", "done_legacy")[:-1]
    if case == "notools":
        return stop("端点不支持工具调用,已按纯对话回答。" + NL)
    if case == "thinking":
        # 模型无关:grok 等非 anthropic 族实测同样首轮带 enable_thinking、回传轮不带
        if not tool_hist:
            return (thinking("第一轮:还没有工具调用历史,应当带着思考参数。")
                    + tc("call_t1", "current_time", "{}", index=0) + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        return (thinking("第二轮:要回传工具结果了,严格网关会拒绝 thinking;端上必须不送。")
                + done_text("两轮都正常。" + NL, "思考规则验证完成", "done_t"))

    # ---------------- 循环 / 体验类 ----------------
    if case == "single":
        if rnd == 1:
            return (chunk({"content": "我先算一下。" + NL})
                    + tc("c_s1", "calculator", '{"expression":"21*2"}', index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        return done_text("**42**,算好了。" + NL, "算出 21*2=42")
    if case == "progress":
        if rnd == 1:
            return (chunk({"content": "我先算一下。" + NL})
                    + tc("c_p1", "calculator", '{"expression":"5*5"}', index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        if rnd == 2:
            # 已动过工具却只回文本(进度播报)→ 端上应提醒续跑,而不是当成答完
            return stop("我再看看时间,稍等。" + NL)
        if rnd == 3:
            return (tc("c_p2", "current_time", "{}", index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        return done_text("时间拿到了,任务完成。" + NL, "续跑后收工")
    if case == "unknown":
        if rnd == 1:
            return (tc("c_u1", "browse_web", '{"url":"https://example.com"}', index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        res = [m.get("content") or "" for m in msgs if m.get("role") == "tool"]
        ok = any(("未知" in r or "不存在" in r or "unsupported" in r.lower() or "browse_web" in r) for r in res)
        log("{} unknown-tool-result 已回传={}".format("PASS" if ok else "FAIL", len(res)))
        return done_text("那个工具我这没有,换个方式。" + NL, "未知工具已妥善降级")
    if case == "argerr":
        res = [m.get("content") or "" for m in msgs if m.get("role") == "tool"]
        if rnd == 1:
            # 故意带一个 schema 里没有的参数 mode:端上要先报「参数名不存在」并给处方
            return (tc("c_ar1", "calculator", '{"expression":"1+1","mode":"fast"}', index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        if rnd == 2:
            got = res[0] if res else ""
            ok = ("[工具错误]" in got and "怎么改" in got and "参数清单" in got
                  and "mode" in got and "expression:string" in got)
            log("{} argerr-信封有处方(类型/问题/怎么改/参数清单)".format("PASS" if ok else "FAIL"))
            if not ok:
                log("  argerr 实际收到: " + got.replace(NL, " | ")[:400])
            # 按建议改对:用规范写法重发一次(这里顺便走一遍别名 expr)
            return (tc("c_ar2", "calculator", '{"expr":"6*7"}', index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        if rnd != 3:
            # 轮次结束后端上还会发一次「记忆提取」请求(它也带着本条 case 文本),
            # 那种请求没有工具结果,不能拿它当场景断言 —— 否则每轮都会多一条假 FAIL。
            return done_text("(非本场景的调用,忽略)" + NL, "忽略")
        ok2 = any("= 42" in r for r in res)
        log("{} argerr-按建议改对后执行成功".format("PASS" if ok2 else "FAIL"))
        return done_text("改对参数后算出来了:42。" + NL, "参数错误已自愈")

    if case == "breaker":
        # 连败三次触发熔断:每轮换参数(签名不同,避开重复调用复用),calculator 每次都本地失败
        #   第 1 次失败:处方式信封(鼓励按建议重试) → 第 2 次:追加「劝换路」 → 第 3 次:端上熔断
        # 全量矩阵共享一个会话:只数「case breaker」这条 user 消息之后的工具消息,
        # 否则此前所有场景的工具消息都会被数进来,剧本直接跳到收尾分支
        start = max(i for i, m in enumerate(msgs)
                    if m.get("role") == "user" and "case breaker" in (m.get("content") or ""))
        got = [m.get("content") or "" for m in msgs[start + 1:] if m.get("role") == "tool"]
        n = len(got)
        if n == 1:
            ok = "[工具错误]" in got[0] and "怎么改" in got[0]
            log("{} breaker-第1次给处方式信封".format("PASS" if ok else "FAIL"))
            if not ok:
                log("  breaker 实际收到: " + got[0].replace(NL, " | ")[:300])
        elif n == 2:
            ok = "连续失败" in got[-1] and "换" in got[-1]
            log("{} breaker-第2次劝换路".format("PASS" if ok else "FAIL"))
            if not ok:
                log("  breaker 实际收到: " + got[-1].replace(NL, " | ")[:300])
        elif n == 3 and tool_hist:
            # 用 tool_hist 排除旁路:记忆提取请求也不带工具消息(但没有 assistant 工具调用历史);
            # 首条 user 消息哈希不能当会话键 —— 每个会话的首条都是 case breaker,哈希全同,会误去重
            ok = "CIRCUIT_OPEN" in got[-1] and "这次调用没有运行" in got[-1]
            log("{} breaker-第3次端上熔断拒绝执行".format("PASS" if ok else "FAIL"))
            if not ok:
                log("  breaker 实际收到: " + got[-1].replace(NL, " | ")[:300])
        if n < 3:
            # 失败表达式每次不同(1+1+ / 1+2+ / 1+3+):签名不同避开重复复用,且都是解析错误
            return (tc("c_b{}".format(n + 1), "calculator", '{"expression":"1+%d+"}' % (n + 1), index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        # 拿到熔断信封后模型只能换路:不再调工具,给正文 + 显式收工
        return done_text("计算器这轮用不了,我先直接回答。" + NL, "端上熔断后已换路")

    if case == "mergerr":
        # 合并处方验证(calculator 全程本地执行,确定性):mock 扮演模型 ——
        #   n==0 发一次多参数错调(三个自造参数 mode/sort/lang)→ 端上应回 ①②③ 合并处方式信封;
        #   n==1 断言信封后,按处方返回**改对后的调用**(删掉全部自造参数);
        #   n==2 断言设备真的把改对后的调用执行成功了(结果含 42、不是错误信封)。
        # 全量矩阵共享会话:同 breaker,只数本 case 的 user 消息之后的工具消息
        start = max(i for i, m in enumerate(msgs)
                    if m.get("role") == "user" and "case mergerr" in (m.get("content") or ""))
        got = [m.get("content") or "" for m in msgs[start + 1:] if m.get("role") == "tool"]
        n = len(got)
        if n == 0:
            # 三个自造参数(弱模型真实病例,argerr 同款):全部是 schema 级错误,才能在体检层合并。
            # 注意表达式本身必须合法 —— calculator 的解析失败发生在执行层,而自造参数在体检层就被拦,
            # 两类错不会同时出现在一张信封里(体检先行)。
            bad = '{"mode":"fast","sort":"stars2","lang":"zh","expression":"(12+8)*3.5"}'
            return (tc("c_mg1", "calculator", bad, index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        if n == 1 and tool_hist:
            nums = sum(1 for m in ("①", "②", "③", "④", "⑤") if m in got[0])
            ok = "[工具错误]" in got[0] and nums >= 3
            log("{} mergerr-合并信封({} 条问题逐条编号)".format("PASS" if ok else "FAIL", nums))
            if not ok:
                log("  mergerr 实际收到: " + got[0].replace(NL, " | ")[:400])
            # 照处方改对:删掉全部自造参数,只留合法的 expression —— 模型拿到合并信封后该做的事。
            # 下一轮请求里 tool 消息会变 2 条,断言挪到那时做(n==1 时解析不到自己这次调用)。
            return (tc("c_mg2", "calculator", '{"expression":"(12+8)*3.5"}', index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        if n == 2 and tool_hist:
            # n==1 那次调用的执行结果,此刻作为最后一条 tool 消息回来了:
            # 不是错误信封 + 命中正确答案 = 设备真的把改对后的调用执行成功了
            ok = "[工具错误]" not in got[-1] and "70" in got[-1]
            log("{} mergerr-照处方改对后一次执行成功".format("PASS" if ok else "FAIL"))
            if not ok:
                log("  mergerr 实际收到: " + got[-1].replace(NL, " | ")[:300])
        return done_text("算出来了:(12+8)*3.5 = 70,按你的偏好回答尽量短。" + NL, "合并处方后一次改对")

    if case == "toolerr":
        if rnd == 1:
            return (tc("c_e1", "calculator", '{"expression":"1+"}', index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        res = [m.get("content") or "" for m in msgs if m.get("role") == "tool"]
        ok = len(res) > 0 and any(("失败" in r or "无法" in r or "错误" in r or "error" in r.lower()) for r in res)
        log("{} tool-error-result 已回传={}".format("PASS" if ok else "FAIL", res[:1]))
        return done_text("计算表达式有问题,我改一下。" + NL, "工具失败已回传模型")
    if case == "repeatcall":
        return (tc("c_r{}".format(rnd), "calculator", '{"expression":"9*9"}', index=0)
                + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
    if case == "cap":
        return (tc("c_c{}".format(rnd), "calculator", '{"expression":"%d*2"}' % rnd, index=0)
                + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
    if case == "http500":
        if rnd == 1:
            log("PASS http500-first-attempt → 端上应重试")
            return "\x00HTTP500\x00"
        log("PASS http500-second-attempt rnd={} → 重试后应成功".format(rnd))
        return done_text("重试之后成功了。" + NL, "500 重试成功")
    if case == "http401":
        return "\x00HTTP401\x00"
    if case == "trunc":
        return "\x00TRUNC\x00"
    if case == "empty":
        return chunk({}, finish="stop") + "data: [DONE]" + NL2
    if case == "memsave":
        if rnd == 1:
            return (tc("c_m1", "save_memory", '{"block":"user","content":"用户偏好:回答尽量短"}', index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        res = [m.get("content") or "" for m in msgs if m.get("role") == "tool"]
        ok = any(("保存" in r or "记忆" in r) for r in res)
        log("{} memsave-confirm={}".format("PASS" if ok else "FAIL", res[:1]))
        return done_text("记住了。" + NL, "记忆已保存")
    if case == "reason":
        if rnd == 1:
            return (thinking("先想想:用户要的是时间,调 current_time 最合适。")
                    + tc("c_z1", "current_time", "{}", index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        return done_text("时间在上面那张卡里。" + NL, "思考后收工")
    if case == "longtext":
        body = "".join("第 {} 段:这是一段用于验证长正文流式输出与自动跟随滚动的文字。".format(i) + NL2
                       for i in range(1, 26))
        return stop(body)
    if case == "long":
        # 长会话压测:24 轮工具调用 + 第 25 轮收工,端上自动执行工具连续运行,
        # 一个对话操作跑完 25 轮(旧版每轮纯文本,单次操作续不了轮,只能狂发 5 分钟)。
        # 推进按「本条 case 的 user 消息之后的工具消息数」:重跑换新会话也不串(killtool 教训)。
        anchor = None
        for i, m in enumerate(msgs):
            if m.get("role") == "user" and "case long" in (m.get("content") or "") \
                    and "(系统提醒)" not in (m.get("content") or ""):
                anchor = i
        n_tools = len([m for m in msgs[anchor + 1:] if m.get("role") == "tool"]) if anchor is not None else 0
        if n_tools < 24:
            # calculator 每轮算式不同:current_time 无参数、每轮参数相同,
            # 会被端上「连续 2 轮重复调用」检测正确掐断(端上行为对,剧本错)。
            return (tc("c_L{}".format(n_tools + 1), "calculator", '{"expression":"6*' + str(n_tools + 1) + '"}', index=0)
                    + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
        return done_text("长会话 {} 轮全部完成。".format(n_tools + 1) + NL, "long Done")
    if case == "killtool":
        # save_memory 默认是「需确认」级:卡片会被写成 waiting 并弹出确认框,端上就此停住。
        # 每轮同款(不看 rnd):rounds 是按 case 全局计数的,一键重跑时新会话可能拿到 rnd>1,
        # 若换成收尾剧本就再也造不出 waiting 卡片(重跑必 FAIL 的实测教训)。
        return (chunk({"content": "我先记一下这个偏好……"})
                + tc("kill_3", "save_memory", {"block": "user", "content": "偏好:回答尽量短"})
                + chunk({}, finish="tool_calls") + "data: [DONE]" + NL2)
    if case == "killstart":
        # 只把流打开,不写任何字节:端上会留下「空正文 + 连接模型并开始生成…」的半成品
        return "\x00KILLSTART"
    if case == "killmid":
        # 先一段正文再一个工具调用,然后挂住:卡片停在 running(本轮不结束,工具不会被执行)
        return "\x00KILLMID"
    if case == "thinkreject":
        # 只有「端上已经去掉思考参数」的那次请求才会走到这里
        return stop("去掉思考参数后照常回答。" + NL)
    return stop("(无场景)" + NL)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _json(self, obj, code=200):
        if code != 200:
            log("HTTP {} body={}".format(code, json.dumps(obj, ensure_ascii=False)[:160]))
        raw = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path.startswith("/__reset"):
            # 清空「按 case 的轮次计数」。计数在**进程内存**里:CI 每轮新起进程没事,
            # 但复用同一个 mock(本地 --skip-install 复跑)时新会话首个请求会拿到
            # rnd>1 → 剧本走兜底 → 整片用例假 FAIL。每次跑之前显式清零。
            rounds.clear()
            conv_rounds.clear()
            log("RESET 轮次计数已清零(新一轮开始)")
            self._json({"ok": True})
            return
        if self.path.startswith("/hang"):
            # 收下请求但不响应:让端上的 web_fetch 一直挂着(用「工具执行中被杀」制造 running 卡片)
            log("HANG web_fetch 已到达,故意不响应")
            time.sleep(120)
            return
        if self.path.rstrip("/").endswith("/models"):
            self._json({"data": [{"id": "mock-thinker"}, {"id": "claude-3-7-sonnet"}]})
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n).decode("utf-8", "ignore") or "{}")
        msgs = body.get("messages", [])
        model = (body.get("model") or "").lower()
        case = case_of(msgs)
        has_tools = bool(body.get("tools"))
        think_fields = [k for k in ("thinking", "enable_thinking", "reasoning_effort") if k in body]
        first_user = next((m.get("content") or "" for m in msgs if m.get("role") == "user"), "")
        conv = hashlib.sha1(first_user.encode("utf-8")).hexdigest()[:8]
        tool_hist = any(m.get("role") == "assistant" and m.get("tool_calls") for m in msgs)
        system_msgs = [m for m in msgs if m.get("role") == "system"]
        sys_chars = sum(len(m.get("content") or "") for m in system_msgs)
        has_summary = any("[早期对话摘要]" in (m.get("content") or "") for m in system_msgs)
        summary_call = any("压缩成简洁摘要" in (m.get("content") or "") for m in system_msgs)
        mid_system = [i for i, m in enumerate(msgs) if m.get("role") == "system" and i > 0]

        if case is None:
            conv_rounds[conv] = conv_rounds.get(conv, 0) + 1
            case, rnd = "closing", conv_rounds[conv]
        else:
            rounds[case] = rounds.get(case, 0) + 1
            rnd = rounds[case]

        log("case={} rnd={} model={} tools={} think={} tool_hist={} msgs={} system_chars={} summary_call={} "
            "summary_in_ctx={} mid_system_at={} tools_chars={}".format(
                case, rnd, model, has_tools, think_fields, tool_hist, len(msgs), sys_chars, summary_call,
                has_summary, mid_system,
                len(json.dumps(body.get("tools"), ensure_ascii=False)) if has_tools else 0))

        # 思考参数规则:只对 Anthropic 兼容族(thinking 字段)断言 ——
        # 回传工具结果那一轮必须不带 thinking,否则严格网关 400(LiteLLM modify_params 同款)
        if case == "thinking" and has_tools:
            if tool_hist:
                ok = not think_fields
                log("{} thinking-rule(anthro) tool_hist=True think={}".format("PASS" if ok else "FAIL", think_fields))
                if not ok:
                    self._json({"error": {"message": "thinking 与工具调用同轮出现"}}, 400)
                    return
            else:
                ok = bool(think_fields)
                log("{} thinking-rule(anthro) tool_hist=False think={}".format("PASS" if ok else "FAIL", think_fields))
                if not ok:
                    self._json({"error": {"message": "thinking 缺失"}}, 400)
                    return

        # 端点明确拒绝思考参数:错误文本点名该字段 → 端上应去掉它重发
        if case == "thinkreject" and think_fields:
            log("PASS thinkreject-first-attempt think={} → 端上应去掉重发".format(think_fields))
            self._json({"error": {"message": "'enable_thinking' is not supported by this endpoint",
                                  "type": "invalid_request_error"}}, 400)
            return
        if case == "thinkreject" and not think_fields:
            log("PASS thinkreject-retry-without-think")

        if case == "notools" and has_tools:
            log("{} notools-first-request-rejected".format("PASS" if rnd == 1 else "WARN"))
            self._json({"error": {"message": "'tools' is not supported by this model",
                                  "type": "invalid_request_error"}}, 400)
            return

        if not body.get("stream"):
            self._json({"choices": [{"message": {"role": "assistant", "content": "[]"}}]})
            return

        out = scripted(case, rnd, tool_hist, msgs)

        # 永不结束的流:用 chunked 编码分块写,故意不发终止块(0 长度块)。
        # 端上会一直以为「还在生成」,矩阵脚本随后 force-stop —— 这正是要复现的中断形态。
        if isinstance(out, str) and out.startswith("\x00") and out.strip("\x00") in ("KILLSTART", "KILLMID"):
            kind = out.strip("\x00")
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()

            def w(data):
                b = data.encode()
                self.wfile.write(("%x" % len(b)).encode() + b"\r\n" + b + b"\r\n")
                self.wfile.flush()

            try:
                if kind == "KILLMID":
                    w(chunk({"content": "我先查一下时间……"}))
                    time.sleep(0.8)
                    w(tc("kill_1", "current_time", {}))
                    log("PASS killmid-stream-opened(正文+工具调用已发出,流不收尾)")
                else:
                    log("PASS killstart-stream-opened(流已开,零字节)")
                # 挂住:直到端上被杀(写失败会抛异常),或最多 60s
                for _ in range(60):
                    time.sleep(1.0)
            except Exception:
                log("PASS {}-client-disconnected".format(kind.lower()))
            return

        if isinstance(out, str) and out.startswith("\x00"):
            kind = out.strip("\x00")
            if kind == "HTTP500":
                self._json({"error": {"message": "上游 500(测试)"}}, 500)
            elif kind == "HTTP401":
                self._json({"error": {"message": "invalid api key"}}, 401)
            else:  # TRUNC:声明长度远大于实际写入 → 客户端看到流被截断
                payload = chunk({"content": "开始输出"}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(payload) + 4096))
                self.end_headers()
                self.wfile.write(payload)
                self.wfile.flush()
                self.close_connection = True
            return

        payload = out.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    open(LOG, "w").close()
    log("matrix mock listening on {}".format(PORT))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
