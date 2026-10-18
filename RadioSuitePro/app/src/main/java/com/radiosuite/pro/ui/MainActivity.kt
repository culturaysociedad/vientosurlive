package com.radiosuite.pro.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.radiosuite.pro.dsp.DspParameters
import com.radiosuite.pro.model.BroadcastProfile
import com.radiosuite.pro.model.CodecType
import com.radiosuite.pro.service.PlaybackSnapshot
import com.radiosuite.pro.service.MixLevels
import com.radiosuite.pro.service.RadioEngineService
import com.radiosuite.pro.service.SavedPlaylistSummary
import com.radiosuite.pro.service.StreamSnapshot
import com.radiosuite.pro.stream.BroadcastProfileStore
import com.radiosuite.pro.ui.theme.RadioSuiteProTheme
import kotlinx.coroutines.flow.collectLatest

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
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var playback by remember(service) { mutableStateOf(PlaybackSnapshot()) }
    var stream by remember(service) { mutableStateOf(StreamSnapshot()) }
    var mixLevels by remember(service) { mutableStateOf(MixLevels()) }
    var playlists by remember(service) { mutableStateOf(emptyList<SavedPlaylistSummary>()) }

    LaunchedEffect(service) { service?.playback?.collectLatest { playback = it } }
    LaunchedEffect(service) { service?.streamStatus?.collectLatest { stream = it } }
    LaunchedEffect(service) { service?.mixLevels?.collectLatest { mixLevels = it } }
    LaunchedEffect(service) { service?.savedPlaylists?.collectLatest { playlists = it } }

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
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Estudio", "DSP", "Emisión").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Text("0${index + 1}", fontFamily = FontFamily.Monospace) },
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
                0 -> StudioScreen(service, meters, stream, mixLevels)
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
            )
        }
    }
}

@Composable
private fun ConsoleHeader(ready: Boolean, stream: StreamSnapshot, darkTheme: Boolean, onToggleTheme: () -> Unit) {
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
            Surface(Modifier.size(8.dp), shape = CircleShape, color = if (stream.isConnected) LiveGreen else MaterialTheme.colorScheme.primary) {}
            Spacer(Modifier.width(8.dp))
            Text(if (stream.isConnected) "AL AIRE" else if (stream.isStreaming) "CONECTANDO" else if (ready) "MOTOR LISTO" else "INICIANDO", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            FilledTonalButton(
                onClick = onToggleTheme,
                shape = CircleShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(if (darkTheme) "☼  CLARO" else "◐  OSCURO", fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).clickable(onClick = onExpand), verticalAlignment = Alignment.CenterVertically) {
                RadioArtwork(Modifier.size(42.dp))
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
) {
    var showingPlaylists by rememberSaveable { mutableStateOf(false) }
    var newPlaylistName by rememberSaveable { mutableStateOf("") }
    var addMessage by rememberSaveable { mutableStateOf("") }
    var seekPosition by remember(playback.currentIndex) { mutableFloatStateOf(playback.positionMs.toFloat()) }
    var contextMenuIndex by remember { mutableIntStateOf(-1) }
    var playlistPickerIndex by remember { mutableIntStateOf(-1) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("REPRODUCIENDO", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Tu sesión", style = MaterialTheme.typography.titleLarge)
            }
            Text("${playback.queueSize} EN COLA", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        RadioArtwork(Modifier.fillMaxWidth().aspectRatio(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(playback.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(3.dp))
                Text("BIBLIOTECA LOCAL · RADIOSUITE", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
            Text("●", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp)
        }

        Column {
            Slider(
                value = seekPosition.coerceIn(0f, playback.durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = { seekPosition = it },
                valueRange = 0f..playback.durationMs.coerceAtLeast(1L).toFloat(),
                onValueChangeFinished = { service?.seekTo(seekPosition.toLong()) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel(playback.positionMs), color = MutedText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(timeLabel(playback.durationMs), color = MutedText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { service?.toggleShuffle() }) {
                Text(if (playback.shuffleEnabled) "⤨ ACTIVO" else "⤨", color = if (playback.shuffleEnabled) MaterialTheme.colorScheme.primary else MutedText)
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
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> "↻ ALL"
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> "↻ 1"
                    else -> "↻"
                }
                Text(repeatLabel, color = if (playback.repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) MutedText else MaterialTheme.colorScheme.primary)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !showingPlaylists, onClick = { showingPlaylists = false }, label = { Text("COLA · ${playback.queueSize}") })
            FilterChip(selected = showingPlaylists, onClick = { showingPlaylists = true }, label = { Text("PLAYLISTS") })
            Spacer(Modifier.weight(1f))
            AddTracksButton(service, Modifier) { count ->
                addMessage = when {
                    count > 0 -> "Se añadieron $count pistas a la cola."
                    count == 0 -> "No se añadieron pistas. Comprueba el servicio y el formato del archivo."
                    else -> "El reproductor no está listo; vuelve a intentarlo."
                }
            }
        }
        if (addMessage.isNotBlank()) Text(addMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)

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
                Text("Crea una playlist desde la cola actual.", color = MutedText, style = MaterialTheme.typography.bodySmall)
            } else {
                playlists.forEach { playlist ->
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
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
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { service?.playQueueItem(index) },
                                onLongClick = { contextMenuIndex = index },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("%02d".format(index + 1), modifier = Modifier.width(34.dp), color = if (index == playback.currentIndex) MaterialTheme.colorScheme.primary else MutedText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text(title, modifier = Modifier.weight(1f), maxLines = 1, fontWeight = if (index == playback.currentIndex) FontWeight.Bold else FontWeight.Normal)
                        Text("⋮", color = MutedText, fontSize = 20.sp)
                    }
                    DropdownMenu(
                        expanded = contextMenuIndex == index,
                        onDismissRequest = { contextMenuIndex = -1 },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Reproducir a continuación") },
                            onClick = {
                                service?.playQueueItemNext(index)
                                contextMenuIndex = -1
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Añadir a playlist") },
                            enabled = playlists.isNotEmpty(),
                            onClick = {
                                playlistPickerIndex = index
                                contextMenuIndex = -1
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Eliminar de la cola", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                service?.removeQueueItem(index)
                                contextMenuIndex = -1
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
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
                            Text("${playlist.name}  ·  ${playlist.trackCount} pistas", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { playlistPickerIndex = -1 }) { Text("CERRAR") }
            },
        )
    }
}

@Composable
private fun RadioArtwork(modifier: Modifier = Modifier) {
    Canvas(
        modifier.clip(RoundedCornerShape(12.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF34462F), Color(0xFF101715), Color(0xFF202E25))),
        ),
    ) {
        val center = this.center
        val unit = size.minDimension
        for (ring in 1..4) {
            val radius = unit * (0.13f + ring * 0.105f)
            drawArc(
                color = if (ring % 2 == 0) Color(0xFF78965A) else Color(0xFFB9EF62),
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = unit * 0.012f, cap = StrokeCap.Round),
            )
        }
        drawCircle(Color(0xFFB9EF62), radius = unit * 0.105f, center = center)
        val playMark = Path().apply {
            moveTo(center.x - unit * 0.025f, center.y - unit * 0.042f)
            lineTo(center.x + unit * 0.045f, center.y)
            lineTo(center.x - unit * 0.025f, center.y + unit * 0.042f)
            close()
        }
        drawPath(playMark, color = Color(0xFF101715))
    }
}

@Composable
private fun StudioScreen(service: RadioEngineService?, meters: MeterSnapshot, stream: StreamSnapshot, mixLevels: MixLevels) {
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
    SectionHeading("EN VIVO", "Controles de mezcla")
    Text(
        if (stream.isStreaming) "CAPTURA ACTIVA · AUDIO EN PROCESAMIENTO" else "FUENTES LISTAS · INICIA UNA EMISIÓN PARA CAPTURAR MICRÓFONO",
        color = if (stream.isStreaming) MaterialTheme.colorScheme.primary else MutedText,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(10.dp))
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(330.dp)) {
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

@Composable
private fun LiveFader(
    label: String,
    gainDb: Float,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onGainChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
) {
    val stateColor = if (muted) Color(0xFFFFA66B) else Color(0xFFB9EF62)
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
            color = if (muted) Color(0xFF3B281F) else Color(0xFF26351D),
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
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
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
private fun AddTracksButton(service: RadioEngineService?, modifier: Modifier = Modifier, onResult: (Int) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) {
            onResult(0)
        } else {
            val tracks = uris.map { uri ->
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
                uri to displayName(context, uri)
            }
            onResult(service?.enqueueTracks(tracks) ?: -1)
        }
    }
    Button(
        onClick = { picker.launch(arrayOf("audio/*")) },
        modifier = modifier,
        enabled = service != null,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Text("＋  AÑADIR CANCIONES", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
    }
}

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
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
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
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
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
                Text(if (stream.isConnected) "Conectado · ${stream.endpoint}" else "Intentando conectar · ${stream.endpoint}", color = if (stream.isConnected) LiveGreen else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
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
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(eyebrow, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun HorizontalRule() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) return it.getString(0) ?: "Archivo de audio"
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Archivo de audio"
}

private fun dbLabel(value: Float): String = if (value <= -119f) "−∞ dB" else "%.1f dB".format(value)

private fun timeLabel(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private val LiveGreen = Color(0xFFB9EF62)
private val MutedText = Color(0xFF87918B)