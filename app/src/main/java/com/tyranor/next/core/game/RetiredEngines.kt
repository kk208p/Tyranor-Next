package com.tyranor.next.core.game

import com.tyranor.next.core.engine.EngineType

/**
 * 本构建已移除、不能再在应用内运行的外置引擎。
 *
 * 历史游戏库/最近打开/快捷启动可能残留这些引擎的记录（Room 不做破坏性迁移），
 * 展示层统一过滤，避免用户看到"存在但点开会失败"的条目。随着外置模块逐个移除，
 * 只在此处追加枚举值即可，不再散落各处判断。
 */
internal val RETIRED_EXTERNAL_ENGINES: Set<EngineType> = setOf(
    EngineType.RENPY,
    EngineType.RPGMAKER,
    EngineType.RUFFLE,
    EngineType.YURIS,
    EngineType.CATSYSTEM2,
    EngineType.PC,
    EngineType.PSP,
    EngineType.NINTENDO_SWITCH,
)
