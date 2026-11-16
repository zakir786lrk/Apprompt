package com.germanium.shortcuts

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.Shortcut
import com.germanium.shortcuts.core.allSteps
import kotlinx.coroutines.launch

@Composable
internal fun VibecodeStudio(
    onSaveApplet: (Shortcut) -> Unit,
    onTestRun: (Shortcut) -> Unit,
    onNotice: (String) -> Unit,
    onReport: (Throwable) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val geminiService = remember { GeminiService(context) }

    var apiKey by remember { mutableStateOf(geminiService.apiKey) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(geminiService.selectedModel) }
    var availableModels by remember { mutableStateOf<List<GeminiModel>>(GeminiService.fallbackModels()) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    var promptText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationError by remember { mutableStateOf<String?>(null) }
    var generatedShortcut by remember { mutableStateOf<Shortcut?>(null) }

    // Load models on first appearance if API key is present
    LaunchedEffect(Unit) {
        if (geminiService.apiKey.isNotBlank()) {
            isFetchingModels = true
            geminiService.fetchAvailableModels()
                .onSuccess { models ->
                    availableModels = models
                    if (models.none { it.id == selectedModel }) {
                        selectedModel = models.firstOrNull()?.id ?: "gemini-2.5-flash"
                        geminiService.selectedModel = selectedModel
                    }
                }
            isFetchingModels = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero / Model Status Header Card
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AppIcon(R.drawable.ic_glyph_bolt)
                            }
                        }
                        Column {
                            Text(
                                "AI Vibecoding Studio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (apiKey.isBlank()) "API Key required" else "Ready to compile prompts",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (apiKey.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    OutlinedButton(onClick = { showApiKeyDialog = true }) {
                        Text(if (apiKey.isBlank()) "Set API Key" else "Change Key")
                    }
                }

                HorizontalDivider()

                // Model Selector Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Selected Gemini Model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box {
                            FilterChip(
                                selected = true,
                                onClick = { modelMenuExpanded = true },
                                label = {
                                    val current = availableModels.find { it.id == selectedModel }
                                    Text(current?.displayName ?: selectedModel)
                                },
                                trailingIcon = {
                                    AppIcon(R.drawable.ic_down)
                                }
                            )

                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false }
                            ) {
                                availableModels.forEach { m ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(m.displayName, fontWeight = FontWeight.SemiBold)
                                                if (m.description.isNotBlank()) {
                                                    Text(
                                                        m.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedModel = m.id
                                            geminiService.selectedModel = m.id
                                            modelMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                isFetchingModels = true
                                geminiService.fetchAvailableModels()
                                    .onSuccess { models ->
                                        availableModels = models
                                        onNotice("Loaded ${models.size} Gemini models")
                                    }
                                    .onFailure { err ->
                                        onReport(err)
                                    }
                                isFetchingModels = false
                            }
                        },
                        enabled = !isFetchingModels && apiKey.isNotBlank()
                    ) {
                        if (isFetchingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            AppIcon(R.drawable.ic_redo, description = "Refresh Models")
                        }
                    }
                }
            }
        }

        // Prompt Input Card
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Describe your Applet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = {
                        Text("e.g., Create an applet that reads clipboard text, counts words and lines, and speaks the summary aloud...")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    shape = RoundedCornerShape(18.dp)
                )

                // Quick Inspiration Chips
                Text(
                    "Try an inspiration prompt:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val suggestions = listOf(
                    "Speak clipboard contents using voice TTS",
                    "Take photo and extract text with OCR",
                    "Ask for price & tip percentage, then calculate total",
                    "Show battery percentage and vibrate device",
                    "Generate a QR code from text input and preview it"
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = { promptText = suggestion },
                            label = { Text(suggestion, maxLines = 1) }
                        )
                    }
                }

                if (generationError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            generationError ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Button(
                    onClick = {
                        if (apiKey.isBlank()) {
                            showApiKeyDialog = true
                            return@Button
                        }
                        if (promptText.isBlank()) {
                            generationError = "Please describe what your applet should do."
                            return@Button
                        }

                        scope.launch {
                            isGenerating = true
                            generationError = null
                            geminiService.generateApplet(promptText)
                                .onSuccess { applet ->
                                    generatedShortcut = applet
                                    onNotice("Compiled '${applet.name}' successfully!")
                                }
                                .onFailure { err ->
                                    generationError = err.message ?: "Failed to generate applet"
                                    onReport(err)
                                }
                            isGenerating = false
                        }
                    },
                    enabled = !isGenerating,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Vibecoding with Gemini…")
                    } else {
                        AppIcon(R.drawable.ic_glyph_bolt)
                        Spacer(Modifier.width(8.dp))
                        Text("Vibecode Applet", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Generated Applet Preview Card
        AnimatedVisibility(
            visible = generatedShortcut != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            generatedShortcut?.let { applet ->
                Card(
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            ShortcutBadge(applet.icon, applet.color, 54.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Compiled Applet",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    applet.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "${allSteps(applet.steps).size} actions chained",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        // Steps Preview Chips
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            allSteps(applet.steps).take(6).forEach { step ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                ) {
                                    Text(
                                        step.type.replace('_', ' '),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalButton(
                                onClick = { onTestRun(applet) },
                                modifier = Modifier.weight(1f)
                            ) {
                                AppIcon(R.drawable.ic_play)
                                Spacer(Modifier.width(6.dp))
                                Text("Test Run")
                            }

                            Button(
                                onClick = {
                                    onSaveApplet(applet)
                                    onNotice("Saved '${applet.name}' to My Applets!")
                                },
                                modifier = Modifier.weight(1.2f)
                            ) {
                                AppIcon(R.drawable.ic_glyph_check)
                                Spacer(Modifier.width(6.dp))
                                Text("Save Applet")
                            }
                        }

                        if (activity != null && AppletLauncher.isPinningSupported(activity)) {
                            OutlinedButton(
                                onClick = {
                                    AppletLauncher.pinToHomeScreen(activity, applet) { success, msg ->
                                        onNotice(msg)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AppIcon(R.drawable.ic_glyph_home)
                                Spacer(Modifier.width(8.dp))
                                Text("Pin to Home Screen")
                            }
                        }
                    }
                }
            }
        }
    }

    // API Key Dialog
    if (showApiKeyDialog) {
        var keyInput by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("Gemini API Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter your Google Gemini API key to enable prompt-to-applet vibecoding and fetch all available models.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        apiKey = keyInput.trim()
                        geminiService.apiKey = keyInput.trim()
                        showApiKeyDialog = false
                        onNotice("Gemini API key saved")
                        // Trigger models refresh
                        scope.launch {
                            isFetchingModels = true
                            geminiService.fetchAvailableModels()
                                .onSuccess { availableModels = it }
                            isFetchingModels = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
