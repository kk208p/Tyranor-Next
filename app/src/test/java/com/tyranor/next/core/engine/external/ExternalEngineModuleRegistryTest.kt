package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEngineModuleRegistryTest {
    @Test
    fun resolvesRegisteredExternalEngines() {
        assertSame(RuffleExternalEngineModule, ExternalEngineModuleRegistry.moduleForEngine(EngineType.RUFFLE))
        assertNull(ExternalEngineModuleRegistry.moduleForEngine(EngineType.RPGMAKER))
        assertNull(ExternalEngineModuleRegistry.moduleForEngine(EngineType.RENPY))
        assertNull(ExternalEngineModuleRegistry.moduleForEngine(EngineType.KIRIKIRI))
        assertFalse(ExternalEngineModuleRegistry.isExternalEngine(EngineType.RPGMAKER))
        assertFalse(ExternalEngineModuleRegistry.isExternalEngine(EngineType.RENPY))
        assertTrue(ExternalEngineModuleRegistry.isExternalEngine(EngineType.RUFFLE))
    }

    @Test
    fun resolvesModulesByAlias() {
        assertSame(RuffleExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("internal.flash"))
        assertNull(ExternalEngineModuleRegistry.moduleForAlias("internal.rpgmvxace"))
        assertNull(ExternalEngineModuleRegistry.moduleForAlias("internal.renpy"))
        assertNull(ExternalEngineModuleRegistry.moduleForAlias(null))
    }
}
