package com.tyranor.next.core.game.model

import java.io.File

/** Private writable root only; resource directories must never be mirrored as user data. */
object AdvhdPaths {
    fun userDataDir(filesDir: File, gameIdentity: String): File {
        return com.core.advhd.AdvhdPaths.userDataDir(filesDir, gameIdentity)
    }

    fun validateUserDataDir(filesDir: File, gameIdentity: String, suppliedPath: String): File =
        com.core.advhd.AdvhdPaths.validateUserDataDir(filesDir, gameIdentity, suppliedPath)

    /** Empty = no overlay; non-empty must live under files/advhd/translations and be readable JSON. */
    fun validatedTranslation(filesDir: File, path: String): String =
        com.core.advhd.AdvhdPaths.validatedTranslation(filesDir, path)
}
