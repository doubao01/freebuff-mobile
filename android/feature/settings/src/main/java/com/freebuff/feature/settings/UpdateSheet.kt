package com.freebuff.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.RemoteVersion
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.theme.LocalTokens
import java.io.File
import java.util.Locale
import androidx.compose.ui.res.stringResource

/**
 * 检查更新:真实拉取更新源 JSON(默认本仓库公开的 dist/update.json,发版时自动刷新)。
 *
 * 三种结局各有去处:
 * - 有 `apk` 块 → 面板内直接下载、校验 sha256、拉起系统安装器(用户不用离开 App)
 * - 只有发布页地址 → 退化成「前往下载」(老清单 / 未带产物指纹)
 * - 地址非 https / 网络失败 / 解析失败 → 分别给出可读提示并支持重试
 *
 * 检查本身在数据层做了瞬时失败静默重试,所以这里只处理最终结果 ——
 * 一次网络抖动不该让用户先看到红字。
 */
@Composable
fun UpdateSheet(viewModel: SettingsViewModel = hiltViewModel()) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val t = LocalTokens.current
    /** 版本显示用实际安装的包版本,避免「标记为已更新但装的是旧包」的错位。 */
    val version = viewModel.appVersion
    var phase by remember { mutableStateOf("checking") }
    var remote by remember { mutableStateOf<RemoteVersion?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    /** 拉起安装器失败时的兜底提示(少数机型/权限缺失下会走到)。 */
    var installError by remember { mutableStateOf<String?>(null) }
    /** 已把用户送去「安装未知应用」授权页,回来前先别催他点第二次。 */
    var needPermission by remember { mutableStateOf(false) }
    val download by viewModel.download.collectAsState()

    LaunchedEffect(attempt) {
        phase = "checking"
        error = null
        installError = null
        needPermission = false
        // 面板重开会重新检查:把上一轮的下载进度/错误清掉,否则会停在旧状态上
        viewModel.resetUpdateDownload()
        viewModel.checkForUpdate { rv, hasUpdate, err ->
            remote = rv
            error = err
            phase = when {
                err != null -> "error"
                hasUpdate -> "update"
                else -> "latest"
            }
        }
    }

    // 下好且校验通过 → 直接拉系统安装器:用户刚点过「下载并安装」,不该再点第三次
    LaunchedEffect(download) {
        val ready = download as? UpdateDownload.Ready ?: return@LaunchedEffect
        installError = launchInstaller(context, ready.file)
    }

    SheetScaffold(stringResource(R.string.set_update_title), stringResource(R.string.set_update_current_prefix) + version) {
        when (phase) {
            "checking" -> Column {
                Text(stringResource(R.string.set_update_checking), color = t.text2, fontSize = 14.sp)
                Text(
                    if (viewModel.updateConfigured) stringResource(R.string.set_update_source_prefix) + viewModel.updateSource + stringResource(R.string.set_update_source_suffix)
                    else stringResource(R.string.set_update_not_configured),
                    color = t.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 5.dp),
                )
            }

            "error" -> Column {
                Text(stringResource(R.string.set_update_failed), color = t.danger, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(error ?: stringResource(R.string.set_unknown_error), color = t.text2, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.set_retry), color = t.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RFull).background(t.accent).clickable { attempt++ }
                        .padding(horizontal = 26.dp, vertical = 11.dp))
                Text(
                    if (viewModel.updateConfigured)
                        stringResource(R.string.set_update_net_hint_prefix) + viewModel.updateSource + ")"
                    else stringResource(R.string.set_update_no_source),
                    color = t.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }

            "update" -> {
                val rv = remote
                val downloadUrl = rv?.url.orEmpty()
                val canInline = rv != null && rv.canDownloadInApp
                Column {
                    Text(stringResource(R.string.set_update_available), color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.set_update_latest_prefix) + (rv?.version ?: "") + stringResource(R.string.set_update_you_have_prefix) + version,
                        color = t.text2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))

                    // 一句话摘要:先让人知道这次值不值得更新,再展开明细
                    val summary = rv?.summary.orEmpty()
                    if (summary.isNotBlank()) {
                        Text(summary, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 10.dp))
                    }

                    val notes = rv?.notes.orEmpty()
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.set_update_notes_prefix) + notes.size + stringResource(R.string.set_snapshot_filter_suffix), color = t.text3, fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium)
                        notes.forEach { i ->
                            Text("• " + i, color = t.text2, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 5.dp))
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    when (val dl = download) {
                        is UpdateDownload.Running -> Column {
                            Text(
                                if (dl.total > 0)
                                    stringResource(R.string.set_update_downloading_prefix) + percent(dl.received, dl.total) + "%(" +
                                        sizeText(dl.received) + " / " + sizeText(dl.total) + ")"
                                else stringResource(R.string.set_update_downloading_prefix) + sizeText(dl.received),
                                color = t.text2, fontSize = 12.5.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Box(
                                Modifier.fillMaxWidth().height(6.dp).clip(RFull).background(t.surface3),
                            ) {
                                if (dl.total > 0) {
                                    Box(
                                        Modifier.fillMaxHeight()
                                            .fillMaxWidth(
                                                (dl.received.toFloat() / dl.total.toFloat()).coerceIn(0f, 1f),
                                            )
                                            .clip(RFull).background(t.accent),
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.set_cancel), color = t.text2, fontSize = 12.5.sp,
                                modifier = Modifier.clip(RFull).background(t.surface3)
                                    .clickable { viewModel.resetUpdateDownload() }
                                    .padding(horizontal = 22.dp, vertical = 10.dp))
                        }

                        is UpdateDownload.Ready -> Column {
                            Text(stringResource(R.string.set_update_verified), color = t.ok, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold)
                            Text(dl.file.name + " · " + sizeText(dl.file.length()), color = t.text3,
                                fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.set_update_install_now), color = t.accentInk, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RFull).background(t.accent)
                                    .clickable { installError = launchInstaller(context, dl.file) }
                                    .padding(horizontal = 26.dp, vertical = 11.dp))
                        }

                        is UpdateDownload.Failed -> Column {
                            Text(stringResource(R.string.set_update_download_failed), color = t.danger, fontSize = 15.sp,
                                fontWeight = FontWeight.Bold)
                            Text(dl.message, color = t.text2, fontSize = 12.5.sp,
                                modifier = Modifier.padding(top = 6.dp))
                            Spacer(Modifier.height(18.dp))
                            Text(stringResource(R.string.set_retry), color = t.accentInk, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RFull).background(t.accent)
                                    .clickable { rv?.let { viewModel.startUpdateDownload(it) } }
                                    .padding(horizontal = 26.dp, vertical = 11.dp))
                        }

                        UpdateDownload.Idle -> Column {
                            if (canInline && rv != null) {
                                Text(
                                    stringResource(R.string.set_update_download_install) + if (rv.apkSize > 0) "(" + sizeText(rv.apkSize) + ")" else "",
                                    color = t.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RFull).background(t.accent).clickable {
                                        if (canInstallUnknownApps(context)) {
                                            viewModel.startUpdateDownload(rv)
                                        } else {
                                            // 没有「安装未知应用」权限时先送用户去开,回来再点这次下载
                                            needPermission = true
                                            openInstallPermission(context)
                                        }
                                    }.padding(horizontal = 26.dp, vertical = 11.dp),
                                )
                                Text(stringResource(R.string.set_update_verify_note), color = t.text3,
                                    fontSize = 10.5.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                            if (downloadUrl.isNotBlank()) {
                                Spacer(Modifier.height(14.dp))
                                Text(stringResource(R.string.set_update_go_download), color = t.text2, fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RFull).background(t.surface3).clickable {
                                        uriHandler.openUri(downloadUrl)
                                    }.padding(horizontal = 26.dp, vertical = 11.dp))
                                Text(
                                    if (canInline) stringResource(R.string.set_update_manual_note)
                                    else stringResource(R.string.set_update_no_direct),
                                    color = t.text3, fontSize = 10.5.sp, modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }

                    if (needPermission) {
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.set_update_allow_install), color = t.danger,
                            fontSize = 11.sp)
                    }
                    installError?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = t.danger, fontSize = 11.sp)
                    }
                }
            }

            else -> Column {
                Text(stringResource(R.string.set_update_up_to_date), color = t.ok, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.set_update_current_prefix) + version + stringResource(R.string.set_update_latest_suffix), color = t.text2, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.set_update_source_label_prefix) + viewModel.updateSource, color = t.text3, fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** 已下载/总量 → 百分比整数;总量为 0 时不显示进度条,交给调用方兜底。 */
private fun percent(received: Long, total: Long): Int =
    ((received.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)

/** 字节 → 人读的大小(下载量与包体积都要用)。 */
private fun sizeText(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

/** 安装未知应用的授权状态(API 26+,与 minSdk 对齐)。 */
private fun canInstallUnknownApps(context: Context): Boolean =
    context.packageManager.canRequestPackageInstalls()

private fun openInstallPermission(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:" + context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/**
 * 把校验过的安装包交给系统安装器。
 * 只经 FileProvider 交出 content:// —— 缓存目录不在共享存储上,直接给路径装不了。
 *
 * @return 失败文案(不是抛异常):调用方拿它显示兜底提示,例如找不到 Activity 的定制 ROM
 */
private fun launchInstaller(context: Context, file: File): String? = try {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
    null
} catch (t: Throwable) {
    // 这里是普通函数(非 @Composable),取文案要走 Context
    context.getString(R.string.set_update_open_installer_failed) + file.absolutePath
}
