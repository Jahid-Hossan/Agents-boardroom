package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.MuseConnectionStatus
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BgSurface
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.StatusErr
import com.example.ui.theme.StatusOk
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MuseConnectionStatusCard(
    status: MuseConnectionStatus,
    storedTokenCount: Int = 0,
    compact: Boolean = false,
    onRefreshConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor by animateColorAsState(
        targetValue = if (status.isConnected) StatusOk else StatusErr,
        label = "muse_connection_accent"
    )
    val statusLabel = if (status.isConnected) {
        stringResource(R.string.muse_status_connected)
    } else {
        stringResource(R.string.muse_status_disconnected)
    }

    Surface(
        shape = RoundedCornerShape(if (compact) 10.dp else 14.dp),
        color = if (compact) BgElevated else BgSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (status.isConnected) StatusOk.copy(alpha = 0.45f) else BorderSubtle
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("muse_connection_status_card")
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (compact) 32.dp else 38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accentColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (status.isConnected) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                            contentDescription = "Muse AI connection icon",
                            tint = accentColor,
                            modifier = Modifier.size(if (compact) 18.dp else 20.dp)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.muse_connection_title),
                            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                                    .testTag("muse_connection_indicator_dot")
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
                                modifier = Modifier
                                    .semantics {
                                        contentDescription = "Muse AI status: $statusLabel"
                                    }
                                    .testTag("muse_connection_status_label")
                            )
                        }
                    }
                }

                if (compact) {
                    IconButton(
                        onClick = onRefreshConnection,
                        enabled = !status.isRefreshing,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("muse_connection_refresh_button")
                    ) {
                        if (status.isRefreshing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = AmberAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.refresh_muse_connection),
                                tint = AmberAccent
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onRefreshConnection,
                        enabled = !status.isRefreshing,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("muse_connection_refresh_button")
                    ) {
                        if (status.isRefreshing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = AmberAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.refreshing_connection),
                                style = MaterialTheme.typography.labelMedium
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.refresh_connection),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            Text(
                text = status.statusDetail.ifBlank {
                    if (status.isConnected) "Active Muse AI bridge & Socket.IO channel"
                    else "Tap Refresh after signing in or starting the Bridge"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("muse_connection_detail_text")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (status.socketConnected) StatusOk.copy(alpha = 0.14f) else BgElevated,
                    border = BorderStroke(
                        1.dp,
                        if (status.socketConnected) StatusOk.copy(alpha = 0.4f) else BorderSubtle
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sensors,
                            contentDescription = null,
                            tint = if (status.socketConnected) StatusOk else TextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (status.socketConnected) {
                                stringResource(R.string.socket_io_live)
                            } else {
                                stringResource(R.string.socket_io_offline)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (status.socketConnected) StatusOk else TextMuted
                        )
                    }
                }

                if (storedTokenCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = AmberAccent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = AmberAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "$storedTokenCount Encrypted Token${if (storedTokenCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberAccent
                            )
                        }
                    }
                }
            }
        }
    }
}
