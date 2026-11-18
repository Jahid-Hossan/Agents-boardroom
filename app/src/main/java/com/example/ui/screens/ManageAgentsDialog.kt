package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.BoardroomSettings
import com.example.data.model.DEFAULT_AI_PRESETS
import com.example.ui.components.AgentStatusBadgeChip
import com.example.ui.components.AgentStatusDot
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberOnAccent
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BgSurface
import com.example.ui.theme.BorderStrong
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.StatusErr
import com.example.ui.theme.StatusOk
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManageAgentsDialog(
    agents: List<AgentConfig>,
    agentStatuses: Map<String, AgentAvailabilityStatus>,
    selectedAgentId: String?,
    settings: BoardroomSettings,
    webhookTestResult: String?,
    isTestingWebhook: Boolean,
    onSelectAgent: (String) -> Unit,
    onAddAgent: () -> Unit,
    onDuplicateAgent: (AgentConfig) -> Unit,
    onSaveAgent: (AgentConfig, Boolean) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onSaveCollaboration: (Boolean, Int) -> Unit,
    onOpenBrowserForAgent: (String) -> Unit,
    onTestWebhook: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val activeAgent = agents.firstOrNull { it.id == selectedAgentId } ?: agents.firstOrNull()

    var name by remember(activeAgent?.id) { mutableStateOf(activeAgent?.name.orEmpty()) }
    var role by remember(activeAgent?.id) { mutableStateOf(activeAgent?.role.orEmpty()) }
    var avatar by remember(activeAgent?.id) { mutableStateOf(activeAgent?.avatar.orEmpty()) }
    var colorHex by remember(activeAgent?.id) { mutableStateOf(activeAgent?.colorHex ?: "#3B82F6") }
    var provider by remember(activeAgent?.id) { mutableStateOf(activeAgent?.provider ?: AgentProvider.INAPP_BROWSER) }
    var customUrl by remember(activeAgent?.id) { mutableStateOf(activeAgent?.customUrl ?: "https://muse.ai") }
    var model by remember(activeAgent?.id) { mutableStateOf(activeAgent?.model.orEmpty()) }
    var apiKey by remember(activeAgent?.id) { mutableStateOf(activeAgent?.apiKey.orEmpty()) }
    var endpoint by remember(activeAgent?.id) { mutableStateOf(activeAgent?.endpoint.orEmpty()) }
    var systemPrompt by remember(activeAgent?.id) { mutableStateOf(activeAgent?.systemPrompt.orEmpty()) }
    var isSupervisor by remember(activeAgent?.id, settings.supervisorAgentId) {
        mutableStateOf(
            activeAgent != null &&
                (activeAgent.isSupervisor || settings.supervisorAgentId == activeAgent.id)
        )
    }

    var collabEnabled by remember(settings.collaborationEnabled) {
        mutableStateOf(settings.collaborationEnabled)
    }
    var maxHops by remember(settings.maxCollaborationHops) {
        mutableIntStateOf(settings.maxCollaborationHops.coerceIn(1, 5))
    }
    var showHopsMenu by remember { mutableStateOf(false) }

    LaunchedEffect(activeAgent?.id) {
        if (activeAgent != null) {
            name = activeAgent.name
            role = activeAgent.role
            avatar = activeAgent.avatar
            colorHex = activeAgent.colorHex
            provider = activeAgent.provider
            customUrl = activeAgent.customUrl
            model = activeAgent.model
            apiKey = activeAgent.apiKey
            endpoint = activeAgent.endpoint
            systemPrompt = activeAgent.systemPrompt
            isSupervisor = activeAgent.isSupervisor || settings.supervisorAgentId == activeAgent.id
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = BgSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderStrong),
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .testTag("manage_agents_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.manage_agents),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        if (activeAgent != null) {
                            AgentStatusBadgeChip(
                                status = agentStatuses[activeAgent.id] ?: AgentAvailabilityStatus.OFFLINE
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_manage_agents_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close Manage Agents",
                            tint = TextSecondary
                        )
                    }
                }
                HorizontalDivider(color = BorderSubtle)

                // Agent selector tabs + Add
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    agents.forEach { agent ->
                        val selected = agent.id == activeAgent?.id
                        val status = agentStatuses[agent.id] ?: AgentAvailabilityStatus.OFFLINE
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectAgent(agent.id) },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(agent.name)
                                    AgentStatusDot(status = status, dotSize = 6.dp)
                                }
                            },
                            leadingIcon = if (agent.isSupervisor || settings.supervisorAgentId == agent.id) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = AmberAccent
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberAccent.copy(alpha = 0.2f),
                                selectedLabelColor = AmberAccent
                            )
                        )
                    }
                    OutlinedButton(
                        onClick = onAddAgent,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("manage_add_agent_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }
                HorizontalDivider(color = BorderSubtle)

                if (activeAgent != null) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Name & Role
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BoardroomInputField(
                                label = "Agent Name",
                                value = name,
                                onValueChange = { name = it },
                                modifier = Modifier.weight(1f)
                            )
                            BoardroomInputField(
                                label = "Role / Title",
                                value = role,
                                onValueChange = { role = it },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Collaboration & CEO/Supervisor controls
                        Surface(
                            shape = RoundedCornerShape(10.dp),
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isSupervisor = !isSupervisor }
                                ) {
                                    Checkbox(
                                        checked = isSupervisor,
                                        onCheckedChange = { isSupervisor = it },
                                        colors = CheckboxDefaults.colors(checkedColor = AmberAccent)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "CEO / Supervisor Agent",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "Reviews commands first, delegates @tasks, and writes the final synthesis.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }

                                HorizontalDivider(color = BorderSubtle)

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                collabEnabled = !collabEnabled
                                                onSaveCollaboration(collabEnabled, maxHops)
                                            }
                                    ) {
                                        Checkbox(
                                            checked = collabEnabled,
                                            onCheckedChange = {
                                                collabEnabled = it
                                                onSaveCollaboration(it, maxHops)
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = AmberAccent)
                                        )
                                        Text(
                                            text = "Allow @AgentName handoffs",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextPrimary
                                        )
                                    }

                                    Box {
                                        OutlinedButton(
                                            onClick = { showHopsMenu = true },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Max Rounds: $maxHops", color = AmberAccent)
                                        }
                                        DropdownMenu(
                                            expanded = showHopsMenu,
                                            onDismissRequest = { showHopsMenu = false }
                                        ) {
                                            (1..5).forEach { rounds ->
                                                DropdownMenuItem(
                                                    text = { Text("$rounds ${if (rounds == 1) "round" else "rounds"}") },
                                                    onClick = {
                                                        showHopsMenu = false
                                                        maxHops = rounds
                                                        onSaveCollaboration(collabEnabled, rounds)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Connection Provider Selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Connection Type",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary
                            )
                            OutlinedButton(
                                onClick = { onDuplicateAgent(activeAgent) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Duplicate")
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AgentProvider.entries.forEach { p ->
                                FilterChip(
                                    selected = provider == p,
                                    onClick = { provider = p },
                                    label = { Text(p.displayLabel) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AmberAccent,
                                        selectedLabelColor = AmberOnAccent
                                    )
                                )
                            }
                        }

                        // Provider-Specific Fields
                        when (provider) {
                            AgentProvider.INAPP_BROWSER -> {
                                Text(
                                    text = "Quick AI Presets",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    DEFAULT_AI_PRESETS.forEach { preset ->
                                        OutlinedButton(
                                            onClick = {
                                                customUrl = preset.url
                                                if (role.isBlank()) role = preset.role
                                                colorHex = preset.colorHex
                                                avatar = preset.avatar
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(preset.label, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                BoardroomInputField(
                                    label = "Website URL",
                                    value = customUrl,
                                    onValueChange = { customUrl = it },
                                    placeholder = "https://muse.ai"
                                )
                                OutlinedButton(
                                    onClick = {
                                        onSaveAgent(
                                            activeAgent.copy(
                                                name = name.ifBlank { activeAgent.name },
                                                role = role,
                                                avatar = avatar,
                                                colorHex = colorHex,
                                                provider = provider,
                                                customUrl = customUrl,
                                                model = model,
                                                apiKey = apiKey,
                                                endpoint = endpoint,
                                                systemPrompt = systemPrompt,
                                                isSupervisor = isSupervisor
                                            ),
                                            isSupervisor
                                        )
                                        onOpenBrowserForAgent(activeAgent.id)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Language, contentDescription = null, tint = AmberAccent)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Save & Open in In-App Browser", color = AmberAccent)
                                }
                            }

                            AgentProvider.GEMINI -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = BgElevated,
                                    border = BorderStroke(1.dp, BorderSubtle),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Powered by built-in Firebase AI Logic (no API key needed). You may optionally supply a personal Gemini API key below.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-argon").forEach { m ->
                                        FilterChip(
                                            selected = model == m || (model.isBlank() && m == "gemini-3.8-flash"),
                                            onClick = { model = m },
                                            label = { Text(m, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                                BoardroomInputField(
                                    label = "Model ID",
                                    value = model.ifBlank { "gemini-3.8-flash" },
                                    onValueChange = { model = it }
                                )
                                BoardroomInputField(
                                    label = "Optional Custom Gemini API Key",
                                    value = apiKey,
                                    onValueChange = { apiKey = it },
                                    placeholder = "Leave blank to use built-in Firebase AI Logic",
                                    isPassword = true
                                )
                            }

                            AgentProvider.OPENAI -> {
                                BoardroomInputField(
                                    label = "API Key (OpenAI / Groq / Compatible)",
                                    value = apiKey,
                                    onValueChange = { apiKey = it },
                                    placeholder = "sk-...",
                                    isPassword = true
                                )
                                BoardroomInputField(
                                    label = "Model",
                                    value = model.ifBlank { "gpt-4o-mini" },
                                    onValueChange = { model = it },
                                    placeholder = "gpt-4o-mini"
                                )
                                BoardroomInputField(
                                    label = "Chat Completions Endpoint URL",
                                    value = endpoint.ifBlank { "https://api.openai.com/v1/chat/completions" },
                                    onValueChange = { endpoint = it }
                                )
                            }

                            AgentProvider.DOCKER_WEB -> {
                                BoardroomInputField(
                                    label = "Public Webhook / Server URL",
                                    value = customUrl,
                                    onValueChange = { customUrl = it },
                                    placeholder = "https://your-agent.example.com"
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onTestWebhook(customUrl) },
                                        enabled = !isTestingWebhook
                                    ) {
                                        Text(if (isTestingWebhook) "Testing..." else "Test Connection")
                                    }
                                    if (!webhookTestResult.isNullOrBlank()) {
                                        Text(
                                            text = webhookTestResult,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (webhookTestResult.startsWith("Connected")) StatusOk else StatusErr,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            AgentProvider.SIMULATION -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = BgElevated,
                                    border = BorderStroke(1.dp, BorderSubtle),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Offline Demo replies locally without network calls so you can test boardroom workflows and @AgentName routing anytime.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }

                        BoardroomInputField(
                            label = "System Instructions / Persona",
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            placeholder = "Optional instructions for how this agent responds...",
                            singleLine = false,
                            minLines = 3
                        )
                    }

                    HorizontalDivider(color = BorderSubtle)

                    // Bottom Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (agents.size > 1) {
                            OutlinedButton(
                                onClick = { onDeleteAgent(activeAgent.id) },
                                border = BorderStroke(1.dp, StatusErr.copy(alpha = 0.6f)),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = null,
                                    tint = StatusErr,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Remove", color = StatusErr)
                            }
                        } else {
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Button(
                            onClick = {
                                onSaveAgent(
                                    activeAgent.copy(
                                        name = name.trim().ifBlank { activeAgent.name },
                                        role = role.trim(),
                                        avatar = avatar.trim().ifBlank {
                                            name.trim().take(1).uppercase().ifBlank { "A" }
                                        },
                                        colorHex = colorHex,
                                        provider = provider,
                                        customUrl = customUrl.trim(),
                                        model = model.trim(),
                                        apiKey = apiKey.trim(),
                                        endpoint = endpoint.trim(),
                                        systemPrompt = systemPrompt.trim(),
                                        isSupervisor = isSupervisor
                                    ),
                                    isSupervisor
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmberAccent,
                                contentColor = AmberOnAccent
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("save_agent_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Agent", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardroomInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = TextMuted) }
            } else null,
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = BgCanvas,
                unfocusedContainerColor = BgCanvas,
                focusedBorderColor = AmberAccent,
                unfocusedBorderColor = BorderSubtle,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
