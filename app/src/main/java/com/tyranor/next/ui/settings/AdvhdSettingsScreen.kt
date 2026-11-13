package com.tyranor.next.ui.settings

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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun advhdNlsOptions(): List<Pair<String, String>> = listOf(
    EngineSettingsStore.ADVHD_NLS_SJIS to "SJIS / CP932",
    EngineSettingsStore.ADVHD_NLS_GBK to "GBK / CP936",
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
    var importingFont by remember { mutableStateOf(false) }
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
