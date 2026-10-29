package com.tyranor.next.ui.game

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GameCardItemKeyTest {
    private val game = ScanGame(
        title = "测试游戏",
        uri = "content://games/test",
        engine = EngineType.KIRIKIRI,
        launchTarget = "data.xp3",
    )

    @Test
    fun changesWhenCoverChanges() {
        val covered = game.copy(coverUri = "file:///covers/new.jpg", coverSource = "vndb")

        assertNotEquals(gameCardItemKey(game), gameCardItemKey(covered))
    }

    @Test
    fun staysStableForNonVisualLibraryFields() {
        val recentlyOpened = game.copy(openTime = 1234L)

        assertEquals(gameCardItemKey(game), gameCardItemKey(recentlyOpened))
    }

    @Test
    fun hidesSaveManagementForExternalApkEngines() {
        assertEquals(false, shouldShowSaveManagement(EngineType.RENPY))
        assertEquals(false, shouldShowSaveManagement(EngineType.ETHORNELL))
        assertEquals(false, shouldShowSaveManagement(EngineType.RPGMAKER))
        assertEquals(false, shouldShowSaveManagement(EngineType.RUFFLE))
        assertEquals(true, shouldShowSaveManagement(EngineType.KIRIKIRI))
        assertEquals(true, shouldShowSaveManagement(EngineType.RPG_MV))
        // 退役外置引擎（YU-RIS / CatSystem2 / PC / PSP / Switch）不再提供存档管理入口
        assertEquals(false, shouldShowSaveManagement(EngineType.YURIS))
        assertEquals(false, shouldShowSaveManagement(EngineType.NINTENDO_SWITCH))
        assertEquals(false, shouldShowSaveManagement(EngineType.PC))
        assertEquals(false, shouldShowSaveManagement(EngineType.CATSYSTEM2))
        // 手动添加的安卓应用：无文件存档接口，隐藏存档管理
        assertEquals(false, shouldShowSaveManagement(EngineType.ANDROID_APP))
        // engine 字段损坏回退 UNKNOWN 的安卓条目（uri 前缀仍在）同样隐藏
        assertEquals(
            false,
            shouldShowSaveManagement(
                game.copy(uri = "androidapp://com.example.game", engine = EngineType.UNKNOWN),
            ),
        )
    }
}
