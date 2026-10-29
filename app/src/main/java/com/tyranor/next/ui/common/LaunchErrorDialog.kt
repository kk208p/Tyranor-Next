package com.tyranor.next.ui.common

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.game.launch.LaunchResult

/** 启动失败展示态：本地化文案。 */
data class LaunchErrorState(
    val message: String,
)

/** 启动结果 -> 展示态；成功返回 null。 */
fun LaunchResult.toErrorState(context: Context): LaunchErrorState? {
    val message = userMessage(context) ?: return null
    return LaunchErrorState(message)
}

/** 统一启动失败弹窗。 */
@Composable
fun LaunchErrorDialog(state: LaunchErrorState, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.game_launch_failed),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = { Text(state.message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            DialogTextButton(text = stringResource(R.string.common_confirm), onClick = onDismiss)
        },
    )
}
