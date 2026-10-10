package com.freebuff.core.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用内语言(跟随系统 / 简体中文 / English)。
 *
 * 为什么存在这里、而不是 Room 的 settings 表:语言必须在 `Activity.attachBaseContext`
 * 里**同步**读到 —— 那一刻还没有协程、没有 Hilt 注入,DAO 是 suspend 的,读不了。
 * 所以这一项落在 SharedPreferences,并且不经过 ViewModel。
 *
 * 生效路径按系统版本分两条(见 docs/i18n-and-accessibility.md §1):
 * - **API 33+**:系统的**应用级语言** `LocaleManager.setApplicationLocales`。
 *   Activity 与 Application 的字符串一起切换,系统自己重建界面。
 * - **API 26–32**:没有该 API,退化为在 Activity 的 base context 上包一层 Configuration
 *   ([wrap],由 `MainActivity.attachBaseContext` 调用)。此时 Compose 里的 `stringResource`
 *   立即跟随,但通过 `@ApplicationContext` 取到的少数 ViewModel 文案要等下次启动。
 */
object AppLanguage {

    /** 跟随系统(默认)。 */
    const val SYSTEM = "system"

    /** 简体中文。 */
    const val ZH = "zh"

    /** 英文。 */
    const val EN = "en"

    /** 设置页分段控件的取值顺序。 */
    val OPTIONS = listOf(SYSTEM, ZH, EN)

    private const val PREFS = "freebuff_app"
    private const val KEY = "language"

    /** 选项 → BCP-47 语言标记;跟随系统不在表里(表示「不加覆盖」)。 */
    private val TAGS = mapOf(ZH to "zh-Hans", EN to "en")

    private val _language = MutableStateFlow(SYSTEM)

    /** 当前选择,供设置页展示;[current] 会把它刷新成磁盘上的值。 */
    val language: StateFlow<String> = _language.asStateFlow()

    /** 归一化:只认白名单,其余(未知语言、空值、坏数据)一律按「跟随系统」。 */
    fun normalize(tag: String?): String = when {
        tag.isNullOrBlank() -> SYSTEM
        tag.startsWith(ZH) -> ZH
        tag.startsWith(EN) -> EN
        else -> SYSTEM
    }

    /**
     * 读取当前选择(同步)。
     *
     * API 33+ 以**系统侧**的应用级语言为准:用户可能直接在「设置 → 应用 → 语言」里改过,
     * 那种情况下本地偏好就过期了。系统侧未设置(返回空)才回落到本地偏好。
     */
    fun current(context: Context): String {
        val mode = systemOverride(context) ?: normalize(prefs(context).getString(KEY, SYSTEM))
        _language.value = mode
        return mode
    }

    /**
     * 切换语言:落盘并立即生效。
     *
     * @return 调用方是否需要自己重建界面 —— **26–32 返回 true**(没有系统级 API);
     *   33+ 由系统重建,返回 false(再调一次 `recreate()` 只会多闪一下)。
     */
    fun set(context: Context, tag: String): Boolean {
        val mode = normalize(tag)
        prefs(context).edit().putString(KEY, mode).apply()
        _language.value = mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val bcp47 = TAGS[mode]
            context.getSystemService(LocaleManager::class.java)
                ?.setApplicationLocales(
                    if (bcp47 == null) LocaleList.getEmptyLocaleList()
                    else LocaleList.forLanguageTags(bcp47),
                )
            return false
        }
        return true
    }

    /** 把当前选择套到 Activity 的 base context 上;跟随系统时原样返回(不动系统行为)。 */
    fun wrap(base: Context): Context {
        val bcp47 = TAGS[current(base)] ?: return base
        val conf = Configuration(base.resources.configuration)
        conf.setLocales(LocaleList.forLanguageTags(bcp47))
        return base.createConfigurationContext(conf)
    }

    /** 系统侧的应用级语言(仅 33+);未设置过返回 null,表示跟随系统。 */
    private fun systemOverride(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val tags = context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags()
        return if (tags.isNullOrBlank()) null else normalize(tags)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
