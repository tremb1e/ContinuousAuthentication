package com.continuousauth.ui.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Collapsed on first visit; headers alone toggle so controls inside remain independent. */
@Composable
fun ExpandableSection(
    sectionKey: String,
    title: String,
    summary: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    expandRequest: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(sectionKey) { mutableStateOf(false) }
    var handledExpandRequest by rememberSaveable(sectionKey) { mutableStateOf(0) }
    val contentState = rememberSaveableStateHolder()
    LaunchedEffect(expandRequest) {
        if (expandRequest > handledExpandRequest) {
            expanded = true
            handledExpandRequest = expandRequest
        }
    }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "sectionArrow")

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .testTag("section_$sectionKey")
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起$title" else "展开$title"
                ) { expanded = !expanded }
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = rotation })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(100))
        ) {
            contentState.SaveableStateProvider(sectionKey) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("section_content_$sectionKey").padding(horizontal = 20.dp).padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    content()
                }
            }
        }
    }
}
