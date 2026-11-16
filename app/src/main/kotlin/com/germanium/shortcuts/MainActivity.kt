package com.germanium.shortcuts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveTag(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent) {
        val runId = intent.getStringExtra("run_shortcut_id") ?: return
        val app = application as ShortcutsApplication
        lifecycleScope.launch {
            val shortcut = app.shortcuts.list().find { it.id == runId }
            if (shortcut != null) {
                val model = androidx.lifecycle.ViewModelProvider(this@MainActivity)[ShortcutsViewModel::class.java]
                model.run(shortcut)
            }
        }
    }

    private fun receiveTag(intent: Intent) {
        if (intent.action !in listOf(android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED, android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED)) return
        @Suppress("DEPRECATION") val tag = intent.getParcelableExtra<android.nfc.Tag>(android.nfc.NfcAdapter.EXTRA_TAG) ?: return
        val app = application as ShortcutsApplication
        lifecycleScope.launch {
            try {
                if (!app.getSharedPreferences("permissions", MODE_PRIVATE).getBoolean("monitorPaused", false) &&
                    app.automations.list().any { it.enabled && it.trigger == TriggerKind.NFC }) {
                    startAutomationMonitor(app, nfcTag = tag.hexId())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                app.diagnostics.trace("nfc_trigger_failed", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as ShortcutsApplication
        try {
            android.nfc.NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
                this,
                { tag -> receiveTag(Intent(android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED).putExtra(android.nfc.NfcAdapter.EXTRA_TAG, tag)) },
                android.nfc.NfcAdapter.FLAG_READER_NFC_A or android.nfc.NfcAdapter.FLAG_READER_NFC_B or android.nfc.NfcAdapter.FLAG_READER_NFC_F or android.nfc.NfcAdapter.FLAG_READER_NFC_V or android.nfc.NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null
            )
        } catch (e: Exception) {
            app.diagnostics.trace("nfc_reader_unavailable", e)
        }

        lifecycleScope.launch {
            try {
                if (!app.getSharedPreferences("permissions", MODE_PRIVATE).getBoolean("monitorPaused", false) &&
                    app.automations.list().any { it.enabled }) {
                    startAutomationMonitor(app)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                app.diagnostics.trace("automation_resume_failed", e)
            }
        }
    }

    override fun onPause() {
        runCatching { android.nfc.NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) }
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableShortcutsEdgeToEdge()
        if (savedInstanceState == null) {
            receiveTag(intent)
            handleLaunchIntent(intent)
        }
        setContent {
            val model: ShortcutsViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            ShortcutsTheme(state.settings.theme) {
                ObserveLifecycle(model)
                HomeScreen(this, model, state)
                InteractionHost(application as ShortcutsApplication)
            }
            LaunchedEffect(Unit) {
                StartupPermissions.start(application as ShortcutsApplication)
            }
        }
    }
}

@Composable private fun HomeScreen(activity: MainActivity, model: ShortcutsViewModel, state: HomeState) {
    val adaptive = rememberAdaptiveLayout()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val shortcutScroll = rememberLazyListState()
    val automationScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    val tilesScroll = rememberLazyListState()
    val vibecodeScroll = rememberLazyListState()

    var deleting by remember { mutableStateOf<Shortcut?>(null) }
    var logs by remember { mutableStateOf<String?>(null) }
    var tileShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var moveShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var addingFolder by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val searchText = rememberTextFieldState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val searchResults = remember(state.items, searchText.text.toString()) {
        state.items.filter { it.name.contains(searchText.text.toString().trim(), ignoreCase = true) }
    }

    fun closeSearch() {
        keyboard?.hide()
        searching = false
        searchText.setTextAndPlaceCursorAtEnd("")
    }

    var editingFolder by remember { mutableStateOf<ShortcutFolder?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val transfers = rememberTransfers(model)
    val app = activity.application as ShortcutsApplication
    val scope = rememberCoroutineScope()

    fun launchRequest(request: Interaction) {
        scope.launch {
            try {
                app.interactions.ask(request)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                model.report(e)
            }
        }
    }

    BackHandler(tab != 0) { tab = 0 }
    BackHandler(searching) { closeSearch() }

    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet {
                    Column(
                        Modifier.fillMaxHeight().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(t("folders"), Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                        NavigationDrawerItem(
                            label = { Text("All Applets") },
                            selected = selectedFolder == null,
                            icon = { AppIcon(R.drawable.ic_glyph_layers) },
                            onClick = {
                                selectedFolder = null
                                tab = 0
                                scope.launch { drawer.close() }
                            }
                        )
                        LazyColumn(Modifier.weight(1f)) {
                            items(state.folders, key = { it.id }) { folder ->
                                NavigationDrawerItem(
                                    label = { Text(folder.name) },
                                    selected = selectedFolder == folder.id,
                                    icon = { ShortcutBadge(folder.icon, folder.color, 36.dp) },
                                    badge = { ToolIcon(R.drawable.ic_settings, "edit") { editingFolder = folder } },
                                    onClick = {
                                        selectedFolder = folder.id
                                        tab = 0
                                        scope.launch { drawer.close() }
                                    }
                                )
                            }
                        }
                        FilledTonalButton(onClick = { addingFolder = true }, modifier = Modifier.fillMaxWidth()) {
                            AppIcon(R.drawable.ic_add)
                            Spacer(Modifier.width(8.dp))
                            Text(t("new_folder"))
                        }
                    }
                }
            }
        ) {
            Row(Modifier.fillMaxSize()) {
                if (adaptive.policy.navigationRail) FloatingNavigationRail(tab) { tab = it }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    contentWindowInsets = shortcutsContentInsets(),
                    topBar = {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                                    .padding(horizontal = 16.dp)
                                    .heightIn(min = 56.dp)
                                    .testTag("home_top_bar"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (tab == 0 && searching) {
                                    ShortcutSearchField(searchText, ::closeSearch, Modifier.weight(1f))
                                } else {
                                    if (tab == 0) {
                                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                                            AppIcon(R.drawable.ic_menu, description = t("folders"))
                                        }
                                    }

                                    val topTitle = when (tab) {
                                        0 -> state.folders.find { it.id == selectedFolder }?.name ?: "Apprompt"
                                        1 -> "Vibecoding Studio"
                                        2 -> "Quick Settings Tiles"
                                        3 -> "Automations"
                                        else -> "Settings"
                                    }

                                    Text(
                                        topTitle,
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )

                                    if (tab == 0) {
                                        ToolIcon(R.drawable.ic_search, "search", state.storageReady, modifier = Modifier.testTag("home_search")) {
                                            searching = true
                                        }
                                        ToolIcon(R.drawable.ic_glyph_folder, "new_folder", state.storageReady && !state.saving, modifier = Modifier.testTag("home_new_folder")) {
                                            addingFolder = true
                                        }
                                        ToolIcon(R.drawable.ic_import, "import", state.storageReady && !state.saving, modifier = Modifier.testTag("home_import"), click = transfers.importXml)
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (!adaptive.policy.navigationRail) FloatingNavigation(tab) { tab = it }
                    },
                    floatingActionButton = {
                        if (tab == 0 && state.storageReady && !state.saving && state.runningId == null) {
                            ExtendedFloatingActionButton(
                                onClick = { tab = 1 },
                                shape = RoundedCornerShape(20.dp),
                                icon = { AppIcon(R.drawable.ic_glyph_bolt) },
                                text = { Text("Vibecode Applet", fontWeight = FontWeight.Bold) }
                            )
                        }
                    }
                ) { padding ->
                    LazyColumn(
                        Modifier.fillMaxSize().padding(padding).testTag("home_content"),
                        state = when (tab) {
                            1 -> vibecodeScroll
                            2 -> tilesScroll
                            3 -> automationScroll
                            4 -> settingsScroll
                            else -> shortcutScroll
                        },
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (state.error != null) item { ErrorCard(state.error, if (state.storageReady) model::clearError else model::reload) }
                        if (state.notice != null) item { Text(state.notice, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
                        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

                        when (tab) {
                            0 -> {
                                if (searching) {
                                    if (searchResults.isEmpty()) {
                                        item {
                                            Text(
                                                t("search_no_results"),
                                                Modifier.padding(16.dp).testTag("shortcut_search_empty"),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    items(searchResults, key = { "search_${it.id}" }) { shortcut ->
                                        ShortcutSearchResult(
                                            shortcut,
                                            !state.saving && state.runningId == null,
                                            { closeSearch(); openEditor(activity, shortcut.id) },
                                            { closeSearch(); model.run(shortcut) }
                                        )
                                    }
                                } else {
                                    if (selectedFolder == null) {
                                        items(state.folders, key = { "folder_${it.id}" }) { folder ->
                                            Card(
                                                onClick = { selectedFolder = folder.id },
                                                shape = RoundedCornerShape(20.dp)
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().padding(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    ShortcutBadge(folder.icon, folder.color, 44.dp)
                                                    Text(folder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }

                                    if (state.items.isEmpty() && state.storageReady) {
                                        item {
                                            Card(
                                                shape = RoundedCornerShape(28.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                                            ) {
                                                Column(
                                                    Modifier.padding(24.dp),
                                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                        Surface(
                                                            shape = RoundedCornerShape(12.dp),
                                                            color = MaterialTheme.colorScheme.primaryContainer,
                                                            modifier = Modifier.size(40.dp)
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                AppIcon(R.drawable.ic_glyph_bolt)
                                                            }
                                                        }
                                                        Text("Create your first Applet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                                    }
                                                    Text("Vibecode small apps using simple AI prompts with Gemini. Run them directly or pin them as Quick Settings tiles and launcher icons.")
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Button(onClick = { tab = 1 }) {
                                                            AppIcon(R.drawable.ic_glyph_bolt)
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("Vibecode with AI")
                                                        }
                                                        OutlinedButton(onClick = { openEditor(activity) }) {
                                                            Text("Manual Editor")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    items(state.items.filter { selectedFolder == null || it.folderId == selectedFolder }, key = { it.id }) { shortcut ->
                                        Card(
                                            onClick = { openEditor(activity, shortcut.id) },
                                            enabled = !state.saving && state.runningId == null,
                                            shape = RoundedCornerShape(26.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                                        ) {
                                            Column(
                                                Modifier.fillMaxWidth().padding(20.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    ShortcutBadge(shortcut.icon, shortcut.color, 52.dp)
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(shortcut.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                        Text("${allSteps(shortcut.steps).size} actions chained", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }

                                                    IconButton(
                                                        onClick = {
                                                            if (state.runningId == shortcut.id) model.cancelRun() else model.run(shortcut)
                                                        },
                                                        colors = IconButtonDefaults.filledIconButtonColors(
                                                            containerColor = if (state.runningId == shortcut.id) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                                        ),
                                                        modifier = Modifier.size(44.dp).testTag("home_run_${shortcut.id}")
                                                    ) {
                                                        AppIcon(if (state.runningId == shortcut.id) R.drawable.ic_stop else R.drawable.ic_play, description = "Run")
                                                    }
                                                }

                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                                Row(
                                                    Modifier.horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Quick Tile Button
                                                    FilledTonalButton(
                                                        onClick = { tileShortcut = shortcut },
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                    ) {
                                                        AppIcon(R.drawable.ic_controls)
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("Quick Tile", style = MaterialTheme.typography.labelMedium)
                                                    }

                                                    // Pin to Launcher Button
                                                    if (AppletLauncher.isPinningSupported(activity)) {
                                                        OutlinedButton(
                                                            onClick = {
                                                                AppletLauncher.pinToHomeScreen(activity, shortcut) { _, msg ->
                                                                    model.notice(msg)
                                                                }
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                        ) {
                                                            AppIcon(R.drawable.ic_glyph_home)
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("Pin to Home", style = MaterialTheme.typography.labelMedium)
                                                        }
                                                    }

                                                    ToolIcon(R.drawable.ic_settings, "edit") { openEditor(activity, shortcut.id) }
                                                    ToolIcon(R.drawable.ic_export, "export") { transfers.export(shortcut) }
                                                    ToolIcon(R.drawable.ic_glyph_folder, "move_to_folder") { moveShortcut = shortcut }
                                                    ToolIcon(R.drawable.ic_copy, "duplicate", state.runningId == null && !state.saving, modifier = Modifier.testTag("copy_shortcut_${shortcut.id}")) { model.duplicate(shortcut) }
                                                    ToolIcon(R.drawable.ic_close, "delete", state.runningId == null && !state.saving, danger = true) { deleting = shortcut }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (state.runningId != null) {
                                    item {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(14.dp)
                                            ) {
                                                CircularProgressIndicator(Modifier.size(24.dp))
                                                Text(t("running"), Modifier.weight(1f).padding(horizontal = 12.dp), fontWeight = FontWeight.Medium)
                                                TextButton(onClick = model::cancelRun) { Text(t("stop"), color = MaterialTheme.colorScheme.error) }
                                            }
                                        }
                                    }
                                }

                                if (state.runningId != null && (state.shellStdout.isNotEmpty() || state.shellStderr.isNotEmpty())) {
                                    item { LiveShell(state.shellStdout, state.shellStderr) }
                                }
                                state.result?.takeUnless { state.homeResultDismissed }?.let { result ->
                                    item { HomeResult(result, model::dismissHomeResult) }
                                }
                            }

                            1 -> item {
                                VibecodeStudio(
                                    onSaveApplet = { newApplet ->
                                        model.save(newApplet)
                                        tab = 0
                                    },
                                    onTestRun = { testApplet ->
                                        model.run(testApplet)
                                    },
                                    onNotice = model::notice,
                                    onReport = model::report
                                )
                            }

                            2 -> item {
                                QuickTilesPanel(
                                    shortcuts = state.items,
                                    onRunShortcut = { model.run(it) },
                                    onNotice = model::notice,
                                    onReport = model::report
                                )
                            }

                            3 -> item {
                                AutomationPanel(app, state.items, model::report)
                            }

                            4 -> item {
                                SettingsPanel(app, model, state, transfers, ::launchRequest) {
                                    scope.launch {
                                        logs = withContext(Dispatchers.IO) { app.diagnostics.read() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        BackHandler(drawer.currentValue != DrawerValue.Closed || drawer.targetValue != DrawerValue.Closed) {
            scope.launch { drawer.close() }
        }

        if (addingFolder || editingFolder != null) {
            FolderEditor(editingFolder, { addingFolder = false; editingFolder = null }, model::saveFolder)
        }

        moveShortcut?.let { shortcut ->
            MoveToFolderSheet(shortcut, state.folders, { moveShortcut = null }) { model.moveToFolder(shortcut, it) }
        }

        deleting?.let { shortcut ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(t("delete_title")) },
                text = { Text(t("delete_body")) },
                confirmButton = {
                    TextButton(onClick = { model.delete(shortcut.id); deleting = null }) { Text(t("delete")) }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text(t("cancel")) }
                }
            )
        }

        tileShortcut?.let { shortcut ->
            TileSlotPicker(app, shortcut, { tileShortcut = null }) { slot ->
                tileShortcut = null
                ShortcutTiles.add(activity, shortcut, slot, model::notice, model::report)
            }
        }

        logs?.let { content ->
            AlertDialog(
                onDismissRequest = { logs = null },
                title = { Text(t("logs")) },
                text = {
                    SelectionContainer {
                        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                            Text(content.ifEmpty { t("logs_empty") }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { logs = null }) { Text(t("close")) }
                }
            )
        }
    }
}
