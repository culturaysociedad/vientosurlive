package com.radiosuite.pro.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Radio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.radiosuite.pro.dsp.DspParameters
import com.radiosuite.pro.model.BroadcastProfile
import com.radiosuite.pro.model.CodecType
import com.radiosuite.pro.service.PlaybackSnapshot
import com.radiosuite.pro.service.FavoriteTrack
import com.radiosuite.pro.service.MixLevels
import com.radiosuite.pro.service.RadioEngineService
import com.radiosuite.pro.service.SavedPlaylistSummary
import com.radiosuite.pro.service.StreamSnapshot
import com.radiosuite.pro.service.SwiperSnapshot
import com.radiosuite.pro.stream.BroadcastProfileStore
import com.radiosuite.pro.ui.theme.RadioSuiteProTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private data class MeterSnapshot(
    val inputDb: Float = -120f,
    val outputDb: Float = -120f,
    val rmsDb: Float = -120f,
    val agcGainDb: Float = 0f,
)

class MainActivity : ComponentActivity() {
    private val serviceState = mutableStateOf<RadioEngineService?>(null)
    private val meterState = mutableStateOf(MeterSnapshot())
    private val darkThemeState = mutableStateOf(true)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? RadioEngineService.RadioEngineBinder)?.service ?: return
            serviceState.value = service
            service.startMeterPolling { input, output, rms, gain ->
                runOnUiThread { meterState.value = MeterSnapshot(input, output, rms, gain) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceState.value = null
            meterState.value = MeterSnapshot()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkThemeState.value = getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .getBoolean(DARK_THEME_KEY, true)
        setContent {
            RadioSuiteProTheme(darkTheme = darkThemeState.value) {
                RadioSuiteConsole(
                    service = serviceState.value,
                    meters = meterState.value,
                    darkTheme = darkThemeState.value,
                    onToggleTheme = {
                        darkThemeState.value = !darkThemeState.value
                        getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(DARK_THEME_KEY, darkThemeState.value).apply()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isBound) {
            isBound = bindService(
                Intent(this, RadioEngineService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
        serviceState.value?.startMeterPolling { input, output, rms, gain ->
            runOnUiThread { meterState.value = MeterSnapshot(input, output, rms, gain) }
        }
    }

    override fun onStop() {
        serviceState.value?.stopMeterPolling()
        super.onStop()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        serviceState.value = null
        super.onDestroy()
    }

    private companion object {
        const val THEME_PREFS = "radio_ui"
        const val DARK_THEME_KEY = "dark_theme"
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RadioSuiteConsole(
    service: RadioEngineService?,
    meters: MeterSnapshot,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val context = LocalContext.current
    val profileStore = remember(context) { BroadcastProfileStore(context) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var playback by remember(service) { mutableStateOf(PlaybackSnapshot()) }
    var stream by remember(service) { mutableStateOf(StreamSnapshot()) }
    var mixLevels by remember(service) { mutableStateOf(MixLevels()) }
    var playlists by remember(service) { mutableStateOf(emptyList<SavedPlaylistSummary>()) }
    var favorites by remember(service) { mutableStateOf(emptyList<FavoriteTrack>()) }
    var swiperStatus by remember(service) { mutableStateOf(SwiperSnapshot()) }
    var pendingStudioProfile by remember { mutableStateOf<BroadcastProfile?>(null) }
    var studioActionMessage by remember { mutableStateOf("") }

    fun startProfileBroadcast(profile: BroadcastProfile) {
        startBroadcast(
            context,
            BroadcastConfig(profile.name, profile.host, profile.port, profile.mount, profile.password, profile.bitrate),
        )
        studioActionMessage = ""
    }

    val studioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val profile = pendingStudioProfile
        pendingStudioProfile = null
        if (profile != null && (
                results[Manifest.permission.RECORD_AUDIO] == true ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                )
        ) {
            startProfileBroadcast(profile)
        } else if (profile != null) {
            studioActionMessage = "Permite el micrófono para iniciar la emisión."
        }
    }

    val onToggleStudioBroadcast = {
        if (stream.isStreaming) {
            service?.stopStreaming()
            studioActionMessage = ""
        } else {
            val profiles = profileStore.loadProfiles()
            val lastProfileName = profileStore.lastUsedProfileName()
            val profile = profiles.firstOrNull { it.name == lastProfileName } ?: profiles.firstOrNull()
            if (profile == null) {
                studioActionMessage = "Configura y guarda un perfil de Icecast en Emisión."
                selectedTab = 2
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startProfileBroadcast(profile)
            } else {
                pendingStudioProfile = profile
                val requestedPermissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                studioPermissionLauncher.launch(requestedPermissions.toTypedArray())
            }
        }
    }

    LaunchedEffect(service) { service?.playback?.collectLatest { playback = it } }
    LaunchedEffect(service) { service?.streamStatus?.collectLatest { stream = it } }
    LaunchedEffect(service) { service?.mixLevels?.collectLatest { mixLevels = it } }
    LaunchedEffect(service) { service?.savedPlaylists?.collectLatest { playlists = it } }
    LaunchedEffect(service) { service?.favoriteTracks?.collectLatest { favorites = it } }
    LaunchedEffect(service) { service?.swiperStatus?.collectLatest { swiperStatus = it } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                MiniPlayerBar(
                    playback = playback,
                    onExpand = { playerExpanded = true },
                    onTogglePlayback = { service?.togglePlayback() },
                    onNext = { service?.skipToNext() },
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                ) {
                    listOf("Estudio", "DSP", "Emisión").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                Icon(
                                    imageVector = when (index) {
                                        0 -> Icons.Filled.GraphicEq
                                        1 -> Icons.Filled.Tune
                                        else -> Icons.Filled.Radio
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            ConsoleHeader(service != null, stream, darkTheme, onToggleTheme)
            Spacer(Modifier.height(20.dp))
            when (selectedTab) {
                0 -> StudioScreen(service, meters, stream, mixLevels, onToggleStudioBroadcast, studioActionMessage)
                1 -> DspScreen(service)
                else -> BroadcastScreen(stream)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (playerExpanded) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { playerExpanded = false },
            modifier = Modifier.fillMaxHeight(0.96f),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            ExpandedPlayer(
                service = service,
                playback = playback,
                playlists = playlists,
                favorites = favorites,
                swiperStatus = swiperStatus,
                onClose = { playerExpanded = false },
            )
        }
    }
}

@Composable
private fun ConsoleHeader(ready: Boolean, stream: StreamSnapshot, darkTheme: Boolean, onToggleTheme: () -> Unit) {
    val statusLabel = if (stream.isConnected) "AL AIRE" else if (stream.isStreaming) "CONECTANDO" else if (ready) "MOTOR LISTO" else "INICIANDO"
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("RADIO SUITE / CONTROL ROOM", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("En directo", style = MaterialTheme.typography.headlineSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        Modifier.size(7.dp),
                        shape = CircleShape,
                        color = if (stream.isConnected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    ) {}
                    Spacer(Modifier.width(7.dp))
                    Text(
                        statusLabel,
                        color = if (stream.isConnected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
            IconButton(onClick = onToggleTheme) {
                Icon(
                    imageVector = if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    contentDescription = if (darkTheme) "Cambiar a modo claro" else "Cambiar a modo oscuro",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    playback: PlaybackSnapshot,
    onExpand: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).clickable(onClick = onExpand), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(Modifier.size(42.dp), playback.artworkData)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(playback.title, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    Text(if (playback.isPlaying) "ARCHIVO LOCAL · ${playback.queueSize} EN COLA" else "ABRIR REPRODUCTOR", color = MutedText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = onNext, enabled = playback.queueSize > 1) { Text("▶|") }
            IconButton(onClick = onTogglePlayback, enabled = playback.queueSize > 0) {
                Text(if (playback.isPlaying) "Ⅱ" else "▶", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
            }
        }
        LinearProgressIndicator(
            progress = { if (playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ExpandedPlayer(
    service: RadioEngineService?,
    playback: PlaybackSnapshot,
    playlists: List<SavedPlaylistSummary>,
    favorites: List<FavoriteTrack>,
    swiperStatus: SwiperSnapshot,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedView by rememberSaveable { mutableIntStateOf(0) }
    var selectedPlaylistName by rememberSaveable { mutableStateOf("") }
    var playlistNameDraft by rememberSaveable { mutableStateOf("") }
    var playlistSearch by rememberSaveable { mutableStateOf("") }
    var renamePlaylistDraft by rememberSaveable { mutableStateOf("") }
    var isRenamingPlaylist by rememberSaveable { mutableStateOf(false) }
    var isConfirmingPlaylistDelete by rememberSaveable { mutableStateOf(false) }
    var isAddingQueueTracks by rememberSaveable { mutableStateOf(false) }
    var addMessage by rememberSaveable { mutableStateOf("") }
    var seekPosition by remember(playback.currentIndex) { mutableFloatStateOf(playback.positionMs.toFloat()) }
    var isSeeking by remember { mutableStateOf(false) }
    var contextMenuIndex by remember { mutableIntStateOf(-1) }
    var playlistTrackMenuIndex by remember { mutableIntStateOf(-1) }
    var playlistPickerIndex by remember { mutableIntStateOf(-1) }
    var favoriteToAdd by remember { mutableStateOf<FavoriteTrack?>(null) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var playerMenuExpanded by remember { mutableStateOf(false) }
    var queueSearch by rememberSaveable { mutableStateOf("") }
    val selectedPlaylist = playlists.firstOrNull { it.name == selectedPlaylistName }

    val swiperFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            coroutineScope.launch {
                addMessage = "Leyendo carpeta para Swiper…"
                try {
                    val folder = withContext(Dispatchers.IO) {
                        context.contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        displayName(context, treeUri) to collectAudioTracks(context, treeUri)
                    }
                    val configured = service?.configureSwiperFolder(treeUri, folder.first, folder.second) == true
                    addMessage = if (configured) {
                        "Swiper activo: ${folder.first}. Insertará un identificativo cada dos temas."
                    } else {
                        "La carpeta seleccionada no contiene audio compatible."
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(AUDIO_IMPORT_TAG, "No se pudo configurar la carpeta Swiper", error)
                    addMessage = "No se pudo leer la carpeta para Swiper."
                }
            }
        }
    }

    LaunchedEffect(playback.positionMs, playback.currentIndex) {
        if (!isSeeking) seekPosition = playback.positionMs.toFloat()
    }

    Column(
        Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("RADIO SUITE", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Reproductor", style = MaterialTheme.typography.titleLarge)
            }
            Box {
                IconButton(onClick = { playerMenuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Opciones del reproductor")
                }
                DropdownMenu(
                    expanded = playerMenuExpanded,
                    onDismissRequest = { playerMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Añadir a la cola") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                        onClick = {
                            selectedView = 1
                            playerMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir lista") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                        onClick = {
                            selectedView = 2
                            selectedPlaylistName = ""
                            playerMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (swiperStatus.isConfigured) "Cambiar Swiper" else "Añadir Swiper") },
                        leadingIcon = { Icon(Icons.Filled.Radio, contentDescription = null) },
                        onClick = {
                            playerMenuExpanded = false
                            swiperFolderPicker.launch(null)
                        },
                    )
                    if (swiperStatus.isConfigured) {
                        DropdownMenuItem(
                            text = { Text("Swiper · ${swiperStatus.folderName} (${swiperStatus.trackCount})", maxLines = 1) },
                            enabled = false,
                            onClick = {},
                        )
                        DropdownMenuItem(
                            text = { Text("Desactivar Swiper") },
                            onClick = {
                                service?.clearSwiperFolder()
                                service?.removeSwiperItemsFromQueue()
                                playerMenuExpanded = false
                                addMessage = "Swiper desactivado."
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Cerrar reproductor") },
                        onClick = {
                            playerMenuExpanded = false
                            onClose()
                        },
                    )
                }
            }
        }

        ScrollableTabRow(selectedTabIndex = if (selectedView == 3) 1 else 0, edgePadding = 0.dp) {
            listOf("Ahora" to 0, "Favoritos · ${favorites.size}" to 3).forEach { (label, targetView) ->
                Tab(
                    selected = selectedView == targetView,
                    onClick = { selectedView = targetView },
                    text = { Text(label, maxLines = 1) },
                    icon = {
                        Icon(
                            imageVector = when (targetView) {
                                0 -> Icons.Filled.PlayCircle
                                else -> Icons.Filled.Favorite
                            },
                            contentDescription = null,
                        )
                    },
                )
            }
        }

        when (selectedView) {
            0 -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TrackArtwork(
                    Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 12.dp),
                    playback.artworkData,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(playback.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                        val subtitle = listOf(playback.artist, playback.album).filter(String::isNotBlank).joinToString(" · ")
                        Text(
                            subtitle.ifBlank { "ARCHIVO LOCAL · RADIOSUITE" },
                            color = MutedText,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                    Text("${playback.currentIndex + 1}/${playback.queueSize}", color = MutedText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    TextButton(onClick = { service?.toggleFavoriteCurrentTrack() }, enabled = playback.queueSize > 0) {
                        Text(if (playback.isFavorite) "♥" else "♡", color = if (playback.isFavorite) MaterialTheme.colorScheme.primary else MutedText, fontSize = 22.sp)
                    }
                }

                Column {
                    Slider(
                        value = seekPosition.coerceIn(0f, playback.durationMs.coerceAtLeast(1L).toFloat()),
                        onValueChange = {
                            isSeeking = true
                            seekPosition = it
                        },
                        valueRange = 0f..playback.durationMs.coerceAtLeast(1L).toFloat(),
                        onValueChangeFinished = {
                            service?.seekTo(seekPosition.toLong())
                            isSeeking = false
                        },
                        enabled = playback.durationMs > 0L,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(timeLabel(if (isSeeking) seekPosition.toLong() else playback.positionMs), color = MutedText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(timeLabel(playback.durationMs), color = MutedText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { service?.toggleShuffle() }) {
                        Text("⤨", color = if (playback.shuffleEnabled) MaterialTheme.colorScheme.primary else MutedText)
                    }
                    TextButton(onClick = { service?.skipToPrevious() }, enabled = playback.queueSize > 0) {
                        Text("|◀", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp).clickable(enabled = playback.queueSize > 0) { service?.togglePlayback() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (playback.isPlaying) "Ⅱ" else "▶", color = MaterialTheme.colorScheme.onPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = { service?.skipToNext() }, enabled = playback.queueSize > 0) {
                        Text("▶|", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
                    }
                    TextButton(onClick = { service?.cycleRepeatMode() }) {
                        val repeatLabel = when (playback.repeatMode) {
                            androidx.media3.common.Player.REPEAT_MODE_ALL -> "↻"
                            androidx.media3.common.Player.REPEAT_MODE_ONE -> "↻1"
                            else -> "↻"
                        }
                        Text(repeatLabel, color = if (playback.repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) MutedText else MaterialTheme.colorScheme.primary)
                    }
                }

                Box {
                    TextButton(onClick = { speedMenuExpanded = true }) {
                        Text("VELOCIDAD  ${playback.playbackSpeed}×", color = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${speed}×") },
                                onClick = {
                                    service?.setPlaybackSpeed(speed)
                                    speedMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { selectedView = 1 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("COLA · ${playback.queueSize}", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { selectedView = 2 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("PLAYLISTS · ${playlists.size}", maxLines = 1)
                    }
                }
            }

            1 -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
            ) {
                val visibleQueue = playback.queue.mapIndexedNotNull { index, title ->
                    if (queueSearch.isBlank() || title.contains(queueSearch.trim(), ignoreCase = true)) index to title else null
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedView = 0 }) { Text("‹ AHORA") }
                        Text("A continuación", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AddTracksButton(service, Modifier) { result ->
                            addMessage = when {
                                result.error != null -> result.error
                                result.folderAdded -> "Se añadió la carpeta ${result.folderName}; elegirá una pista al azar entre ${result.folderTrackCount}."
                                result.trackCount > 0 -> "Se añadieron ${result.trackCount} pistas a la cola."
                                result.source == AudioImportSource.FOLDER -> "La carpeta no contiene pistas de audio compatibles."
                                result.trackCount == 0 -> "No se añadieron pistas. Comprueba el formato del archivo."
                                else -> "El reproductor no está listo; vuelve a intentarlo."
                            }
                        }
                        TextButton(onClick = { service?.clearQueue() }, enabled = playback.queueSize > 0) { Text("VACIAR") }
                    }
                }
                OutlinedTextField(
                    value = queueSearch,
                    onValueChange = { queueSearch = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    label = { Text("Buscar pista en la cola") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
                if (addMessage.isNotBlank()) Text(addMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (playback.queue.isEmpty()) {
                    Text("Añade archivos de audio para empezar.", modifier = Modifier.padding(top = 20.dp), color = MutedText)
                } else if (visibleQueue.isEmpty()) {
                    Text("No hay pistas que coincidan con la búsqueda.", modifier = Modifier.padding(top = 20.dp), color = MutedText)
                } else {
                    if (queueSearch.isNotBlank()) {
                        Text("${visibleQueue.size} resultados", color = MutedText, style = MaterialTheme.typography.bodySmall)
                    }
                    visibleQueue.forEach { (index, title) ->
                        Box(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { service?.playQueueItem(index) },
                                        onLongClick = { contextMenuIndex = index },
                                    )
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("%02d".format(index + 1), modifier = Modifier.width(38.dp), color = if (index == playback.currentIndex) MaterialTheme.colorScheme.primary else MutedText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                Column(Modifier.weight(1f)) {
                                    Text(title, maxLines = 1, fontWeight = if (index == playback.currentIndex) FontWeight.Bold else FontWeight.Normal)
                                    if (index == playback.currentIndex) Text("REPRODUCIENDO", color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Text("⋮", color = MutedText, fontSize = 20.sp)
                            }
                            DropdownMenu(expanded = contextMenuIndex == index, onDismissRequest = { contextMenuIndex = -1 }) {
                                DropdownMenuItem(text = { Text("Subir en la cola") }, enabled = index > 0, onClick = {
                                    service?.moveQueueItem(index, index - 1)
                                    contextMenuIndex = -1
                                })
                                DropdownMenuItem(text = { Text("Bajar en la cola") }, enabled = index < playback.queue.lastIndex, onClick = {
                                    service?.moveQueueItem(index, index + 1)
                                    contextMenuIndex = -1
                                })
                                DropdownMenuItem(text = { Text("Reproducir a continuación") }, onClick = {
                                    service?.playQueueItemNext(index)
                                    contextMenuIndex = -1
                                })
                                DropdownMenuItem(text = { Text("Añadir a playlist") }, enabled = playlists.isNotEmpty(), onClick = {
                                    playlistPickerIndex = index
                                    contextMenuIndex = -1
                                })
                                DropdownMenuItem(text = { Text("Eliminar de la cola", color = MaterialTheme.colorScheme.error) }, onClick = {
                                    service?.removeQueueItem(index)
                                    contextMenuIndex = -1
                                })
                            }
                        }
                    }
                }
            }

            2 -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selectedPlaylist != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedPlaylistName = "" }) { Text("‹ LISTAS") }
                        Column(Modifier.weight(1f)) {
                            Text(selectedPlaylist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text("${selectedPlaylist.trackCount} elementos", color = MutedText, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { isRenamingPlaylist = true; renamePlaylistDraft = selectedPlaylist.name }) { Text("RENOMBRAR") }
                        TextButton(onClick = { isConfirmingPlaylistDelete = true }) { Text("ELIMINAR", color = MaterialTheme.colorScheme.error) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AddTracksToPlaylistButton(selectedPlaylist.name, service, Modifier.weight(1f)) { result ->
                                addMessage = when {
                                    result.error != null -> result.error
                                    result.folderAdded -> "Se añadió la carpeta ${result.folderName} como una entrada aleatoria."
                                    result.trackCount > 0 -> "Se añadieron ${result.trackCount} pistas a la playlist."
                                    result.source == AudioImportSource.FOLDER -> "La carpeta no contiene pistas de audio compatibles."
                                    else -> "No se añadieron pistas."
                                }
                            }
                            OutlinedButton(onClick = { isAddingQueueTracks = true }, enabled = playback.queueSize > 0, modifier = Modifier.weight(1f)) { Text("DESDE COLA") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { service?.appendPlaylist(selectedPlaylist.name) }) { Text("AÑADIR A COLA") }
                            TextButton(onClick = { service?.loadPlaylist(selectedPlaylist.name) }, enabled = selectedPlaylist.trackCount > 0) { Text("REPRODUCIR") }
                            TextButton(onClick = { service?.playPlaylistRandom(selectedPlaylist.name) }, enabled = selectedPlaylist.trackCount > 0) { Text("ALEATORIA") }
                        }
                    }
                    if (addMessage.isNotBlank()) Text(addMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    if (selectedPlaylist.tracks.isEmpty()) {
                        Text("Esta playlist está vacía.", color = MutedText, style = MaterialTheme.typography.bodySmall)
                    } else {
                        selectedPlaylist.tracks.forEachIndexed { index, track ->
                            Box(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { service?.playPlaylistTrack(selectedPlaylist.name, index) }.padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("%02d".format(index + 1), modifier = Modifier.width(38.dp), color = MutedText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                        Text(
                                            if (track.isFolder) "Carpeta · ${track.title} (${track.folderTrackCount} pistas)" else track.title,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                        )
                                    IconButton(onClick = { playlistTrackMenuIndex = index }) { Text("⋮", color = MutedText, fontSize = 20.sp) }
                                }
                                DropdownMenu(
                                    expanded = playlistTrackMenuIndex == index,
                                    onDismissRequest = { playlistTrackMenuIndex = -1 },
                                ) {
                                    DropdownMenuItem(text = { Text("Subir en la playlist") }, enabled = index > 0, onClick = {
                                        service?.moveTrackInPlaylist(selectedPlaylist.name, index, index - 1)
                                        playlistTrackMenuIndex = -1
                                    })
                                    DropdownMenuItem(text = { Text("Bajar en la playlist") }, enabled = index < selectedPlaylist.tracks.lastIndex, onClick = {
                                        service?.moveTrackInPlaylist(selectedPlaylist.name, index, index + 1)
                                        playlistTrackMenuIndex = -1
                                    })
                                    DropdownMenuItem(text = { Text("Quitar de la playlist", color = MaterialTheme.colorScheme.error) }, onClick = {
                                        service?.removeTrackFromPlaylist(selectedPlaylist.name, index)
                                        playlistTrackMenuIndex = -1
                                    })
                                }
                            }
                        }
                    }
                } else {
                    TextButton(onClick = { selectedView = 0 }) { Text("‹ REPRODUCTOR") }
                    OutlinedTextField(
                        value = playlistSearch,
                        onValueChange = { playlistSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Buscar playlist") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = playlistNameDraft,
                            onValueChange = { playlistNameDraft = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Nueva playlist") },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                if (service?.createPlaylist(playlistNameDraft) == true) playlistNameDraft = ""
                            },
                            enabled = playlistNameDraft.isNotBlank(),
                        ) { Text("CREAR") }
                    }
                    if (playlists.isEmpty()) {
                        Text("Crea playlists y añade pistas desde la cola.", color = MutedText, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val visiblePlaylists = playlists.filter { it.name.contains(playlistSearch.trim(), ignoreCase = true) }
                        if (visiblePlaylists.isEmpty()) {
                            Text("No hay playlists que coincidan con la búsqueda.", color = MutedText, style = MaterialTheme.typography.bodySmall)
                        }
                        visiblePlaylists.forEach { playlist ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth().clickable { selectedPlaylistName = playlist.name },
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text("${playlist.trackCount} elementos", color = MutedText, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("›", fontSize = 22.sp, color = MutedText)
                                }
                            }
                        }
                    }
                }
            }

            else -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
            ) {
                if (favorites.isEmpty()) {
                    Text("Marca canciones con ♡ para guardarlas aquí.", color = MutedText)
                } else {
                    favorites.forEach { favorite ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { service?.playFavoriteTrack(favorite.uri) }) {
                                Text(favorite.title, modifier = Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.onBackground)
                            }
                            TextButton(onClick = { favoriteToAdd = favorite }) { Text("A LISTA") }
                            TextButton(onClick = { service?.removeFavorite(favorite.uri) }) { Text("♥", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (playlistPickerIndex >= 0) {
        AlertDialog(
            onDismissRequest = { playlistPickerIndex = -1 },
            title = { Text("Añadir a playlist") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = {
                                service?.addQueueItemToPlaylist(playlistPickerIndex, playlist.name)
                                playlistPickerIndex = -1
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${playlist.name}  ·  ${playlist.trackCount} elementos", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { playlistPickerIndex = -1 }) { Text("CERRAR") }
            },
        )
    }

    if (isAddingQueueTracks && selectedPlaylist != null) {
        AlertDialog(
            onDismissRequest = { isAddingQueueTracks = false },
            title = { Text("Añadir desde la cola") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    playback.queue.forEachIndexed { index, title ->
                        TextButton(
                            onClick = {
                                service?.addQueueItemToPlaylist(index, selectedPlaylist.name)
                                isAddingQueueTracks = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(title, modifier = Modifier.fillMaxWidth(), maxLines = 1) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { isAddingQueueTracks = false }) { Text("CERRAR") } },
        )
    }

    if (favoriteToAdd != null) {
        AlertDialog(
            onDismissRequest = { favoriteToAdd = null },
            title = { Text("Añadir favorito a playlist") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = {
                                favoriteToAdd?.let { service?.addFavoriteToPlaylist(it.uri, playlist.name) }
                                favoriteToAdd = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(playlist.name, modifier = Modifier.fillMaxWidth()) }
                    }
                    if (playlists.isEmpty()) Text("Crea una playlist primero.", color = MutedText)
                }
            },
            confirmButton = { TextButton(onClick = { favoriteToAdd = null }) { Text("CERRAR") } },
        )
    }

    if (isRenamingPlaylist && selectedPlaylist != null) {
        AlertDialog(
            onDismissRequest = { isRenamingPlaylist = false },
            title = { Text("Renombrar playlist") },
            text = {
                OutlinedTextField(
                    value = renamePlaylistDraft,
                    onValueChange = { renamePlaylistDraft = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (service?.renamePlaylist(selectedPlaylist.name, renamePlaylistDraft) == true) {
                        selectedPlaylistName = renamePlaylistDraft.trim()
                        isRenamingPlaylist = false
                    }
                }, enabled = renamePlaylistDraft.isNotBlank()) { Text("GUARDAR") }
            },
            dismissButton = { TextButton(onClick = { isRenamingPlaylist = false }) { Text("CANCELAR") } },
        )
    }

    if (isConfirmingPlaylistDelete && selectedPlaylist != null) {
        AlertDialog(
            onDismissRequest = { isConfirmingPlaylistDelete = false },
            title = { Text("Eliminar playlist") },
            text = { Text("Se eliminará '${selectedPlaylist.name}'. Las pistas originales no se borrarán.") },
            confirmButton = {
                TextButton(onClick = {
                    service?.deletePlaylist(selectedPlaylist.name)
                    selectedPlaylistName = ""
                    isConfirmingPlaylistDelete = false
                }) { Text("ELIMINAR", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { isConfirmingPlaylistDelete = false }) { Text("CANCELAR") } },
        )
    }
}

@Composable
private fun TrackArtwork(modifier: Modifier = Modifier, artworkData: ByteArray?) {
    val bitmap = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, artworkData) {
        value = withContext(Dispatchers.IO) {
            artworkData?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }.value

    Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = "Carátula del álbum", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            RadioArtwork(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun RadioArtwork(modifier: Modifier = Modifier) {
    Canvas(
        modifier.clip(MaterialTheme.shapes.medium).background(
            Brush.linearGradient(listOf(Color(0xFF514033), Color(0xFF171615), Color(0xFF352A22))),
        ),
    ) {
        val center = this.center
        val unit = size.minDimension
        for (ring in 1..4) {
            val radius = unit * (0.13f + ring * 0.105f)
            drawArc(
                color = if (ring % 2 == 0) Color(0xFFB87952) else Color(0xFFE3B66A),
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = unit * 0.012f, cap = StrokeCap.Round),
            )
        }
        drawCircle(Color(0xFFE3B66A), radius = unit * 0.105f, center = center)
        val playMark = Path().apply {
            moveTo(center.x - unit * 0.025f, center.y - unit * 0.042f)
            lineTo(center.x + unit * 0.045f, center.y)
            lineTo(center.x - unit * 0.025f, center.y + unit * 0.042f)
            close()
        }
        drawPath(playMark, color = Color(0xFF171615))
    }
}

@Composable
private fun StudioScreen(
    service: RadioEngineService?,
    meters: MeterSnapshot,
    stream: StreamSnapshot,
    mixLevels: MixLevels,
    onToggleBroadcast: () -> Unit,
    actionMessage: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.People, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Oyente en línea", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val listenerStatus = when {
                    !stream.isStreaming -> "Inicia una emisión para consultar oyentes"
                    stream.activeListeners == null -> "No disponible en el servidor"
                    stream.activeListeners == 0 -> "Sin oyentes conectados"
                    stream.activeListeners == 1 -> "1 oyente activo"
                    else -> "${stream.activeListeners} oyentes activos"
                }
                Text(listenerStatus, color = MutedText, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Text(
                stream.activeListeners?.toString() ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(Modifier.height(22.dp))
    SectionHeading("SEÑAL", "Monitor de audio")
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MeterRow("ENTRADA", meters.inputDb)
            MeterRow("SALIDA", meters.outputDb)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RMS ${dbLabel(meters.rmsDb)}", style = MaterialTheme.typography.labelMedium)
                Text("AGC +${meters.agcGainDb.coerceAtLeast(0f).toInt()} dB", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    Spacer(Modifier.height(26.dp))
    SectionHeading("EN VIVO", "Controles de mezcla")
    Text(
        if (stream.isStreaming) "CAPTURA ACTIVA · AUDIO EN PROCESAMIENTO" else "FUENTES LISTAS · INICIA UNA EMISIÓN PARA CAPTURAR MICRÓFONO",
        color = if (stream.isStreaming) MaterialTheme.colorScheme.primary else MutedText,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(10.dp))
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(330.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveFader(
                label = "MICRÓFONO",
                gainDb = mixLevels.microphoneGainDb,
                muted = mixLevels.microphoneMuted,
                modifier = Modifier.weight(1f),
                onGainChange = { service?.setMicrophoneGain(it) },
                onToggleMute = { service?.toggleMicrophoneMute() },
            )
            LiveFader(
                label = "MÚSICA",
                gainDb = mixLevels.playerGainDb,
                muted = mixLevels.playerMuted,
                modifier = Modifier.weight(1f),
                onGainChange = { service?.setPlayerGain(it) },
                onToggleMute = { service?.togglePlayerMute() },
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    when {
                        stream.isConnected -> "Emisión activa"
                        stream.isStreaming && stream.error.isNotBlank() -> "Reconectando a Icecast"
                        stream.isStreaming -> "Conectando a Icecast"
                        else -> "Emisión detenida"
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stream.endpoint.ifBlank { "Configura tu servidor" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onToggleBroadcast,
                enabled = stream.isStreaming || service != null,
                modifier = Modifier.width(120.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (stream.isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = if (stream.isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(if (stream.isStreaming) "Detener" else "Emitir")
            }
        }
    }
    if (actionMessage.isNotBlank()) {
        Text(actionMessage, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (stream.error.isNotBlank()) {
        Text(stream.error, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LiveFader(
    label: String,
    gainDb: Float,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onGainChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
) {
    val stateColor = if (muted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text("${gainDb.toInt()} dB", color = MutedText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Box(Modifier.width(56.dp).weight(1f), contentAlignment = Alignment.Center) {
            Slider(
                value = gainDb,
                onValueChange = onGainChange,
                valueRange = -24f..6f,
                enabled = !muted,
                modifier = Modifier.requiredSize(width = 226.dp, height = 48.dp).rotate(-90f),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            border = BorderStroke(1.dp, stateColor.copy(alpha = 0.9f)),
            shadowElevation = 7.dp,
            modifier = Modifier.clickable(onClick = onToggleMute),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(Modifier.size(6.dp), shape = CircleShape, color = stateColor) {}
                Text(if (muted) "MUTE" else "ACTIVO", color = stateColor, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/*
                Slider(
                    value = seekPosition.coerceIn(0f, playback.durationMs.coerceAtLeast(1L).toFloat()),
                    onValueChange = { seekPosition = it },
                    valueRange = 0f..playback.durationMs.coerceAtLeast(1L).toFloat(),
                    onValueChangeFinished = { service?.seekTo(seekPosition.toLong()) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeLabel(playback.positionMs), color = MutedText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(timeLabel(playback.durationMs), color = MutedText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { service?.toggleShuffle() }) { Text(if (playback.shuffleEnabled) "ALEAT. ✓" else "ALEAT.") }
                    TextButton(onClick = { service?.skipToPrevious() }, enabled = playback.queueSize > 0) { Text("|◀") }
                    Button(onClick = { service?.togglePlayback() }, enabled = playback.queueSize > 0) {
                        Text(if (playback.isPlaying) "Ⅱ" else "▶", fontSize = 18.sp)
                    }
                    TextButton(onClick = { service?.skipToNext() }, enabled = playback.queueSize > 0) { Text("▶|") }
                    TextButton(onClick = { service?.cycleRepeatMode() }) {
                        Text(when (playback.repeatMode) {
                            androidx.media3.common.Player.REPEAT_MODE_ALL -> "REP. TODO"
                            androidx.media3.common.Player.REPEAT_MODE_ONE -> "REP. UNA"
                            else -> "REPETIR"
                        })
                    }
                }
            }
        }

        VolumeFader("MÚSICA", playerGain, { playerGain = it; service?.setPlayerGain(it) })
        VolumeFader("MICRÓFONO", microphoneGain, { microphoneGain = it; service?.setMicrophoneGain(it) })

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showingPlaylists, onClick = { showingPlaylists = false }, label = { Text("COLA · ${playback.queueSize}") })
            FilterChip(selected = showingPlaylists, onClick = { showingPlaylists = true }, label = { Text("LISTAS · ${playlists.size}") })
            Spacer(Modifier.weight(1f))
            AddTracksButton(service, Modifier)
        }

        if (showingPlaylists) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Nombre de la lista") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        if (service?.createPlaylist(newPlaylistName) == true) newPlaylistName = ""
                    },
                    enabled = playback.queueSize > 0 && newPlaylistName.isNotBlank(),
                ) { Text("GUARDAR") }
            }
            if (playlists.isEmpty()) {
                Text("Crea una lista desde la cola actual.", color = MutedText, style = MaterialTheme.typography.bodySmall)
            } else {
                playlists.forEach { playlist ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(playlist.name, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${playlist.trackCount} pistas", color = MutedText, style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { service?.appendPlaylist(playlist.name) }) { Text("AÑADIR") }
                                    TextButton(onClick = { service?.loadPlaylist(playlist.name) }) { Text("CARGAR") }
                                }
                            }
                        }
                    }
                }
            }
        } else if (playback.queue.isEmpty()) {
            Text("Añade archivos de audio para empezar.", color = MutedText, style = MaterialTheme.typography.bodySmall)
        } else {
            playback.queue.forEachIndexed { index, title ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { service?.playQueueItem(index) }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%02d".format(index + 1), modifier = Modifier.width(32.dp), color = if (index == playback.currentIndex) MaterialTheme.colorScheme.primary else MutedText, fontFamily = FontFamily.Monospace)
                    Text(title, modifier = Modifier.weight(1f), maxLines = 1, fontWeight = if (index == playback.currentIndex) FontWeight.Bold else FontWeight.Normal)
                    TextButton(onClick = { service?.removeQueueItem(index) }) { Text("×", color = MutedText) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun VolumeFader(label: String, gainDb: Float, onChange: (Float) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedText)
                Text(if (gainDb <= -59f) "MUTE" else "${gainDb.toInt()} dB", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
            Slider(gainDb, onChange, valueRange = -60f..6f)
        }
    }
}

@Composable
private fun StudioScreen(service: RadioEngineService?, playback: PlaybackSnapshot, meters: MeterSnapshot, stream: StreamSnapshot) {
    SectionHeading("SEÑAL", "Monitor de audio")
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MeterRow("ENTRADA", meters.inputDb)
            MeterRow("SALIDA", meters.outputDb)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RMS ${dbLabel(meters.rmsDb)}", style = MaterialTheme.typography.labelMedium)
                Text("AGC +${meters.agcGainDb.coerceAtLeast(0f).toInt()} dB", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    Spacer(Modifier.height(26.dp))
    SectionHeading("REPRODUCCIÓN · AHORA", "Mezcla en vivo")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VolumeFader("MICRÓFONO", mixLevels.microphoneGainDb) { service?.setMicrophoneGain(it) }
        VolumeFader("MÚSICA", mixLevels.playerGainDb) { service?.setPlayerGain(it) }
    }
    Spacer(Modifier.height(20.dp))
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(if (stream.isConnected) "Emisión activa" else if (stream.isStreaming) "Conectando a Icecast" else "Emisión detenida", fontWeight = FontWeight.SemiBold)
                Text(stream.endpoint.ifBlank { "Configura tu servidor" }, style = MaterialTheme.typography.bodySmall, color = MutedText)
            }
            TextButton(onClick = { service?.stopStreaming() }, enabled = stream.isStreaming) { Text("DETENER", color = MaterialTheme.colorScheme.error) }
        }
    }
}

*/

@Composable
private fun AddTracksButton(
    service: RadioEngineService?,
    modifier: Modifier = Modifier,
    onResult: (AudioImportResult) -> Unit,
) {
    AudioImportButton(
        label = "＋  AÑADIR AUDIO",
        modifier = modifier,
        enabled = service != null,
        onImport = { selection ->
            if (selection.source == AudioImportSource.FILES) {
                AudioImportResult(
                    service?.enqueueTracks(selection.tracks) ?: -1,
                    AudioImportSource.FILES,
                )
            } else {
                val added = selection.folderUri?.let { treeUri ->
                    service?.enqueueRandomFolder(treeUri, selection.folderName, selection.tracks)
                } == true
                AudioImportResult(
                    trackCount = if (added) 1 else 0,
                    source = AudioImportSource.FOLDER,
                    error = if (!added && selection.tracks.isNotEmpty()) {
                        "Se encontraron ${selection.tracks.size} pistas, pero no se pudieron añadir a la cola."
                    } else {
                        null
                    },
                    folderAdded = added,
                    folderName = selection.folderName,
                    folderTrackCount = selection.tracks.size,
                )
            }
        },
        onResult = onResult,
    )
}

@Composable
private fun AddTracksToPlaylistButton(
    playlistName: String,
    service: RadioEngineService?,
    modifier: Modifier = Modifier,
    onResult: (AudioImportResult) -> Unit,
) {
    AudioImportButton(
        label = "IMPORTAR AUDIO",
        modifier = modifier,
        enabled = service != null,
        outlined = true,
        onImport = { selection ->
            if (selection.source == AudioImportSource.FILES) {
                AudioImportResult(
                    service?.addTracksToPlaylist(playlistName, selection.tracks) ?: 0,
                    AudioImportSource.FILES,
                )
            } else {
                val added = selection.folderUri?.let { treeUri ->
                    service?.addFolderToPlaylist(playlistName, treeUri, selection.folderName, selection.tracks)
                } == true
                AudioImportResult(
                    trackCount = if (added) 1 else 0,
                    source = AudioImportSource.FOLDER,
                    error = if (!added && selection.tracks.isNotEmpty()) {
                        "Se encontraron ${selection.tracks.size} pistas, pero no se pudieron añadir a la playlist."
                    } else {
                        null
                    },
                    folderAdded = added,
                    folderName = selection.folderName,
                    folderTrackCount = selection.tracks.size,
                )
            }
        },
        onResult = onResult,
    )
}

private enum class AudioImportSource { FILES, FOLDER }

private data class AudioImportSelection(
    val source: AudioImportSource,
    val tracks: List<Pair<Uri, String>>,
    val folderUri: Uri? = null,
    val folderName: String = "",
)

private data class AudioImportResult(
    val trackCount: Int,
    val source: AudioImportSource,
    val error: String? = null,
    val folderAdded: Boolean = false,
    val folderName: String = "",
    val folderTrackCount: Int = 0,
)

@Composable
private fun AudioImportButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    outlined: Boolean = false,
    onImport: (AudioImportSelection) -> AudioImportResult,
    onResult: (AudioImportResult) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                importing = true
                try {
                    val tracks = withContext(Dispatchers.IO) {
                        uris.map { uri ->
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                            uri to displayName(context, uri)
                        }
                    }
                    onResult(onImport(AudioImportSelection(AudioImportSource.FILES, tracks)))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SecurityException) {
                    Log.e(AUDIO_IMPORT_TAG, "No se pudo conservar el acceso a los archivos seleccionados", error)
                    onResult(AudioImportResult(0, AudioImportSource.FILES, "No se pudo acceder a los archivos seleccionados."))
                } catch (error: Exception) {
                    Log.e(AUDIO_IMPORT_TAG, "No se pudieron procesar los archivos seleccionados", error)
                    onResult(AudioImportResult(0, AudioImportSource.FILES, "No se pudieron procesar los archivos seleccionados."))
                } finally {
                    importing = false
                }
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            coroutineScope.launch {
                importing = true
                try {
                    val selection = withContext(Dispatchers.IO) {
                        context.contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        AudioImportSelection(
                            source = AudioImportSource.FOLDER,
                            tracks = collectAudioTracks(context, treeUri),
                            folderUri = treeUri,
                            folderName = displayName(context, treeUri),
                        )
                    }
                    onResult(onImport(selection))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SecurityException) {
                    Log.e(AUDIO_IMPORT_TAG, "No se pudo conservar el acceso a la carpeta seleccionada", error)
                    onResult(AudioImportResult(0, AudioImportSource.FOLDER, "No se pudo conservar el acceso a la carpeta: ${error.localizedMessage ?: "permiso denegado"}."))
                } catch (error: IOException) {
                    Log.e(AUDIO_IMPORT_TAG, "No se pudieron leer las pistas de la carpeta seleccionada", error)
                    onResult(AudioImportResult(0, AudioImportSource.FOLDER, "No se pudo leer la carpeta: ${error.localizedMessage ?: "error de lectura"}."))
                } catch (error: Exception) {
                    Log.e(AUDIO_IMPORT_TAG, "No se pudo procesar la carpeta seleccionada", error)
                    onResult(AudioImportResult(0, AudioImportSource.FOLDER, "No se pudo procesar la carpeta: ${error.localizedMessage ?: error.javaClass.simpleName}."))
                } finally {
                    importing = false
                }
            }
        }
    }

    Box(modifier) {
        val buttonModifier = if (outlined) Modifier.fillMaxWidth() else Modifier
        if (outlined) {
            OutlinedButton(
                onClick = { menuExpanded = true },
                enabled = enabled && !importing,
                modifier = buttonModifier,
            ) {
                Text(if (importing) "BUSCANDO…" else "$label  ▾", maxLines = 1)
            }
        } else {
            Button(
                onClick = { menuExpanded = true },
                modifier = buttonModifier,
                enabled = enabled && !importing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(if (importing) "BUSCANDO…" else "$label  ▾", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Archivos de audio") },
                onClick = {
                    menuExpanded = false
                    filesPicker.launch(arrayOf("audio/*"))
                },
            )
            DropdownMenuItem(
                text = { Text("Carpeta de música") },
                onClick = {
                    menuExpanded = false
                    folderPicker.launch(null)
                },
            )
        }
    }
}

private fun collectAudioTracks(context: Context, treeUri: Uri): List<Pair<Uri, String>> {
    val resolver = context.contentResolver
    val pendingDocumentIds = ArrayDeque<String>().apply {
        addLast(DocumentsContract.getTreeDocumentId(treeUri))
    }
    val visitedDocumentIds = mutableSetOf<String>()
    val audioTracks = mutableListOf<Pair<Uri, String>>()
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    while (pendingDocumentIds.isNotEmpty()) {
        val parentDocumentId = pendingDocumentIds.removeFirst()
        if (!visitedDocumentIds.add(parentDocumentId)) continue
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("El proveedor de documentos no pudo enumerar la carpeta.")
        cursor.use {
            val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            if (idColumn < 0) throw IOException("El proveedor de documentos no devolvió identificadores.")
            val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) {
                val documentId = it.getString(idColumn) ?: continue
                val displayName = if (nameColumn >= 0) it.getString(nameColumn).orEmpty() else ""
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val mimeType = (if (mimeColumn >= 0) it.getString(mimeColumn).orEmpty() else "")
                    .ifBlank { runCatching { resolver.getType(documentUri) }.getOrNull().orEmpty() }
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    pendingDocumentIds.addLast(documentId)
                } else if (mimeType.startsWith("audio/") || isSupportedAudioFile(displayName)) {
                    audioTracks += documentUri to displayName.ifBlank { documentId.substringAfterLast('/') }
                }
            }
        }
    }
    return audioTracks.sortedBy { (_, title) -> title.lowercase() }
}

private fun isSupportedAudioFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("aac", "flac", "m4a", "mp3", "ogg", "opus", "wav")

private const val AUDIO_IMPORT_TAG = "RadioAudioImport"

@Composable
private fun DspScreen(service: RadioEngineService?) {
    var enabled by rememberSaveable { mutableStateOf(true) }
    var target by rememberSaveable { mutableFloatStateOf(-18f) }
    var maxGain by rememberSaveable { mutableFloatStateOf(24f) }
    var attack by rememberSaveable { mutableFloatStateOf(150f) }
    var release by rememberSaveable { mutableFloatStateOf(800f) }
    var output by rememberSaveable { mutableFloatStateOf(0f) }
    fun applyParameters() = service?.engine?.updatePipeline(DspParameters(enabled, target, maxGain, attack, release, output))

    SectionHeading("PROCESAMIENTO", "Cadena DSP")
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Procesamiento", fontWeight = FontWeight.SemiBold)
                    Text(if (enabled) "AGC y limitador activos" else "Bypass", color = MutedText, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; applyParameters() })
            }
            HorizontalRule()
            ParameterSlider("OBJETIVO AGC", target, -36f..-6f, "${target.toInt()} dB", { target = it }, ::applyParameters)
            ParameterSlider("GANANCIA MÁXIMA", maxGain, 0f..36f, "+${maxGain.toInt()} dB", { maxGain = it }, ::applyParameters)
            ParameterSlider("ATAQUE", attack, 10f..500f, "${attack.toInt()} ms", { attack = it }, ::applyParameters)
            ParameterSlider("RELEASE", release, 100f..2000f, "${release.toInt()} ms", { release = it }, ::applyParameters)
            ParameterSlider("NIVEL DE SALIDA", output, -12f..12f, "${if (output > 0) "+" else ""}${output.toInt()} dB", { output = it }, ::applyParameters)
        }
    }
}

@Composable
private fun ParameterSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MutedText)
            Text(valueLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, onChange, valueRange = range, onValueChangeFinished = onFinished)
    }
}

@Composable
private fun BroadcastScreen(stream: StreamSnapshot) {
    val context = LocalContext.current
    val profileStore = remember(context) { BroadcastProfileStore(context) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8000") }
    var mount by rememberSaveable { mutableStateOf("/live") }
    var password by remember { mutableStateOf("") }
    var bitrate by rememberSaveable { mutableIntStateOf(128) }
    var profileName by rememberSaveable { mutableStateOf("") }
    var selectedProfileName by rememberSaveable { mutableStateOf("") }
    var profiles by remember { mutableStateOf(profileStore.loadProfiles()) }
    var error by rememberSaveable { mutableStateOf("") }
    var feedback by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<BroadcastConfig?>(null) }

    LaunchedEffect(profileStore) {
        profiles = profileStore.loadProfiles()
        val lastName = profileStore.lastUsedProfileName()
        val lastProfile = profiles.firstOrNull { it.name == lastName } ?: profiles.firstOrNull()
        if (host.isBlank() && lastProfile != null) {
            profileName = lastProfile.name
            selectedProfileName = lastProfile.name
            host = lastProfile.host
            port = lastProfile.port.toString()
            mount = lastProfile.mount
            password = lastProfile.password
            bitrate = lastProfile.bitrate
        }
    }

    fun applyProfile(profile: BroadcastProfile) {
        profileName = profile.name
        selectedProfileName = profile.name
        host = profile.host
        port = profile.port.toString()
        mount = profile.mount
        password = profile.password
        bitrate = profile.bitrate
        profileStore.setLastUsedProfile(profile.name)
        error = ""
        feedback = ""
    }

    fun saveCurrentProfile(name: String): Boolean {
        val parsedPort = port.toIntOrNull()
        if (name.isBlank() || host.isBlank() || parsedPort == null || parsedPort !in 1..65535 || mount.isBlank()) {
            error = "Indica nombre, host, puerto y mount para guardar el perfil."
            feedback = ""
            return false
        }
        val profile = BroadcastProfile(
            name = name.trim(),
            host = host.trim(),
            port = parsedPort,
            mount = mount.trim(),
            password = password,
            codec = CodecType.Mp3,
            bitrate = bitrate,
        )
        if (!profileStore.saveProfile(profile)) {
            error = "No se pudo cifrar el perfil en este dispositivo."
            feedback = ""
            return false
        }
        profiles = profileStore.loadProfiles()
        selectedProfileName = profile.name
        profileName = profile.name
        profileStore.setLastUsedProfile(profile.name)
        error = ""
        feedback = "Perfil guardado de forma cifrada."
        return true
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val config = pending
        pending = null
        if (config != null && results.values.all { it }) startBroadcast(context, config) else if (config != null) error = "Permite el micrófono para iniciar."
    }

    SectionHeading("DESTINO", "Servidor Icecast")
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (profiles.isNotEmpty()) {
                Text("PERFILES GUARDADOS", fontSize = 10.sp, color = MutedText, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEach { profile ->
                        FilterChip(
                            selected = selectedProfileName == profile.name,
                            onClick = { applyProfile(profile) },
                            label = { Text(profile.name) },
                        )
                    }
                }
            }
            OutlinedTextField(
                profileName,
                { profileName = it },
                Modifier.fillMaxWidth(),
                label = { Text("Nombre del perfil") },
                placeholder = { Text("Emisora principal") },
                singleLine = true,
            )
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Host o IP") }, placeholder = { Text("radio.midominio.com") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.weight(0.7f), label = { Text("Puerto") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(mount, { mount = it }, Modifier.weight(1.3f), label = { Text("Mount point") }, singleLine = true)
            }
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Contraseña source") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("CODEC · MP3", fontSize = 11.sp, color = MutedText, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(64, 96, 128, 192, 256, 320).forEach { option ->
                    FilterChip(bitrate == option, { bitrate = option }, label = { Text("$option kbps") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { saveCurrentProfile(profileName) },
                    enabled = profileName.isNotBlank() && host.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("GUARDAR PERFIL") }
                if (selectedProfileName.isNotBlank()) {
                    TextButton(onClick = {
                        if (profileStore.deleteProfile(selectedProfileName)) {
                            profiles = profileStore.loadProfiles()
                            selectedProfileName = ""
                            profileName = ""
                            error = ""
                            feedback = "Perfil eliminado."
                        }
                    }) { Text("ELIMINAR", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (feedback.isNotBlank()) Text(feedback, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            if (stream.isStreaming) {
                Button(onClick = { context.startService(Intent(context, RadioEngineService::class.java).setAction(RadioEngineService.ACTION_STOP_STREAM)) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("DETENER EMISIÓN") }
            } else {
                Button(onClick = {
                    val parsedPort = port.toIntOrNull()
                    if (host.isBlank() || parsedPort == null || parsedPort !in 1..65535 || mount.isBlank()) {
                        error = "Revisa host, puerto y mount point."
                    } else {
                        val name = profileName.ifBlank { host.trim() }
                        val profileSaved = saveCurrentProfile(name)
                        pending = BroadcastConfig(name, host.trim(), parsedPort, mount.trim(), password, bitrate)
                        if (profileSaved) profileStore.setLastUsedProfile(name)
                        val requested = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (requested.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                            startBroadcast(context, pending!!)
                            pending = null
                        } else permissionLauncher.launch(requested.toTypedArray())
                    }
                }, Modifier.fillMaxWidth()) { Text("INICIAR EMISIÓN") }
            }
            if (stream.isStreaming) {
                Text(
                    when {
                        stream.isConnected -> "Conectado · ${stream.endpoint}"
                        stream.isStreaming && stream.error.isNotBlank() -> "Reconectando · ${stream.endpoint}"
                        stream.isStreaming -> "Intentando conectar · ${stream.endpoint}"
                        else -> stream.endpoint
                    },
                    color = if (stream.isConnected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (stream.error.isNotBlank()) Text(stream.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class BroadcastConfig(val profileName: String, val host: String, val port: Int, val mount: String, val password: String, val bitrate: Int)

private fun startBroadcast(context: Context, config: BroadcastConfig) {
    val intent = Intent(context, RadioEngineService::class.java)
        .setAction(RadioEngineService.ACTION_START_STREAM)
        .putExtra(RadioEngineService.EXTRA_HOST, config.host)
        .putExtra(RadioEngineService.EXTRA_PORT, config.port)
        .putExtra(RadioEngineService.EXTRA_MOUNT, config.mount)
        .putExtra(RadioEngineService.EXTRA_PASSWORD, config.password)
        .putExtra(RadioEngineService.EXTRA_BITRATE, config.bitrate)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
}

@Composable
private fun MeterRow(label: String, levelDb: Float) {
    val progress = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(70.dp), color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        LinearProgressIndicator({ progress }, Modifier.weight(1f).height(8.dp), color = if (levelDb > -3f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Text(dbLabel(levelDb), Modifier.width(58.dp).padding(start = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun SectionHeading(eyebrow: String, title: String) {
    Row(
        Modifier.padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(
            Modifier
                .width(3.dp)
                .height(38.dp)
                .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(eyebrow, color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun HorizontalRule() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

private fun displayName(context: Context, uri: Uri): String {
    val queriedName = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
        }
    } catch (error: Exception) {
        Log.w(AUDIO_IMPORT_TAG, "El proveedor no pudo devolver el nombre del documento", error)
        null
    }
    if (!queriedName.isNullOrBlank()) return queriedName
    val fallbackName = if (DocumentsContract.isTreeUri(uri)) {
        runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/').substringAfter(':') }.getOrNull()
    } else {
        uri.lastPathSegment?.substringAfterLast('/')
    }
    return fallbackName?.takeIf(String::isNotBlank) ?: "Archivo de audio"
}

private fun dbLabel(value: Float): String = if (value <= -119f) "−∞ dB" else "%.1f dB".format(value)

private fun timeLabel(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private val MutedText: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant