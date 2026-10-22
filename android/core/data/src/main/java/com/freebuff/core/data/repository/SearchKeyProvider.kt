package com.freebuff.core.data.repository

/**
 * 联网搜索用的 API 密钥来源(真实实现在 [SearchKeyRepository],密文存库、Keystore 解密)。
 *
 * 与 [GitTokenProvider] 同构:执行器只认「给我一个密钥」这一件事,
 * 不需要加解密、设置读写与「是否已配置」这些细节 —— 接口越小,测试里越好替身
 * (单测传 `SearchKeyProvider { "" }` 即可走免钥降级路径)。
 *
 * **约定:没有可用密钥时返回空串,不要抛异常。** 调用方据此降级到免钥通道,
 * 而不是把「没配密钥」变成一次工具失败。
 */
fun interface SearchKeyProvider {
    suspend fun apiKey(): String
}
