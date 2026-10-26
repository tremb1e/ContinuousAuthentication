package com.continuousauth.ui.compose.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.continuousauth.ui.compose.components.PrivacyNoticeContent

/** 首次启动时显示，用户可先浏览，开始采集前须明确同意。 */
@Composable
fun PrivacyAgreementDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        PrivacyAgreementContent(
            onAccept = onAccept,
            onDecline = onDecline,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)
        )
    }
}

/** The whole notice scrolls, while both decisions retain their space on short screens. */
@Composable
internal fun PrivacyAgreementContent(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stackActions = LocalDensity.current.fontScale > 1.3f
    Card(
        modifier = modifier.testTag("privacy_agreement"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).testTag("privacy_notice_scroll"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PrivacyTip,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "知情同意",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    "开始前，请了解我们会收集什么。你可以先浏览，稍后再决定。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PrivacyNoticeContent()
            }
            Spacer(Modifier.height(16.dp))
            if (stackActions) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeclineButton(onDecline, Modifier.fillMaxWidth())
                    AcceptButton(onAccept, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeclineButton(onDecline, Modifier.weight(1f))
                    AcceptButton(onAccept, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DeclineButton(onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag("privacy_decline")) {
        Text("暂不启用", textAlign = TextAlign.Center)
    }
}

@Composable
private fun AcceptButton(onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag("privacy_accept")) {
        Text("同意并继续", textAlign = TextAlign.Center)
    }
}
