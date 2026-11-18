package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.CreatedBridgeCredentials
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberOnAccent
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BgSurface
import com.example.ui.theme.BorderStrong
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.StatusOk
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun BridgeTokenDialog(
    credentials: CreatedBridgeCredentials,
    onCopyToken: (String) -> Unit,
    onRegenerateToken: (String) -> Unit,
    onConnectMuseAi: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
        onCopyToken(value)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BgSurface,
            border = BorderStroke(1.dp, BorderStrong),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bridge_token_dialog")
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = StatusOk,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Bridge Bot Connected",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "In-app server registration completed",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusOk
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_bridge_token_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close dialog",
                            tint = TextSecondary
                        )
                    }
                }

                HorizontalDivider(color = BorderSubtle)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BgElevated,
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Hub,
                                contentDescription = null,
                                tint = AmberAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${credentials.botName} (${credentials.botId})",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary
                            )
                        }

                        Text(
                            text = "In-App Server Endpoint",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        SelectionContainer {
                            Text(
                                text = credentials.serverUrl,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = TextPrimary,
                                modifier = Modifier.testTag("bridge_token_server_url")
                            )
                        }

                        Text(
                            text = "Muse AI Bridge Token",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BgSurface,
                            border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = credentials.token,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = AmberAccent,
                                        modifier = Modifier.testTag("bridge_generated_token_text")
                                    )
                                }
                                IconButton(
                                    onClick = { copyToClipboard("Muse Bridge Token", credentials.token) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("copy_bridge_token_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Copy token",
                                        tint = AmberAccent
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Next step: Tap \"Connect to Muse AI\" to sign into ${credentials.targetUrl} once. After login, all broadcasts sent in Muse Boardroom automatically route through this Bridge token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Button(
                    onClick = { onConnectMuseAi(credentials.agentId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberAccent,
                        contentColor = AmberOnAccent
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("connect_muse_ai_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.connect_muse_ai),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onRegenerateToken(credentials.agentId) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("regenerate_bridge_token_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Token")
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("bridge_token_done_button")
                    ) {
                        Text("Done", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
