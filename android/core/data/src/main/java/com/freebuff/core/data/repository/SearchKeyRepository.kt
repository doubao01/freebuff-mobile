package com.freebuff.core.data.repository

import com.freebuff.core.data.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 搜索 API 密钥(Exa)的本机安全存放。
 *
 * 存法与本项目其它密钥一致(Git token / 自定义模型 key):**明文只存在于内存**,
 * 落库的是 Android Keystore 里的 AES/GCM 密文(见 [CryptoManager])。因此
 * - 备份/换机带走的库文件里没有可用密钥;
 * - root 之外拿不到密钥(密钥材料在系统 Keystore,不在应用进程);
 * - 应用自身也**不回显**密钥,只暴露「有没有配置」([configured])。
 *
 * 为什么要单独一层而不是让执行器直接读设置:执行器(Core 层)不该知道加解密与存储细节,
 * 也不该拿到「密钥是否配置」以外的任何信息 —— 见 [SearchKeyProvider]。
 */
@Singleton
class SearchKeyRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val crypto: CryptoManager,
) : SearchKeyProvider {

    /** 是否已配置(只看密文是否非空;任何情况下都不回显明文)。 */
    val configured: Flow<Boolean> = settings.getStringFlow(SettingsRepository.KEY_SEARCH_API_KEY, "")
        .map { it.isNotBlank() }
        .distinctUntilChanged()

    /**
     * 保存密钥(空串 = 清除)。
     * 先 trim:从配置页复制粘贴常带首尾空白/换行,原样加密会让请求头带坏字符而 401。
     */
    suspend fun set(plain: String) {
        val key = plain.trim()
        settings.setString(
            SettingsRepository.KEY_SEARCH_API_KEY,
            if (key.isEmpty()) "" else crypto.encrypt(key),
        )
    }

    suspend fun clear() = set("")

    /**
     * 取明文密钥给执行器。未配置、或密文解不开(换机 / Keystore 被清 / 库被拷走)时返回空串 ——
     * 调用方据此降级到免钥通道。**不抛异常**:一条搜不到的搜索结果,不配让整个工具调用失败。
     */
    override suspend fun apiKey(): String =
        crypto.decrypt(settings.getString(SettingsRepository.KEY_SEARCH_API_KEY, ""))
}
