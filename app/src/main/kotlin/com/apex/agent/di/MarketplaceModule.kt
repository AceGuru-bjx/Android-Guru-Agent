package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.marketplace.ClawHubSource
import com.apex.agent.core.tools.marketplace.HubSource
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.github.GithubTokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarketplaceModule {

    /**
     * 魔搭（ModelScope）技能源：对接官方 modelscope/modelscope-skills 仓库。
     * GitHub token 可选注入（已登录用户走认证限流配额，未登录走匿名配额）。
     */
    @Provides
    @Singleton
    fun provideModelScopeSource(
        httpClient: OkHttpClient,
        githubTokenManager: GithubTokenManager
    ): ModelScopeSource {
        return ModelScopeSource(httpClient, gitHubToken = githubTokenManager.getToken())
    }

    /**
     * ClawHub（clawhub.ai）技能仓库源：公开只读 API，无需认证。
     * 供市场 Skills 页签（浏览/搜索/下载安装）与 [com.apex.agent.marketplace.MarketInstallManager] 使用。
     */
    @Provides
    @Singleton
    fun provideClawHubSource(httpClient: OkHttpClient): ClawHubSource {
        return ClawHubSource(httpClient)
    }

    /**
     * 官方 Hub 仓库源（apex-skill-hub + apex-mcp-hub 双目录）：
     * - 技能：62 个生活/通用技能（APK 内置瘦身后迁出），市场「官方仓库」直装；
     * - MCP：沙箱/远端服务器目录，市场「官方 MCP 仓库」安装 → 配置 → 启动。
     * raw.githubusercontent.com 只读，无需认证。
     */
    @Provides
    @Singleton
    fun provideHubSource(httpClient: OkHttpClient): HubSource {
        return HubSource(httpClient)
    }
}
