package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.templates.PromptTemplateRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * #197 模板工坊 DI —— PromptTemplateRegistry 首次接线到 app 层。
 *
 * core 层的提示词模板库（此前仅有测试消费）落盘在
 * `<filesDir>/prompt_templates/templates.json`；内置 10 套模板在构造时
 * 幂等播种（见 PromptTemplateRegistry.init 路径），scope 字段按分类自动
 * 分层（CODING → coding 工位，其余 → agent 工位）。
 */
@Module
@InstallIn(SingletonComponent::class)
object TemplateModule {

    @Provides
    @Singleton
    fun providePromptTemplateRegistry(
        @ApplicationContext context: Context
    ): PromptTemplateRegistry {
        return PromptTemplateRegistry(
            storageDir = File(context.filesDir, "prompt_templates")
        )
    }
}
