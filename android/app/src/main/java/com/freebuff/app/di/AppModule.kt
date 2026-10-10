package com.freebuff.app.di

import com.freebuff.app.AgentTaskNotifierImpl
import com.freebuff.app.AppNavigatorImpl
import com.freebuff.core.ui.navigation.AppNavigator
import com.freebuff.core.ui.platform.AgentTaskNotifier
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * app 模块装配:
 * - [AppNavigator] 单例绑定(route/sheet/snackbar 全局状态)
 * - [AgentTaskNotifier] 单例绑定(长任务前台保活 + 完成通知:实现要用 Context 与 Android 通知 API,
 *   所以接口在 core:ui、实现留在 app,feature 层只注入接口)
 * - 三个可配置密钥与已安装版本号只读自 BuildConfig,feature/core 层经 @Named 注入,
 *   不直接引用 app 的 BuildConfig。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindAppNavigator(impl: AppNavigatorImpl): AppNavigator

    @Binds
    @Singleton
    abstract fun bindAgentTaskNotifier(impl: AgentTaskNotifierImpl): AgentTaskNotifier

    companion object {
        @Provides
        @Named("gatewayBaseUrl")
        fun provideGatewayBaseUrl(): String = com.freebuff.mobile.BuildConfig.DEFAULT_GATEWAY_BASE_URL

        @Provides
        @Named("githubClientId")
        fun provideGithubClientId(): String = com.freebuff.mobile.BuildConfig.GITHUB_OAUTH_CLIENT_ID

        @Provides
        @Named("updateUrl")
        fun provideUpdateUrl(): String = com.freebuff.mobile.BuildConfig.UPDATE_URL

        /** 已安装版本号(源自 android/version.properties):设置页与关于页的版本显示以此为准。 */
        @Provides
        @Named("appVersion")
        fun provideAppVersion(): String = com.freebuff.mobile.BuildConfig.VERSION_NAME
    }
}
