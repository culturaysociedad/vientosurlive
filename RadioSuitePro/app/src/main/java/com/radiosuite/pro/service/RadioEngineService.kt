package com.radiosuite.pro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.radiosuite.pro.dsp.DspEngine
import com.radiosuite.pro.dsp.DspParameters
import com.radiosuite.pro.model.AudioFormat as TrackAudioFormat
import com.radiosuite.pro.model.Track
import com.radiosuite.pro.player.EnginePcmProcessor
import com.radiosuite.pro.player.PlaylistManager
import com.radiosuite.pro.stream.IcecastClient
import com.radiosuite.pro.stream.StreamCodec
import com.radiosuite.pro.stream.StreamEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackSnapshot(
    val title: String = "Sin pista",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueSize: Int = 0,
    val queue: List<String> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

data class SavedPlaylistSummary(val name: String, val trackCount: Int)

data class MixLevels(
    val playerGainDb: Float = 0f,
    val microphoneGainDb: Float = 0f,
    val playerMuted: Boolean = false,
    val microphoneMuted: Boolean = false,
)

private data class StoredTrack(val uri: String, val title: String)

data class StreamSnapshot(
    val isStreaming: Boolean = false,
    val isConnected: Boolean = false,
    val endpoint: String = "",
    val error: String = "",
)

/**
 * RadioEngineService
 * ------------------
 * Foreground Service que orquesta los tres motores de la aplicación:
 *
 *   1. Reproductor / PlaylistManager — decodifica pistas y alimenta PCM al mezclador.
 *   2. Motor DSP (C++/NDK)           — pipeline de procesamiento de audio en tiempo real.
 *   3. Streamer (IcecastClient + StreamEncoder) — codifica y emite al servidor.
 *
 * Se ejecuta como foreground service con tipo "mediaPlayback" + "microphone"
 * para garantizar operación 24/7 sin que el sistema mate el proceso cuando la
 * app pasa a segundo plano o la pantalla se apaga.
 *
 * La UI (Jetpack Compose) se comunica con el servicio vía un binder expuesto
 * (RadioEngineBinder) que da acceso a [engine] (DSP), [playlist] y [streamer].
 */
class RadioEngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var meterJob: Job? = null

    // Motores. Inicializados en onCreate y liberados en onDestroy.
    lateinit var engine: DspEngine
        private set
    lateinit var playlist: PlaylistManager
        private set
    private lateinit var player: ExoPlayer
    private val _playback = MutableStateFlow(PlaybackSnapshot())
    val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()
    private val _savedPlaylists = MutableStateFlow<List<SavedPlaylistSummary>>(emptyList())
    val savedPlaylists: StateFlow<List<SavedPlaylistSummary>> = _savedPlaylists.asStateFlow()
    private val _mixLevels = MutableStateFlow(MixLevels())
    val mixLevels: StateFlow<MixLevels> = _mixLevels.asStateFlow()
    private val storedPlaylists = linkedMapOf<String, List<StoredTrack>>()
    private val _streamStatus = MutableStateFlow(StreamSnapshot())
    val streamStatus: StateFlow<StreamSnapshot> = _streamStatus.asStateFlow()
    private var streamMonitorJob: Job? = null
    private var streamEncoder: StreamEncoder? = null
    private var icecast: IcecastClient? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var captureActive = false

    val isStreaming: Boolean get() = streamEncoder != null
    val isConnected: Boolean get() = icecast?.isConnected() == true

    private val binder = RadioEngineBinder()

    inner class RadioEngineBinder : android.os.Binder() {
        val service: RadioEngineService get() = this@RadioEngineService
    }

    override fun onCreate() {
        super.onCreate()
        engine = DspEngine().also { it.start() }
        val mixPreferences = getSharedPreferences(MIX_PREFS, Context.MODE_PRIVATE)
        _mixLevels.value = MixLevels(
            playerGainDb = mixPreferences.getFloat(PLAYER_GAIN_KEY, 0f),
            microphoneGainDb = mixPreferences.getFloat(MIC_GAIN_KEY, 0f),
            playerMuted = mixPreferences.getBoolean(PLAYER_MUTED_KEY, false),
            microphoneMuted = mixPreferences.getBoolean(MIC_MUTED_KEY, false),
        )
        engine.setSourceGain(0, if (_mixLevels.value.playerMuted) MUTED_GAIN_DB else _mixLevels.value.playerGainDb)
        engine.setSourceGain(2, if (_mixLevels.value.microphoneMuted) MUTED_GAIN_DB else _mixLevels.value.microphoneGainDb)
        playlist = PlaylistManager()
        restorePlaylists()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(EnginePcmProcessor(engine)))
                .build()
        }
        player = ExoPlayer.Builder(this, renderersFactory).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = publishPlayback()
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publishPlayback()
                override fun onPlaybackStateChanged(playbackState: Int) = publishPlayback()
            })
        }
        scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                publishPlayback()
                delay(250)
            }
        }
        Log.i(TAG, "Motores DSP + reproductor inicializados")
    }

    fun enqueueTracks(tracks: List<Pair<Uri, String>>): Int {
        if (tracks.isEmpty() || !::player.isInitialized) return 0
        val mediaItems = tracks.map { (uri, title) ->
            val format = audioFormat(title)
            playlist.enqueue(
                Track(uri.toString(), title, "Local", uri.toString(), 0L, format),
            )
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        }
        return try {
            player.addMediaItems(mediaItems)
            syncPlaylist()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            publishPlayback()
            mediaItems.size
        } catch (error: Exception) {
            Log.e(TAG, "No se pudieron añadir las pistas", error)
            0
        }
    }

    fun togglePlayback() {
        if (player.mediaItemCount == 0) return
        if (player.isPlaying) player.pause() else player.play()
        publishPlayback()
    }

    fun setPlayerGain(gainDb: Float) {
        val level = gainDb.coerceIn(-60f, 6f)
        engine.setSourceGain(0, if (_mixLevels.value.playerMuted) MUTED_GAIN_DB else level)
        _mixLevels.value = _mixLevels.value.copy(playerGainDb = level)
        getSharedPreferences(MIX_PREFS, Context.MODE_PRIVATE).edit().putFloat(PLAYER_GAIN_KEY, level).apply()
    }

    fun setMicrophoneGain(gainDb: Float) {
        val level = gainDb.coerceIn(-60f, 6f)
        engine.setSourceGain(2, if (_mixLevels.value.microphoneMuted) MUTED_GAIN_DB else level)
        _mixLevels.value = _mixLevels.value.copy(microphoneGainDb = level)
        getSharedPreferences(MIX_PREFS, Context.MODE_PRIVATE).edit().putFloat(MIC_GAIN_KEY, level).apply()
    }

    fun togglePlayerMute() {
        val muted = !_mixLevels.value.playerMuted
        _mixLevels.value = _mixLevels.value.copy(playerMuted = muted)
        engine.setSourceGain(0, if (muted) MUTED_GAIN_DB else _mixLevels.value.playerGainDb)
        getSharedPreferences(MIX_PREFS, Context.MODE_PRIVATE).edit().putBoolean(PLAYER_MUTED_KEY, muted).apply()
    }

    fun toggleMicrophoneMute() {
        val muted = !_mixLevels.value.microphoneMuted
        _mixLevels.value = _mixLevels.value.copy(microphoneMuted = muted)
        engine.setSourceGain(2, if (muted) MUTED_GAIN_DB else _mixLevels.value.microphoneGainDb)
        getSharedPreferences(MIX_PREFS, Context.MODE_PRIVATE).edit().putBoolean(MIC_MUTED_KEY, muted).apply()
    }

    fun skipToNext() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        if (player.currentPosition > 3_000L || !player.hasPreviousMediaItem()) {
            player.seekTo(0L)
        } else {
            player.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
        publishPlayback()
    }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        publishPlayback()
    }

    fun cycleRepeatMode() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        publishPlayback()
    }

    fun playQueueItem(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        player.play()
    }

    fun removeQueueItem(index: Int) {
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
        syncPlaylist()
        publishPlayback()
    }

    fun playQueueItemNext(index: Int) {
        if (index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex) return
        val mediaItem = player.getMediaItemAt(index)
        player.removeMediaItem(index)
        val insertionIndex = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItem(insertionIndex, mediaItem)
        syncPlaylist()
        publishPlayback()
    }

    fun addQueueItemToPlaylist(queueIndex: Int, playlistName: String): Boolean {
        if (queueIndex !in 0 until player.mediaItemCount) return false
        val tracks = storedPlaylists[playlistName] ?: return false
        val item = player.getMediaItemAt(queueIndex)
        val uri = item.localConfiguration?.uri ?: return false
        val title = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment.orEmpty()
        storedPlaylists[playlistName] = tracks + StoredTrack(uri.toString(), title)
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    fun createPlaylist(name: String): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty() || player.mediaItemCount == 0 ||
            storedPlaylists.keys.any { it.equals(normalizedName, ignoreCase = true) }
        ) return false

        val tracks = (0 until player.mediaItemCount).mapNotNull { index ->
            val item = player.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri ?: return@mapNotNull null
            StoredTrack(uri.toString(), item.mediaMetadata.title?.toString() ?: uri.lastPathSegment.orEmpty())
        }
        if (tracks.isEmpty()) return false
        storedPlaylists[normalizedName] = tracks
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    fun loadPlaylist(name: String) {
        val tracks = storedPlaylists[name] ?: return
        player.setMediaItems(tracks.map { track ->
            MediaItem.Builder()
                .setUri(Uri.parse(track.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).build())
                .build()
        })
        syncPlaylist()
        player.prepare()
        player.play()
        publishPlayback()
    }

    fun appendPlaylist(name: String) {
        val tracks = storedPlaylists[name] ?: return
        player.addMediaItems(tracks.map { track ->
            MediaItem.Builder()
                .setUri(Uri.parse(track.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).build())
                .build()
        })
        syncPlaylist()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (!player.isPlaying) player.play()
        publishPlayback()
    }

    fun clearPlaylist() {
        player.clearMediaItems()
        playlist.setPlaylist(emptyList())
        publishPlayback()
    }

    private fun publishPlayback() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem
        _playback.value = PlaybackSnapshot(
            title = item?.mediaMetadata?.title?.toString() ?: "Sin pista",
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            queueSize = player.mediaItemCount,
            queue = (0 until player.mediaItemCount).mapNotNull { index ->
                player.getMediaItemAt(index).mediaMetadata.title?.toString()
            },
            currentIndex = player.currentMediaItemIndex,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    private fun syncPlaylist() {
        playlist.setPlaylist((0 until player.mediaItemCount).mapNotNull { index ->
            val item = player.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri ?: return@mapNotNull null
            val title = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment.orEmpty()
            Track(uri.toString(), title, "Local", uri.toString(), 0L, audioFormat(title))
        })
    }

    private fun restorePlaylists() {
        val saved = getSharedPreferences(PLAYLIST_PREFS, Context.MODE_PRIVATE)
            .getString(PLAYLISTS_JSON, "[]") ?: "[]"
        try {
            val array = JSONArray(saved)
            for (index in 0 until array.length()) {
                val playlistObject = array.getJSONObject(index)
                val tracksJson = playlistObject.getJSONArray("tracks")
                val tracks = (0 until tracksJson.length()).map { trackIndex ->
                    val track = tracksJson.getJSONObject(trackIndex)
                    StoredTrack(track.getString("uri"), track.getString("title"))
                }
                storedPlaylists[playlistObject.getString("name")] = tracks
            }
        } catch (error: Exception) {
            Log.w(TAG, "No se pudieron cargar las playlists guardadas", error)
        }
        publishSavedPlaylists()
    }

    private fun persistPlaylists() {
        val array = JSONArray()
        storedPlaylists.forEach { (name, tracks) ->
            val trackArray = JSONArray()
            tracks.forEach { track ->
                trackArray.put(JSONObject().put("uri", track.uri).put("title", track.title))
            }
            array.put(JSONObject().put("name", name).put("tracks", trackArray))
        }
        getSharedPreferences(PLAYLIST_PREFS, Context.MODE_PRIVATE)
            .edit().putString(PLAYLISTS_JSON, array.toString()).apply()
    }

    private fun publishSavedPlaylists() {
        _savedPlaylists.value = storedPlaylists.map { (name, tracks) -> SavedPlaylistSummary(name, tracks.size) }
    }

    private fun audioFormat(title: String): TrackAudioFormat = when (title.substringAfterLast('.', "").lowercase()) {
        "ogg" -> TrackAudioFormat.Ogg
        "opus" -> TrackAudioFormat.Opus
        "wav" -> TrackAudioFormat.Wav
        "aac", "m4a" -> TrackAudioFormat.Aac
        "flac" -> TrackAudioFormat.Flac
        else -> TrackAudioFormat.Mp3
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAM -> startBroadcast(
                host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
                port = intent.getIntExtra(EXTRA_PORT, 8000),
                mount = intent.getStringExtra(EXTRA_MOUNT) ?: "/live",
                password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                bitrate = intent.getIntExtra(EXTRA_BITRATE, 128),
            )
            ACTION_STOP_STREAM  -> stopStreaming()
            ACTION_UPDATE_DSP   -> applyDspParameters(intent)
        }
        return START_NOT_STICKY
    }

    // ---------- Streaming ----------
    fun startBroadcast(host: String, port: Int, mount: String, password: String, bitrate: Int): Boolean {
        if (streamEncoder != null || host.isBlank() || port !in 1..65535 || mount.isBlank()) return false
        startForegroundCompat(includeMicrophone = true)

        val recorder = createAudioRecord() ?: run {
            stopForegroundCompat()
            stopSelf()
            return false
        }
        val client = IcecastClient(host, port, mount, password, StreamCodec.Mp3, bitrate)
        val encoder = StreamEncoder(engine.nativeHandle, client::sendPacket)

        return try {
            recorder.startRecording()
            audioRecord = recorder
            icecast = client
            streamEncoder = encoder
            client.start()
            if (!encoder.start(StreamCodec.Mp3, SAMPLE_RATE, CHANNELS, bitrate)) {
                stopStreaming()
                false
            } else {
                captureActive = true
                captureThread = Thread({ captureMicrophone(recorder) }, "RadioMicCapture").apply { start() }
                streamMonitorJob?.cancel()
                streamMonitorJob = scope.launch {
                    while (isActive && streamEncoder != null) {
                        publishStreamStatus(host, port, mount)
                        delay(250)
                    }
                }
                publishStreamStatus(host, port, mount)
                Log.i(TAG, "Emisión iniciada hacia ${host}:${port}${mount} (MP3, ${bitrate}kbps)")
                true
            }
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo iniciar la emisión", error)
            stopStreaming()
            false
        }
    }

    fun stopStreaming() {
        captureActive = false
        audioRecord?.stopSafely()
        captureThread?.interrupt()
        if (captureThread !== Thread.currentThread()) captureThread?.join(500)
        captureThread = null
        audioRecord?.release()
        audioRecord = null
        streamEncoder?.stop()
        icecast?.stop()
        streamEncoder = null
        icecast = null
        streamMonitorJob?.cancel()
        streamMonitorJob = null
        _streamStatus.value = StreamSnapshot()
        stopForegroundCompat()
        stopSelf()
        Log.i(TAG, "Streaming detenido")
    }

    private fun publishStreamStatus(host: String, port: Int, mount: String) {
        _streamStatus.value = StreamSnapshot(
            isStreaming = streamEncoder != null,
            isConnected = icecast?.isConnected() == true,
            endpoint = "$host:$port$mount",
            error = icecast?.lastError.orEmpty(),
        )
    }

    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            PlatformAudioFormat.CHANNEL_IN_MONO,
            PlatformAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                PlatformAudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(PlatformAudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(PlatformAudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(4096))
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        return recorder
    }

    private fun captureMicrophone(recorder: AudioRecord) {
        val pcm = ShortArray(CAPTURE_BUFFER_SAMPLES)
        val samples = FloatArray(CAPTURE_BUFFER_SAMPLES)
        while (captureActive && !Thread.currentThread().isInterrupted) {
            val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            if (count <= 0) continue
            for (index in 0 until count) samples[index] = pcm[index] / 32768f
            engine.pushInputSamples(samples.copyOf(count))
        }
    }

    private fun AudioRecord.stopSafely() {
        if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try { stop() } catch (_: IllegalStateException) { }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ---------- DSP ----------
    private fun applyDspParameters(intent: Intent) {
        val p = DspParameters(
            enabled = intent.getBooleanExtra(EXTRA_DSP_ENABLED, true),
            agcTargetDb = intent.getFloatExtra(EXTRA_AGC_TARGET, -18f),
            agcMaxGainDb = intent.getFloatExtra(EXTRA_AGC_MAX_GAIN, 24f),
            agcAttackMs = intent.getFloatExtra(EXTRA_AGC_ATTACK, 150f),
            agcReleaseMs = intent.getFloatExtra(EXTRA_AGC_RELEASE, 800f),
            outputGainDb = intent.getFloatExtra(EXTRA_OUT_GAIN, 0f),
        )
        engine.updatePipeline(p)
    }

    // ---------- Métricas para vómetros ----------
    fun startMeterPolling(onTick: (Float, Float, Float, Float) -> Unit) {
        meterJob?.cancel()
        meterJob = scope.launch {
            while (isActive) {
                onTick(
                    engine.inputPeakDb(),
                    engine.outputPeakDb(),
                    engine.outputRmsDb(),
                    engine.agcGainDb(),
                )
                delay(METER_INTERVAL_MS)
            }
        }
    }

    fun stopMeterPolling() { meterJob?.cancel(); meterJob = null }

    // ---------- Foreground ----------
    private fun startForegroundCompat(includeMicrophone: Boolean) {
        val channelId = CHANNEL_ID
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                channelId, "Radio Engine", NotificationManager.IMPORTANCE_LOW
            ))
        }
        val pi = PendingIntent.getService(
            this, 0, Intent(), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("RadioSuite Pro")
            .setContentText("Emisión en curso")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (includeMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIF_ID, notif,
                serviceTypes)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        stopMeterPolling()
        stopStreaming()
        engine.stop()
        engine.release()
        player.release()
        scope.cancel()
        Log.i(TAG, "Servicio destruido y motores liberados")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "RadioEngineService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "radio_engine"
        private const val METER_INTERVAL_MS = 33L // ~30 fps para vómetros fluidos
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val CAPTURE_BUFFER_SAMPLES = 1024
        private const val PLAYLIST_PREFS = "radio_playlists"
        private const val PLAYLISTS_JSON = "saved_playlists"
        private const val MIX_PREFS = "radio_mix"
        private const val PLAYER_GAIN_KEY = "player_gain_db"
        private const val MIC_GAIN_KEY = "mic_gain_db"
        private const val PLAYER_MUTED_KEY = "player_muted"
        private const val MIC_MUTED_KEY = "mic_muted"
        private const val MUTED_GAIN_DB = -60f

        const val ACTION_START_STREAM = "com.radiosuite.pro.START_STREAM"
        const val ACTION_STOP_STREAM  = "com.radiosuite.pro.STOP_STREAM"
        const val ACTION_UPDATE_DSP   = "com.radiosuite.pro.UPDATE_DSP"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_MOUNT = "mount"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_SAMPLE_RATE = "sampleRate"
        const val EXTRA_CHANNELS = "channels"
        const val EXTRA_BITRATE = "bitrate"

        const val EXTRA_DSP_ENABLED = "dspEnabled"
        const val EXTRA_AGC_TARGET = "agcTarget"
        const val EXTRA_AGC_MAX_GAIN = "agcMaxGain"
        const val EXTRA_AGC_ATTACK = "agcAttack"
        const val EXTRA_AGC_RELEASE = "agcRelease"
        const val EXTRA_OUT_GAIN = "outGain"

        fun start(context: Context) {
            val intent = Intent(context, RadioEngineService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private object ContextCompat {
    fun startForegroundService(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
