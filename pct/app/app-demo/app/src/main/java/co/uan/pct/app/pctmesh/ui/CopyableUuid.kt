package co.uan.pct.app.pctmesh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.uan.designsystem.uikit.theme.UanThemeTokens

@Composable
fun CopyableUuid(
    label: String,
    uuid: String,
    modifier: Modifier = Modifier,
) {
    val tokens = UanThemeTokens.current
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (uuid.isNotBlank()) clipboard.setText(AnnotatedString(uuid))
            },
    ) {
        Text(
            text = if (uuid.length >= 8) "$label · ${uuid.take(8)}" else label,
            style = tokens.typography.small,
            color = tokens.colors.muted,
        )
        if (uuid.isNotBlank()) {
            Text(
                text = uuid,
                style = tokens.typography.small,
                color = tokens.colors.onSurface,
                softWrap = true,
            )
        }
    }
}
