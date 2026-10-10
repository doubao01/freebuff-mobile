package com.freebuff.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.TaskDraft
import com.freebuff.core.model.parseClone
import androidx.compose.runtime.LaunchedEffect
import com.freebuff.core.ui.R14
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens
import com.freebuff.core.model.normRepoUrl
import androidx.compose.ui.res.stringResource

private val REPO_HOSTS = listOf("github.com", "gitlab.com", "gitee.com")

/**
 * 发起任务三步向导:仓库 → 模型 → 描述任务。
 * 提交时创建会话并回调 [onLaunch] 触发真实对话。
 */
@Composable
fun TaskWizardSheet(
    onLaunch: (sessionId: String, desc: String, ctxRepo: String, ctxModel: String) -> Unit,
    viewModel: TaskWizardViewModel = hiltViewModel(),
) {
    val t = LocalTokens.current
    val d by viewModel.draft.collectAsState()
    val modelList by viewModel.modelList.collectAsState()
    // 把草稿模型对齐当前对话模型(草稿是 Activity 级、会跨次保留)。
    // 监听的是 StateFlow:设置值由 DAO 异步读入,首帧可能是初始值,拿到真值后会再同步一次。
    val currentModelId by viewModel.currentModelId.collectAsState()
    LaunchedEffect(currentModelId) { viewModel.syncDraftModel() }
    SheetScaffold(stringResource(R.string.chat_wizard_title), stringResource(R.string.chat_wizard_sub)) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    (0..2).forEach { i ->
                        val on = i <= d.step
                        Box(Modifier.size(7.dp).clip(RFull).background(if (on) t.accent else t.surface3))
                        if (i < 2) Spacer(Modifier.width(6.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(listOf(stringResource(R.string.chat_wizard_step_repo), stringResource(R.string.chat_wizard_step_model), stringResource(R.string.chat_wizard_step_desc))[d.step], color = t.text2, fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            item { Spacer(Modifier.height(14.dp)) }
            when (d.step) {
                0 -> item { RepoStep(viewModel, d) }
                1 -> item { ModelStep(viewModel, d, modelList) }
                else -> item { DescStep(viewModel, d, modelList) }
            }
        }
        WizardFooter(viewModel, d, modelList, onLaunch = onLaunch)
    }
}

/* ---------------- Step 1: 仓库 ---------------- */

@Composable
private fun RepoStep(vm: TaskWizardViewModel, d: TaskDraft) {
    val t = LocalTokens.current
    val modes = listOf(stringResource(R.string.chat_wizard_repo_none), stringResource(R.string.chat_wizard_repo_manual), stringResource(R.string.chat_wizard_repo_git))
    val repoModes = listOf("none", "manual", "git")
    Row(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(4.dp)) {
        modes.forEachIndexed { i, label ->
            val on = d.repoMode == repoModes[i]
            Text(label, color = if (on) t.accentInk else t.text3, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (on) t.accent else Color.Transparent)
                    .clickable { vm.update { it.copy(repoMode = repoModes[i]) } }
                    .padding(vertical = 8.dp), textAlign = TextAlign.Center)
        }
    }
    Spacer(Modifier.height(14.dp))
    when (d.repoMode) {
        "manual" -> ManualRepo(vm, d)
        "git" -> GitRepos(vm, d)
        else -> Column {
            Text(stringResource(R.string.chat_wizard_repo_none_title), color = t.text3, fontSize = 12.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.chat_wizard_repo_none_hint), color = t.text3, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun ManualRepo(vm: TaskWizardViewModel, d: TaskDraft) {
    val t = LocalTokens.current
    val strict = vm.repoParse.collectAsState().value == "strict"
    Column {
        Row(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = d.repoUrl,
                    onValueChange = { v ->
                        var nd = d.copy(repoUrl = v)
                        if (strict) parseClone(v)?.let { cp ->
                            nd = nd.copy(repoUrl = cp.repo, repoName = cp.repo, repoBranch = cp.branch)
                        }
                        vm.update { nd }
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(color = t.text, fontSize = 13.sp),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (d.repoUrl.isEmpty()) Text(stringResource(R.string.chat_wizard_repo_url_hint), color = t.text3, fontSize = 13.sp)
                            inner()
                        }
                    },
                )
            }
        }
        if (strict) {
            Row(Modifier.padding(top = 10.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                REPO_HOSTS.forEach { h ->
                    val dot = when { h.startsWith("github") -> t.accent; h.startsWith("gitlab") -> t.tier; else -> t.warn }
                    Row(Modifier.clip(RFull).background(t.surface2).clickable {
                        vm.update { d.copy(repoUrl = h + "/", repoName = "") }
                    }.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RFull).background(dot))
                        Spacer(Modifier.width(5.dp))
                        Text(h, color = t.text2, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
            if (d.repoUrl.isNotBlank()) {
                val norm = normRepoUrl(d.repoUrl)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(norm, color = t.text3, fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (norm == d.repoUrl.trim()) stringResource(R.string.chat_wizard_repo_as_is) else stringResource(R.string.chat_wizard_repo_completed),
                        color = if (norm == d.repoUrl.trim()) t.text3 else t.accent,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RFull).background(t.surface2).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        } else {
            Text(stringResource(R.string.chat_wizard_repo_loose), color = t.text3, fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (d.repoBranch.isNotBlank()) {
            Text(stringResource(R.string.chat_wizard_branch_prefix) + d.repoBranch, color = t.accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).background(t.accentSoft).padding(horizontal = 9.dp, vertical = 4.dp).padding(top = 8.dp))
        }
        d.repoUrl.takeIf { it.isNotBlank() }?.let { v ->
            val ok = !strict || parseClone(v) != null || v.contains("/")
            if (!ok) {
                Text(stringResource(R.string.chat_wizard_repo_incomplete), color = t.danger, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun GitRepos(vm: TaskWizardViewModel, d: TaskDraft) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val git by vm.git.collectAsState()
    if (!git.connected) {
        Column {
            Text(stringResource(R.string.chat_wizard_git_none), color = t.text3, fontSize = 12.5.sp)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.chat_wizard_link_now), color = t.accentInk, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(R14).background(t.accent).clickable { navigator.openSheet(AppNavState.SHEET_GIT, AppNavState.SHEET_TASK) }
                    .padding(horizontal = 14.dp, vertical = 8.dp))
        }
    } else {
        val repos by vm.repos.collectAsState()
        val loading by vm.reposLoading.collectAsState()
        val error by vm.reposError.collectAsState()
        // 从 Git 授权弹层回到向导时,账号状态可能刚变,自动刷新一次仓库列表
        LaunchedEffect(git.connected, git.login) {
            if (git.connected) vm.loadRepos()
        }
        when {
            loading -> {
                Text(stringResource(R.string.chat_wizard_loading_repos), color = t.text3, fontSize = 12.5.sp)
                Spacer(Modifier.height(8.dp))
            }
            error != null -> {
                Text(stringResource(R.string.chat_wizard_repos_failed) + error, color = t.warn, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.action_retry), color = t.accentInk, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(R14).background(t.accent).clickable { vm.loadRepos() }
                        .padding(horizontal = 14.dp, vertical = 8.dp))
                Spacer(Modifier.height(10.dp))
            }
            repos.isEmpty() -> {
                Text(stringResource(R.string.chat_wizard_repos_empty), color = t.text3, fontSize = 12.5.sp)
                Spacer(Modifier.height(8.dp))
            }
        }
        repos.forEach { r ->
            val on = d.repoName == r.name
            Column(Modifier.fillMaxWidth().clip(R14).background(t.surface2).clickable {
                vm.update { d.copy(repoName = r.name, repoBranch = r.branch, repoUrl = "https://" + r.name + ".git") }
            }.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, color = t.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    if (on) Text(stringResource(R.string.chat_wizard_picked), color = t.accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
                Text(r.branch + " · " + r.desc, color = t.text3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/* ---------------- Step 2: 模型 ---------------- */

@Composable
private fun ModelStep(vm: TaskWizardViewModel, d: TaskDraft, all: List<com.freebuff.core.model.OfficialModel>) {
    val t = LocalTokens.current
    // 正式构建可能一个模型都没有(未配网关、也没加自定义模型):
    // 给出可执行的下一步,而不是一个空空的「官方模型」标题
    if (all.isEmpty()) {
        Text(stringResource(R.string.chat_wizard_no_models), color = t.warn, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.chat_wizard_no_models_hint),
            color = t.text3, fontSize = 11.5.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 6.dp))
        return
    }
    val official = all.filter { it.tier != "custom" }
    if (official.isNotEmpty()) {
        Text(stringResource(R.string.chat_wizard_official), color = t.text3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
        official.forEach { m ->
            WizardModelRow(m, d.modelId == m.id, t.accent) { vm.update { d.copy(modelId = m.id) } }
        }
    }
    if (all.any { it.tier == "custom" }) {
        Text(stringResource(R.string.chat_wizard_mine), color = t.text3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 6.dp))
        all.filter { it.tier == "custom" }.forEach { m ->
            WizardModelRow(m, d.modelId == m.id, t.warn) { vm.update { d.copy(modelId = m.id) } }
        }
    }
}

@Composable
private fun WizardModelRow(
    m: com.freebuff.core.model.OfficialModel,
    on: Boolean,
    badgeColor: Color,
    onClick: () -> Unit,
) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().clip(R14).background(t.surface2).clickable { onClick() }
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(m.badge, color = t.accentInk, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(badgeColor).padding(horizontal = 6.dp, vertical = 3.dp))
        Spacer(Modifier.width(8.dp))
        Text(m.name, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (on) Text(stringResource(R.string.chat_wizard_picked), color = t.accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
}

/* ---------------- Step 3: 描述 ---------------- */

@Composable
private fun DescStep(vm: TaskWizardViewModel, d: TaskDraft, modelList: List<com.freebuff.core.model.OfficialModel>) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(12.dp)) {
        androidx.compose.foundation.text.BasicTextField(
            value = d.desc,                    onValueChange = { v ->
                        // 与 DESC_MAX 一致:超限直接截断,避免提交时才报错
                        vm.update { d.copy(desc = if (v.length > 500) v.take(500) else v) }
                    },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = t.text, fontSize = 13.5.sp, lineHeight = 20.sp),
            decorationBox = { inner ->
                Box {
                    if (d.desc.isEmpty()) Text(stringResource(R.string.chat_wizard_desc_hint), color = t.text3, fontSize = 13.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(d.desc.length.toString() + " / 500", color = if (d.desc.length >= 500) t.warn else t.text3,
            fontSize = 10.5.sp, fontWeight = if (d.desc.length >= 500) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.align(Alignment.End))
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.chat_wizard_model_prefix), color = t.text3, fontSize = 12.sp)
        val picked = modelList.firstOrNull { it.id == d.modelId }?.name ?: d.modelId
        Text(picked.ifBlank { stringResource(R.string.chat_wizard_not_picked) }, color = if (picked.isBlank()) t.warn else t.text2, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold)
        if (d.repoName.isNotBlank() || d.repoUrl.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.chat_wizard_repo_prefix), color = t.text3, fontSize = 12.sp)
            Text(if (d.repoName.isNotBlank()) d.repoName else d.repoUrl, color = t.text2, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ---------------- 底部导航 ---------------- */

@Composable
private fun WizardFooter(
    vm: TaskWizardViewModel,
    d: TaskDraft,
    modelList: List<com.freebuff.core.model.OfficialModel>,
    onLaunch: (sessionId: String, desc: String, ctxRepo: String, ctxModel: String) -> Unit,
) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (d.step > 0) {
            Text(stringResource(R.string.action_prev), color = t.text2, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).clickable { vm.prev() }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        val last = d.step == 2
        // 待补描述时是「禁用态」:底色换成 surface3,文字也必须跟着换成 text2 ——
        // accentInk(浅色主题是白、深色主题是近黑)压在 surface3 上只有 1.2:1,按钮会像没字
        val ready = !last || d.desc.isNotBlank()
        Text(if (last) stringResource(R.string.chat_wizard_title) else stringResource(R.string.chat_wizard_next),
            color = if (ready) t.accentInk else t.text2, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RFull).background(if (ready) t.accent else t.surface3)
                .clickable {
                    if (last) {
                        vm.launch(onLaunch)
                    } else {
                        vm.next()
                    }
                }.padding(horizontal = 22.dp, vertical = 10.dp))
    }
}
