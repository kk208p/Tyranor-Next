package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType

/** 外置 APK 引擎模块单一注册表。 */
object ExternalEngineModuleRegistry {
    val modules: List<ExternalEngineModule> = emptyList()

    fun moduleForEngine(engine: EngineType): ExternalEngineModule? =
        modules.firstOrNull { it.engine == engine }

    /**
     * 按内部别名查找模块。仅用于兼容历史扫描数据与测试；当前启动不走此路径——
     * Ruffle 由 [moduleForEngine] 按 EngineType 解析（默认别名只用于历史数据兼容）。
     */
    fun moduleForAlias(alias: String?): ExternalEngineModule? =
        modules.firstOrNull { it.supportsAlias(alias) }

    fun isExternalEngine(engine: EngineType): Boolean = moduleForEngine(engine) != null
}
