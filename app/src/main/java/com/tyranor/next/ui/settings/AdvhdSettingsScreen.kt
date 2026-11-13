package com.tyranor.next.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.TopBarIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun advhdNlsOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.ADVHD_NLS_SJIS to "SJIS / CP932",
    EngineSettingsStore.ADVHD_NLS_GBK to "GBK / CP936",
    EngineSettingsStore.ADVHD_NLS_BIG5 to "Big5 / CP950",
    EngineSettingsStore.ADVHD_NLS_UTF8 to "UTF-8",
    EngineSettingsStore.ADVHD_NLS_UTF16LE to "UTF-16LE（ADVHD 2.1+）",
)

/**
 * AdvHD 全局设置：文本编码与 CJK 字体。
 *
 * 淘汰逻辑（入口脚本 / 用户目录）由运行时与启动预检负责，这里只暴露会影响
 * 所有作品的可选项；单游戏覆盖在「单游戏设置」页。
 */
@Composable
internal fun AdvhdSettingsScreen() {
    val context = LocalContext.current
    var nls by remember { mutableStateOf(EngineSettingsStore.getAdvhdNls(context)) }
    var font by remember { mutableStateOf(EngineSettingsStore.getAdvhdFont(context)) }
    var translation by remember { mutableStateOf(EngineSettingsStore.getAdvhdTranslation(context)) }
    var importingFont by remember { mutableStateOf(false) }
    var importingTranslation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !importingFont) {
            importingFont = true
            scope.launch {
                try {
                    val imported = FontImport.importToPrivate(context, uri)
                    if (imported != null) {
                        font = imported
                    } else {
                        Toast.makeText(context, R.string.advhd_error_font, Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    importingFont = false
                }
            }
        }
    }
    val translationPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !importingTranslation) {
            importingTranslation = true
            scope.launch {
                try {
                    val imported = importTranslation(context, uri)
                    if (imported != null) {
                        translation = imported
                    } else {
                        Toast.makeText(context, R.string.advhd_error_translation, Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    importingTranslation = false
                }
            }
        }
    }
    MiuixSettingsTheme {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.advhd_settings_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.engine_settings_save_content_description),
                            MiuixTheme.colorScheme.primary,
                        ) {
                            EngineSettingsStore.setAdvhdNls(context, nls)
                            EngineSettingsStore.setAdvhdFont(context, font)
                            EngineSettingsStore.setAdvhdTranslation(context, translation)
                            Toast.makeText(context, R.string.engine_settings_saved, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    EngineCard("AdvHD (WillPlus)") {
                        DropdownRow(stringResource(R.string.advhd_nls_title), advhdNlsOptions(), nls) { nls = it }
                        FontPreference(
                            label = stringResource(R.string.advhd_font_title),
                            value = font.substringAfterLast('/').ifBlank { stringResource(R.string.advhd_font_default) },
                            followLabel = stringResource(R.string.advhd_font_default),
                            onFollow = { if (!importingFont) font = "" },
                            onPick = { if (!importingFont) picker.launch("*/*") },
                            enabled = !importingFont,
                        )
                        FontPreference(
                            label = stringResource(R.string.advhd_translation_title),
                            value = translation.substringAfterLast('/').ifBlank { stringResource(R.string.advhd_translation_none) },
                            followLabel = stringResource(R.string.advhd_translation_none),
                            onFollow = { if (!importingTranslation) translation = "" },
                            onPick = { if (!importingTranslation) translationPicker.launch("*/*") },
                            enabled = !importingTranslation,
                        )
                        Text(
                            stringResource(R.string.advhd_settings_note),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Import a translation JSON into files/advhd/translations, validating size and JSON shape. */
internal suspend fun importTranslation(context: android.content.Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            val raw = uri.lastPathSegment ?: "translation.json"
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val name = base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "translation.json" }
            val fileName = if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
            val dir = java.io.File(context.filesDir, "advhd/translations").apply { mkdirs() }
            val canonicalDir = dir.canonicalFile
            val target = java.io.File(canonicalDir, fileName)
            if (target.canonicalFile.parentFile != canonicalDir) return@withContext null
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            if (target.length() == 0L || target.length() > 8L * 1024 * 1024) {
                target.delete()
                return@withContext null
            }
            org.json.JSONObject(target.readText()) // reject malformed JSON before it reaches the runtime
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }
