package com.freebuff.core.model

import org.json.JSONObject

/**
 * 任务清单:`write_todos` 的结构化产物(纯逻辑,便于单测)。
 *
 * 为什么要单独一个对象而不是把 JSON 塞进卡片文案:
 * - **给用户看**:卡片要逐项渲染状态(待办 / 进行中 / 已完成),而不是一坨参数摘要;
 * - **给模型看**:每次更新回一段带编号与状态的清单,模型下一轮据此接着做,不必回忆;
 * - **持久化**:清单随消息里的工具卡片一起落库,重开会话仍在(不新开表、不加迁移)。
 *
 * 归一有意宽容:弱模型常把状态写成 `done` / `进行中` / `已完成`,把字段写成 `task` / `title`。
 * 状态只影响展示,认不出来就按待办处理并**不报错** —— 为它浪费一轮重试不值得。
 */
object TodoList {

    /** 工具名(注册表、宿主分派与卡片渲染共用一处)。 */
    const val TOOL = "write_todos"

    /** 清单上限:再多就不是「任务清单」而是笔记了,也会把卡片撑得比回复还长。 */
    const val MAX_ITEMS = 20

    /** 单条上限:一条任务一句话说清。 */
    const val MAX_CONTENT = 120

    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"

    /** 一条任务:内容 + 状态([PENDING] / [IN_PROGRESS] / [COMPLETED])。 */
    data class Item(val content: String, val status: String = PENDING) {
        val isDone: Boolean get() = status == COMPLETED
        val isDoing: Boolean get() = status == IN_PROGRESS
    }

    /**
     * 解析工具参数。返回 null 表示这份清单不可用(todos 缺失/为空、或每一项都没有内容)。
     *
     * 空 content 的条目直接跳过而不是让整次调用失败:模型偶尔会多写一个空占位,
     * 为此让整份清单丢掉、再花一轮重发,反而更贵。
     */
    fun parse(argsJson: String): List<Item>? {
        val obj = JsonArgs.parse(argsJson).objectOrNull ?: return null
        val arr = obj.optJSONArray("todos")
            ?: obj.optJSONArray("items")
            ?: obj.optJSONArray("tasks")
            ?: return null
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_ITEMS) break
            val o = arr.optJSONObject(i) ?: continue
            val content = firstText(o, CONTENT_KEYS) ?: continue
            val status = firstText(o, STATUS_KEYS)
            out += Item(content.take(MAX_CONTENT), normalizeStatus(status.orEmpty()))
        }
        return out.ifEmpty { null }
    }

    /** 状态归一:英文同义词、连字符/空格写法与中文说法都收;认不出来按待办。 */
    fun normalizeStatus(raw: String): String = when (raw.trim().lowercase().replace('-', '_').replace(' ', '_')) {
        "completed", "complete", "done", "finished", "已完成", "完成", "做完了" -> COMPLETED
        "in_progress", "doing", "active", "current", "进行中", "正在做", "在做" -> IN_PROGRESS
        else -> PENDING
    }

    /** 卡片折叠时的进度:`已完成/总数`(数字与语言无关,不进资源)。 */
    fun progress(items: List<Item>): String = items.count { it.isDone }.toString() + "/" + items.size

    /** 卡片摘要行(存进卡片的 input,展开时也能看懂)。 */
    fun brief(items: List<Item>): String {
        val done = items.count { it.isDone }
        val doing = items.count { it.isDoing }
        return "共 " + items.size + " 项 · 已完成 " + done + " · 进行中 " + doing
    }

    /**
     * 回给模型的执行结果:完整清单 + 下一步要求。
     *
     * 为什么要回整份清单:模型在长任务里会「忘了自己排到哪一步」。把清单连同状态回传,
     * 下一轮它据此继续,而不是重新规划;末尾两句把「整份覆盖」与「做完要收工」说清楚,
     * 避免它只传变化项(会丢条目)或做完不收尾。
     */
    fun summary(items: List<Item>): String {
        val sb = StringBuilder()
        sb.append("任务清单已更新(")
            .append(progress(items)).append(" 已完成")
        if (items.count { it.isDoing } > 0) sb.append(",").append(items.count { it.isDoing }).append(" 项进行中")
        sb.append("):")
        items.forEachIndexed { i, it ->
            sb.append("\n").append(i + 1).append(". [").append(statusLabel(it.status)).append("] ").append(it.content)
        }
        val left = items.count { !it.isDone }
        if (left > 0) {
            sb.append("\n还有 ").append(left).append(" 项未完成:继续推进,每完成一项就再调一次 write_todos 更新状态。")
        } else {
            sb.append("\n全部完成:可以给用户收尾汇报,然后调用 task_completed。")
        }
        sb.append("\n每次调用都要传完整清单(整份覆盖,不要只传变化项)。")
        return sb.toString()
    }

    /** 模型可见的状态名(与界面文案分开:界面走资源,这里进上下文)。 */
    fun statusLabel(status: String): String = when (status) {
        COMPLETED -> "已完成"
        IN_PROGRESS -> "进行中"
        else -> "待办"
    }

    private val CONTENT_KEYS = listOf("content", "text", "task", "title", "内容", "任务")
    private val STATUS_KEYS = listOf("status", "state", "状态")

    /** 取第一个非空字符串字段;JSON null 在 org.json 里会变成字面量 "null",要排掉。 */
    private fun firstText(o: JSONObject, keys: List<String>): String? = keys.firstNotNullOfOrNull { k ->
        o.optString(k).trim().takeIf { it.isNotBlank() && it != "null" }
    }
}
