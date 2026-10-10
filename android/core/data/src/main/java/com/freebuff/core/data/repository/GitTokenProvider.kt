package com.freebuff.core.data.repository

/**
 * 已授权 Git token 的读取口。
 *
 * 为什么要单独一个口而不是让执行器直接依赖 [GitAuthRepository]:执行层只需要「拿到当前 token」,
 * 不需要授权流程、仓库列表与撤销能力 —— 接口越小,测试里越好替身(单测传 `GitTokenProvider { "" }` 即可)。
 *
 * **安全约定**:token 只用于请求头,绝不写进给模型看的文本(工具结果、错误信封)、日志或界面。
 */
fun interface GitTokenProvider {
    /** 当前 access_token;**未连接时返回空串**(调用方据此回「需要先连接 Git 账号」的处方)。 */
    suspend fun token(): String
}
