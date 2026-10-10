package com.freebuff.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freebuff.core.ui.theme.LocalTokens
import com.freebuff.core.ui.theme.ThemeTokens

val R14 = RoundedCornerShape(14.dp)
val RFull = RoundedCornerShape(50)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = LocalTokens.current.text3, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
fun RowCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalTokens.current.surface, R14)
            .border(1.dp, LocalTokens.current.border, R14)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        content = content,
    )
}

/**
 * 设置行。trailing 默认高亮;dim=true 时置灰(用于「未连接/未添加」这类空状态),
 * accent=true 强制高亮(如"已连接")。danger=true 用于破坏性操作(整行红色)。
 *
 * 为什么 dim 是参数而不在组件内比字符串:老的实现拿 trailing 和"未连接"/"未添加"对比,
 * 文案一旦多语言化就再也不相等 —— 置灰会静默失效。空状态是调用方知道的事,就由调用方声明。
 */
@Composable
fun SetRow(
    icon: String,
    title: String,
    sub: String = "",
    trailing: String = "",
    accent: Boolean = false,
    dim: Boolean = false,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .background(if (danger) t.danger.copy(alpha = 0.14f) else t.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Text(icon, fontSize = 16.sp, color = if (danger) t.danger else t.accent) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) t.danger else t.text, fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold)
            if (sub.isNotEmpty()) Text(
                sub, color = t.text3, fontSize = 11.5.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (trailing.isNotEmpty()) {
            Text(
                trailing, color = if (dim && !accent) t.text3 else t.accent, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 在浏览器/系统应用中打开外部链接(缺省浏览器不存在时静默失败,不阻塞 UI)。 */
fun openExternal(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
fun Pill(text: String, color: Color = LocalTokens.current.accent, filled: Boolean = false) {
    val t = LocalTokens.current
    Text(
        text, color = if (filled) t.accentInk else color, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RFull)
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 模型切换条:强调色圆点 + 模型名 + ⌄,点击打开模型表。
 * 会话列表与对话页顶部共用同一外观(模型选择入口保持一致)。
 */
@Composable
fun ModelChip(modelName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    // 未选模型时:圆点转为告警色、文案转弱 —— 一眼看出「还没选模型」需要用户动手
    val picked = modelName.isNotBlank()
    // 整块芯片是一个按钮:onClickLabel 让读屏说清「双击可以做什么」,而不是只读一个模型名
    val pickLabel = stringResource(R.string.cd_model_picker)
    Row(
        modifier = modifier.clip(RFull).background(t.chip)
            .clickable(onClickLabel = pickLabel, role = Role.Button) { onClick() }
            .padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RFull).background(if (picked) t.accent else t.warn))
        Spacer(Modifier.width(6.dp))
        Text(
            modelName.ifBlank { stringResource(R.string.model_chip_empty) },
            color = if (picked) t.text2 else t.warn, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 170.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("⌄", color = t.text3, fontSize = 12.sp)
    }
}

/**
 * 分段控件。默认撑满宽度(整行开关);放在带 weight 兄弟行的 Row 里时必须传入有界宽度 ——
 * 本组件是 Row 中无 weight 的子项,会先于 weight 兄弟被测量并吃掉全部可用宽度,
 * 不传宽度会把同级标签列挤成 0 宽(文字换行到不可见、行高暴增)。
 */
@Composable
fun SegRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onSelect: (Int) -> Unit,
) {
    val t = LocalTokens.current
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(t.surface)
        .border(1.dp, t.border, RoundedCornerShape(12.dp)).padding(3.dp)) {
        options.forEachIndexed { i, opt ->
            Text(
                opt, color = if (i == selectedIndex) t.accentInk else t.text2, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (i == selectedIndex) t.accent else Color.Transparent)
                    // 选中态平时只靠底色表达,读屏与无障碍树里看不出当前选的是哪个 —— 补 selected
                    .semantics { selected = i == selectedIndex }
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
            )
        }
    }
}

/* ---------------- Mini Markdown(段落/加粗/行内代码/代码块/列表) ---------------- */
@Composable
fun MiniMarkdown(text: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val out = mutableListOf<Pair<String, String>>()
    var buf = StringBuilder()
    var inCode = false
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add("p" to buf.toString())
            buf = StringBuilder()
        }
    }
    text.split("\n").forEach { line ->
        if (line.startsWith("```")) {
            if (inCode) {
                out.add("code" to buf.toString())
                buf = StringBuilder()
                inCode = false
            } else {
                flush()
                inCode = true
            }
        } else if (inCode) {
            buf.append(line).append("\n")
        } else if (line.startsWith("#")) {
            flush()
            out.add("h" to line.removePrefix("#").trim())
        } else if (line.startsWith("- ") || line.startsWith("* ")) {
            flush()
            out.add("li" to line.drop(2))
        } else if (line.trim().isEmpty()) {
            flush()
        } else {
            if (buf.isNotEmpty()) buf.append("\n")
            buf.append(line)
        }
    }
    if (inCode) out.add("code" to buf.toString()) else flush()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        out.forEach { (kind, content) ->
            when (kind) {
                "h" -> Text(content, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                "li" -> Row {
                    Text("•  ", color = t.accent, fontSize = 13.sp)
                    InlineRich(content, t)
                }
                "code" -> Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(t.codeBg).border(1.dp, t.border, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        content.trimEnd(), color = t.codeText, fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
                    )
                }
                else -> InlineRich(content, t)
            }
        }
    }
}

@Composable
private fun InlineRich(text: String, t: ThemeTokens) {
    val styled = buildAnnotatedString {
        val bold = Regex("""\*\*([^*]+)\*\*""")
        val code = Regex("""`([^`]+)`""")
        var pos = 0
        val parts = mutableListOf<Triple<Int, Int, String>>()
        bold.findAll(text).forEach { parts.add(Triple(it.range.first, it.range.last, "b")) }
        code.findAll(text).forEach { parts.add(Triple(it.range.first, it.range.last, "c")) }
        parts.sortBy { it.first }
        parts.forEach { (s, e, kind) ->
            if (pos < s) append(text.substring(pos, s))
            val inner = text.substring(s, e + 1)
            val clean = if (kind == "b") inner.substring(2, inner.length - 2)
            else inner.substring(1, inner.length - 1)
            if (kind == "b") withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(clean) }
            else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = t.accent)) { append(clean) }
            pos = e + 1
        }
        if (pos < text.length) append(text.substring(pos))
    }
    Text(styled, color = t.text, fontSize = 13.5.sp, lineHeight = 20.sp)
}

@Composable
fun SheetTitle(title: String, sub: String = "", modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Column(modifier = modifier.padding(bottom = 8.dp)) {
        Text(title, color = t.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) Text(
            sub, color = t.text3, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
fun SheetScaffold(title: String, sub: String = "", content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RFull).background(LocalTokens.current.surface3))
        }
        Spacer(Modifier.height(12.dp))
        SheetTitle(title, sub)
        content()
    }
}
