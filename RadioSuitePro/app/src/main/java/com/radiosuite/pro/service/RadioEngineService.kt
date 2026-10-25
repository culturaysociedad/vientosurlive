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
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.radiosuite.pro.dsp.DspEngine
import com.radiosuite.pro.dsp.DspParameters
import com.radiosuite.pro.model.AudioFormat as TrackAudioFormat
import com.radiosuite.pro.model.Track
import com.radiosuite.pro.player.EnginePcmProcessor
import com.radiosuite.pro.player.ClockAnnouncementDataSourceFactory
import com.radiosuite.pro.player.ClockAnnouncementSynthesizer
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

data class PlaybackSnapshot(
    val title: String = "Sin pista",
    val artist: String = "",
    val album: String = "",
    val artworkData: ByteArray? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueSize: Int = 0,
    val queue: List<String> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val playbackSpeed: Float = 1f,
    val isFavorite: Boolean = false,
    val errorMessage: String = "",
)

data class SavedPlaylistTrack(
    val uri: String,
    val title: String,
    val isFolder: Boolean = false,
    val folderTrackCount: Int = 0,
    val isClockAnnouncement: Boolean = false,
)

data class SavedPlaylistSummary(
    val name: String,
    val trackCount: Int,
    val tracks: List<SavedPlaylistTrack> = emptyList(),
)

data class FavoriteTrack(val uri: String, val title: String)

data class SwiperSnapshot(
    val isConfigured: Boolean = false,
    val folderName: String = "",
    val trackCount: Int = 0,
)

data class ClockAudioFolderSnapshot(
    val isConfigured: Boolean = false,
    val folderName: String = "",
    val hasPhrase: Boolean = false,
    val configuredHours: List<Int> = emptyList(),
    val configuredMinutes: List<Int> = emptyList(),
)

data class MixLevels(
    val playerGainDb: Float = 0f,
    val microphoneGainDb: Float = 0f,
    val playerMuted: Boolean = false,
    val microphoneMuted: Boolean = false,
)

private data class StoredTrack(val uri: String, val title: String)

private sealed interface StoredPlaylistEntry {
    data class Track(val uri: String, val title: String) : StoredPlaylistEntry
    data class Folder(val treeUri: String, val name: String, val tracks: List<StoredTrack>) : StoredPlaylistEntry
    data object ClockAnnouncement : StoredPlaylistEntry
}

private data class QueueFolderEntry(
    val treeUri: String,
    val name: String,
    val tracks: List<StoredTrack>,
)

data class StreamSnapshot(
    val isStreaming: Boolean = false,
    val isConnected: Boolean = false,
    val endpoint: String = "",
    val error: String = "",
    val activeListeners: Int? = null,
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
    private lateinit var clockSynthesizer: ClockAnnouncementSynthesizer
    private var clockAudioFolderName = ""
    private var clockPhraseAudio: Uri? = null
    private val clockHourAudioFiles = mutableMapOf<Int, Uri>()
    private val clockMinuteAudioFiles = mutableMapOf<Int, Uri>()
    private var playbackErrorMessage = ""
    private var eventForegroundActive = false
    private val _playback = MutableStateFlow(PlaybackSnapshot())
    val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()
    private val _savedPlaylists = MutableStateFlow<List<SavedPlaylistSummary>>(emptyList())
    val savedPlaylists: StateFlow<List<SavedPlaylistSummary>> = _savedPlaylists.asStateFlow()
    private val _favoriteTracks = MutableStateFlow<List<FavoriteTrack>>(emptyList())
    val favoriteTracks: StateFlow<List<FavoriteTrack>> = _favoriteTracks.asStateFlow()
    private val _swiperStatus = MutableStateFlow(SwiperSnapshot())
    val swiperStatus: StateFlow<SwiperSnapshot> = _swiperStatus.asStateFlow()
    private val _clockAudioFolderStatus = MutableStateFlow(ClockAudioFolderSnapshot())
    val clockAudioFolderStatus: StateFlow<ClockAudioFolderSnapshot> = _clockAudioFolderStatus.asStateFlow()
    private val _scheduledEvents = MutableStateFlow<List<ScheduledRadioEvent>>(emptyList())
    val scheduledEvents: StateFlow<List<ScheduledRadioEvent>> = _scheduledEvents.asStateFlow()
    private val _mixLevels = MutableStateFlow(MixLevels())
    val mixLevels: StateFlow<MixLevels> = _mixLevels.asStateFlow()
    private val storedPlaylists = linkedMapOf<String, List<StoredPlaylistEntry>>()
    private val storedFavorites = linkedMapOf<String, FavoriteTrack>()
    private val queueFolders = mutableMapOf<String, QueueFolderEntry>()
    private val swiperQueueEntries = mutableMapOf<String, String>()
    private val playedFolderEntries = mutableSetOf<String>()
    private var lastQueueMediaId: String? = null
    private var swiperFolder: QueueFolderEntry? = null
    private var musicEntriesSinceSwiper = 0
    private val _streamStatus = MutableStateFlow(StreamSnapshot())
    val streamStatus: StateFlow<StreamSnapshot> = _streamStatus.asStateFlow()
    private var streamMonitorJob: Job? = null
    private var listenerCountJob: Job? = null
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
        engine = DspEngine()
        if (!engine.start()) {
            Log.e(TAG, "El motor de audio no pudo iniciar; se bloquearán las emisiones hasta que esté disponible")
        }
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
        restoreFavorites()
        restoreSwiperFolder()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(EnginePcmProcessor(engine)))
                .build()
        }
        clockSynthesizer = ClockAnnouncementSynthesizer(this)
        restoreClockAudioFolder()
        _scheduledEvents.value = RadioEventScheduler.load(this)
        RadioEventScheduler.restore(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(ClockAnnouncementDataSourceFactory(this, clockSynthesizer))
        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = publishPlayback()
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val mediaId = mediaItem?.mediaId.orEmpty()
                    if (mediaId.startsWith(RADIO_EVENT_MEDIA_ID_PREFIX)) {
                        playbackErrorMessage = ""
                        publishPlayback()
                        return
                    }
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
                        player.repeatMode == Player.REPEAT_MODE_ONE &&
                        mediaId.startsWith(CLOCK_SEGMENT_MEDIA_ID_PREFIX) &&
                        mediaId.endsWith(":minute")
                    ) {
                        val index = player.currentMediaItemIndex
                        if (index >= 2) player.seekTo(index - 2, 0L)
                        return
                    }
                    if (mediaItem != null && expandClockAnnouncement(mediaItem)) return
                    playbackErrorMessage = ""
                    rotateFolderEntryOnTransition(mediaId, reason)
                    publishPlayback()
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackErrorMessage = error.message ?: "No se pudo reproducir este elemento."
                    Log.e(TAG, "Error de reproducción", error)
                    publishPlayback()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        player.currentMediaItem?.mediaId?.takeIf(queueFolders::containsKey)?.let { folderId ->
                            refreshFolderEntry(folderId)
                            player.prepare()
                            player.play()
                        }
                        if (eventForegroundActive && !isStreaming) {
                            eventForegroundActive = false
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }
                    publishPlayback()
                }
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

    fun enqueueClockAnnouncement(): Boolean {
        if (!::player.isInitialized) return false
        return try {
            playbackErrorMessage = ""
            player.addMediaItem(createClockAnnouncementMediaItem())
            syncPlaylist()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            publishPlayback()
            true
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo añadir la locución de hora a la cola", error)
            false
        }
    }

    fun enqueueRandomFolder(treeUri: Uri, folderName: String, tracks: List<Pair<Uri, String>>): Boolean {
        if (tracks.isEmpty() || !::player.isInitialized) return false
        val entry = QueueFolderEntry(
            treeUri = treeUri.toString(),
            name = folderName,
            tracks = tracks.map { (uri, title) -> StoredTrack(uri.toString(), title) },
        )
        val mediaItem = createFolderMediaItem(entry) ?: return false
        player.addMediaItem(mediaItem)
        syncPlaylist()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        publishPlayback()
        return true
    }

    fun configureSwiperFolder(treeUri: Uri, folderName: String, tracks: List<Pair<Uri, String>>): Boolean {
        if (tracks.isEmpty()) return false
        swiperFolder = QueueFolderEntry(
            treeUri = treeUri.toString(),
            name = folderName,
            tracks = tracks.map { (uri, title) -> StoredTrack(uri.toString(), title) },
        )
        musicEntriesSinceSwiper = 0
        persistSwiperFolder()
        publishSwiperStatus()
        return true
    }

    fun clearSwiperFolder() {
        swiperFolder = null
        musicEntriesSinceSwiper = 0
        persistSwiperFolder()
        publishSwiperStatus()
    }

    fun configureClockAudioFolder(
        folderName: String,
        phraseAudio: Uri?,
        hourAudioFiles: Map<Int, Uri>,
        minuteAudioFiles: Map<Int, Uri>,
    ): Boolean {
        if (phraseAudio == null || hourAudioFiles.isEmpty() || minuteAudioFiles.isEmpty() ||
            hourAudioFiles.keys.any { it !in 0..23 } ||
            minuteAudioFiles.keys.any { it !in 0..59 }
        ) return false
        clockAudioFolderName = folderName
        clockPhraseAudio = phraseAudio
        clockHourAudioFiles.clear()
        clockHourAudioFiles.putAll(hourAudioFiles)
        clockMinuteAudioFiles.clear()
        clockMinuteAudioFiles.putAll(minuteAudioFiles)
        persistClockAudioFolder()
        publishClockAudioFolderStatus()
        return true
    }

    fun clearClockAudioFolder() {
        clockAudioFolderName = ""
        clockPhraseAudio = null
        clockHourAudioFiles.clear()
        clockMinuteAudioFiles.clear()
        getSharedPreferences(CLOCK_AUDIO_PREFS, Context.MODE_PRIVATE)
            .edit().remove(CLOCK_AUDIO_JSON).apply()
        publishClockAudioFolderStatus()
    }

    fun scheduleRadioEvent(
        name: String,
        audioUri: Uri,
        audioTitle: String,
        scheduledAtMillis: Long,
    ): Boolean {
        if (name.isBlank() || audioTitle.isBlank() || scheduledAtMillis <= System.currentTimeMillis()) return false
        return try {
            val event = RadioEventScheduler.newEvent(name.trim(), audioUri.toString(), audioTitle, scheduledAtMillis)
            RadioEventScheduler.schedule(this, event)
            val events = RadioEventScheduler.load(this) + event
            RadioEventScheduler.save(this, events.sortedBy(ScheduledRadioEvent::scheduledAtMillis))
            publishScheduledEvents()
            true
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo programar el evento", error)
            false
        }
    }

    fun cancelRadioEvent(eventId: String) {
        RadioEventScheduler.cancel(this, eventId)
        RadioEventScheduler.save(
            this,
            RadioEventScheduler.load(this).filterNot { it.id == eventId },
        )
        publishScheduledEvents()
    }

    private fun playScheduledEvent(eventId: String) {
        val event = RadioEventScheduler.eventFor(this, eventId) ?: run {
            if (!isStreaming) {
                stopForegroundCompat()
                stopSelf()
            }
            return
        }
        if (System.currentTimeMillis() - event.scheduledAtMillis > EVENT_MISSED_TOLERANCE_MS) {
            cancelRadioEvent(eventId)
            Log.w(TAG, "Se omitió el evento vencido ${event.name}")
            if (!isStreaming) {
                stopForegroundCompat()
                stopSelf()
            }
            return
        }
        cancelRadioEvent(eventId)
        eventForegroundActive = true
        val mediaItem = MediaItem.Builder()
            .setMediaId("$RADIO_EVENT_MEDIA_ID_PREFIX${event.id}")
            .setUri(Uri.parse(event.audioUri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(event.name)
                    .setArtist("Evento programado · ${event.audioTitle}")
                    .build(),
            )
            .build()
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex in 0 until player.mediaItemCount) {
            var insertionIndex = currentIndex + 1
            while (insertionIndex < player.mediaItemCount &&
                player.getMediaItemAt(insertionIndex).mediaId.startsWith(RADIO_EVENT_MEDIA_ID_PREFIX)
            ) insertionIndex++
            player.addMediaItem(insertionIndex, mediaItem)
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekToDefaultPosition(insertionIndex)
                player.prepare()
                player.play()
            }
        } else {
            player.addMediaItem(mediaItem)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        publishPlayback()
    }

    private fun publishScheduledEvents() {
        _scheduledEvents.value = RadioEventScheduler.load(this)
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
        if (index in 0 until player.mediaItemCount) {
            val removed = player.getMediaItemAt(index)
            player.removeMediaItem(index)
            queueFolders.remove(removed.mediaId)
            swiperQueueEntries.remove(removed.mediaId)
            playedFolderEntries.remove(removed.mediaId)
        }
        syncPlaylist()
        publishPlayback()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in 0 until player.mediaItemCount || toIndex !in 0 until player.mediaItemCount) return
        player.moveMediaItem(fromIndex, toIndex)
        syncPlaylist()
        publishPlayback()
    }

    fun clearQueue() {
        clearPlaylist()
    }

    fun setPlaybackSpeed(speed: Float) {
        val level = speed.coerceIn(0.5f, 2f)
        player.setPlaybackSpeed(level)
        publishPlayback()
    }

    fun toggleFavoriteCurrentTrack() {
        val item = player.currentMediaItem ?: return
        if (item.mediaId.startsWith(CLOCK_MEDIA_ID_PREFIX)) return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        if (storedFavorites.remove(uri) == null) {
            val title = item.mediaMetadata.title?.toString() ?: uri.substringAfterLast('/')
            storedFavorites[uri] = FavoriteTrack(uri, title)
        }
        persistFavorites()
        publishFavorites()
        publishPlayback()
    }

    fun removeFavorite(uri: String) {
        if (storedFavorites.remove(uri) == null) return
        persistFavorites()
        publishFavorites()
        publishPlayback()
    }

    fun playFavoriteTrack(uri: String) {
        val track = storedFavorites[uri] ?: return
        clearQueueFolderState()
        player.setMediaItems(listOf(createMediaItem(track.uri, track.title)))
        syncPlaylist()
        player.prepare()
        player.play()
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
        val entry = storedEntryFor(item) ?: return false
        storedPlaylists[playlistName] = tracks + entry
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    private fun storedEntryFor(item: MediaItem): StoredPlaylistEntry? {
        if (item.mediaId.startsWith(CLOCK_MEDIA_ID_PREFIX) ||
            item.mediaId.startsWith(CLOCK_SEGMENT_MEDIA_ID_PREFIX)
        ) {
            return StoredPlaylistEntry.ClockAnnouncement
        }
        queueFolders[item.mediaId]?.let { folder ->
            return StoredPlaylistEntry.Folder(folder.treeUri, folder.name, folder.tracks)
        }
        val uri = item.localConfiguration?.uri ?: return null
        val title = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment.orEmpty()
        return StoredPlaylistEntry.Track(uri.toString(), title)
    }

    fun addFavoriteToPlaylist(uri: String, playlistName: String): Boolean {
        val favorite = storedFavorites[uri] ?: return false
        val tracks = storedPlaylists[playlistName] ?: return false
        storedPlaylists[playlistName] = tracks + StoredPlaylistEntry.Track(favorite.uri, favorite.title)
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    fun removeTrackFromPlaylist(playlistName: String, trackIndex: Int): Boolean {
        val tracks = storedPlaylists[playlistName] ?: return false
        if (trackIndex !in tracks.indices) return false
        storedPlaylists[playlistName] = tracks.toMutableList().also { it.removeAt(trackIndex) }
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    fun moveTrackInPlaylist(playlistName: String, fromIndex: Int, toIndex: Int): Boolean {
        val tracks = storedPlaylists[playlistName] ?: return false
        if (fromIndex !in tracks.indices || toIndex !in tracks.indices || fromIndex == toIndex) return false
        storedPlaylists[playlistName] = tracks.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    fun addTracksToPlaylist(playlistName: String, tracks: List<Pair<Uri, String>>): Int {
        val currentTracks = storedPlaylists[playlistName] ?: return 0
        if (tracks.isEmpty()) return 0
        storedPlaylists[playlistName] = currentTracks + tracks.map { (uri, title) ->
            StoredPlaylistEntry.Track(uri.toString(), title)
        }
        persistPlaylists()
        publishSavedPlaylists()
        return tracks.size
    }

    fun addClockAnnouncementToPlaylist(playlistName: String): Boolean {
        val entries = storedPlaylists[playlistName] ?: return false
        storedPlaylists[playlistName] = entries + StoredPlaylistEntry.ClockAnnouncement
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    fun addFolderToPlaylist(
        playlistName: String,
        treeUri: Uri,
        folderName: String,
        tracks: List<Pair<Uri, String>>,
    ): Boolean {
        val currentEntries = storedPlaylists[playlistName] ?: return false
        if (tracks.isEmpty()) return false
        val folderEntry = QueueFolderEntry(
            treeUri = treeUri.toString(),
            name = folderName,
            tracks = tracks.map { (uri, title) -> StoredTrack(uri.toString(), title) },
        )
        storedPlaylists[playlistName] = currentEntries + StoredPlaylistEntry.Folder(
            folderEntry.treeUri,
            folderEntry.name,
            folderEntry.tracks,
        )
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    fun createPlaylist(name: String): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty() ||
            storedPlaylists.keys.any { it.equals(normalizedName, ignoreCase = true) }
        ) return false

        val entries = storedEntriesForQueue()
        storedPlaylists[normalizedName] = entries
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    private fun storedEntriesForQueue(): List<StoredPlaylistEntry> {
        val entries = mutableListOf<StoredPlaylistEntry>()
        val handledClockGroups = mutableSetOf<String>()
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val mediaId = item.mediaId
            if (mediaId.startsWith(CLOCK_SEGMENT_MEDIA_ID_PREFIX)) {
                val groupId = mediaId.removePrefix(CLOCK_SEGMENT_MEDIA_ID_PREFIX).substringBefore(':')
                if (handledClockGroups.add(groupId)) entries += StoredPlaylistEntry.ClockAnnouncement
            } else {
                storedEntryFor(item)?.let(entries::add)
            }
        }
        return entries
    }

    fun renamePlaylist(name: String, newName: String): Boolean {
        val normalizedName = newName.trim()
        val tracks = storedPlaylists[name] ?: return false
        if (normalizedName.isEmpty() || storedPlaylists.keys.any {
                !it.equals(name, ignoreCase = true) && it.equals(normalizedName, ignoreCase = true)
            }
        ) return false
        storedPlaylists.remove(name)
        storedPlaylists[normalizedName] = tracks
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    fun deletePlaylist(name: String): Boolean {
        if (storedPlaylists.remove(name) == null) return false
        persistPlaylists()
        publishSavedPlaylists()
        return true
    }

    fun loadPlaylist(name: String) {
        val entries = storedPlaylists[name] ?: return
        clearQueueFolderState()
        val mediaItems = createMediaItemsForEntries(entries)
        if (mediaItems.isEmpty()) return
        player.setMediaItems(mediaItems)
        syncPlaylist()
        player.prepare()
        player.play()
        publishPlayback()
    }

    fun playPlaylistRandom(name: String) {
        val entries = storedPlaylists[name] ?: return
        if (entries.isEmpty()) return
        clearQueueFolderState()
        val mediaItems = createMediaItemsForEntries(entries.shuffled())
        if (mediaItems.isEmpty()) return
        player.setMediaItems(mediaItems)
        syncPlaylist()
        player.prepare()
        player.play()
        publishPlayback()
    }

    fun playPlaylistTrack(name: String, index: Int) {
        val entries = storedPlaylists[name] ?: return
        if (index !in entries.indices) return
        clearQueueFolderState()
        val mediaItems = createMediaItemsForEntries(entries)
        if (mediaItems.isEmpty()) return
        player.setMediaItems(mediaItems)
        player.seekToDefaultPosition(index)
        syncPlaylist()
        player.prepare()
        player.play()
        publishPlayback()
    }

    fun appendPlaylist(name: String) {
        val entries = storedPlaylists[name] ?: return
        val mediaItems = createMediaItemsForEntries(entries)
        if (mediaItems.isEmpty()) return
        player.addMediaItems(mediaItems)
        syncPlaylist()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (!player.isPlaying) player.play()
        publishPlayback()
    }

    fun clearPlaylist() {
        player.clearMediaItems()
        clearQueueFolderState()
        playlist.setPlaylist(emptyList())
        publishPlayback()
    }

    private fun publishPlayback() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem
        val metadata = player.mediaMetadata
        _playback.value = PlaybackSnapshot(
            title = metadata.title?.toString() ?: item?.mediaMetadata?.title?.toString() ?: "Sin pista",
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            artworkData = metadata.artworkData,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            queueSize = player.mediaItemCount,
            queue = (0 until player.mediaItemCount).map { index ->
                queueEntryTitle(player.getMediaItemAt(index))
            },
            currentIndex = player.currentMediaItemIndex,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            playbackSpeed = player.playbackParameters.speed,
            isFavorite = item?.localConfiguration?.uri?.toString()?.let(storedFavorites::containsKey) == true,
            errorMessage = playbackErrorMessage,
        )
    }

    private fun createMediaItem(uri: String, title: String): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse(uri))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

    private fun createClockAnnouncementMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("$CLOCK_MEDIA_ID_PREFIX${UUID.randomUUID()}")
        .setUri(Uri.parse("$CLOCK_MEDIA_URI_PREFIX/announcement"))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(CLOCK_ANNOUNCEMENT_TITLE).build())
        .build()

    private fun expandClockAnnouncement(item: MediaItem): Boolean {
        if (!item.mediaId.startsWith(CLOCK_MEDIA_ID_PREFIX)) return false
        val index = player.currentMediaItemIndex
        val phraseUri = clockPhraseAudio ?: return false
        val now = java.time.LocalTime.now()
        val hourUri = clockHourAudioFiles[now.hour] ?: return false
        val minuteUri = clockMinuteAudioFiles[now.minute] ?: return false
        if (index !in 0 until player.mediaItemCount) return false

        val groupId = item.mediaId.removePrefix(CLOCK_MEDIA_ID_PREFIX)
        val segments = listOf(
            Triple("phrase", phraseUri, "$CLOCK_ANNOUNCEMENT_TITLE · frase"),
            Triple("hour", hourUri, "$CLOCK_ANNOUNCEMENT_TITLE · hora"),
            Triple("minute", minuteUri, "$CLOCK_ANNOUNCEMENT_TITLE · minutos"),
        ).map { (segment, uri, title) ->
            MediaItem.Builder()
                .setMediaId("$CLOCK_SEGMENT_MEDIA_ID_PREFIX$groupId:$segment")
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        }
        player.replaceMediaItems(index, index + 1, segments)
        return true
    }

    private fun createMediaItemsForEntries(entries: List<StoredPlaylistEntry>): List<MediaItem> =
        entries.mapNotNull { entry ->
            when (entry) {
                is StoredPlaylistEntry.Track -> createMediaItem(entry.uri, entry.title)
                is StoredPlaylistEntry.Folder -> createFolderMediaItem(
                    QueueFolderEntry(entry.treeUri, entry.name, entry.tracks),
                )
                StoredPlaylistEntry.ClockAnnouncement -> createClockAnnouncementMediaItem()
            }
        }

    private fun createFolderMediaItem(folder: QueueFolderEntry): MediaItem? {
        if (folder.tracks.isEmpty()) return null
        val folderId = "folder:${UUID.randomUUID()}"
        queueFolders[folderId] = folder
        return resolveFolderMediaItem(folderId, folder.tracks.random())
    }

    private fun resolveFolderMediaItem(folderId: String, track: StoredTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(folderId)
            .setUri(Uri.parse(track.uri))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).build())
            .build()

    private fun queueEntryTitle(item: MediaItem): String = queueFolders[item.mediaId]?.let { "Carpeta · ${it.name}" }
        ?: swiperQueueEntries[item.mediaId]?.let { "Swiper · $it" }
        ?: when {
            item.mediaId.startsWith(CLOCK_SEGMENT_MEDIA_ID_PREFIX) ->
                item.mediaMetadata.title?.toString() ?: CLOCK_ANNOUNCEMENT_TITLE
            item.mediaId.startsWith(CLOCK_MEDIA_ID_PREFIX) -> CLOCK_ANNOUNCEMENT_TITLE
            else -> null
        }
        ?: item.mediaMetadata.title?.toString()
        ?: item.localConfiguration?.uri?.lastPathSegment.orEmpty()

    private fun clearQueueFolderState() {
        queueFolders.clear()
        swiperQueueEntries.clear()
        playedFolderEntries.clear()
        lastQueueMediaId = null
        musicEntriesSinceSwiper = 0
    }

    private fun rotateFolderEntryOnTransition(mediaId: String?, reason: Int) {
        val previousId = lastQueueMediaId
        val naturalTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        if (naturalTransition && previousId != null &&
            !previousId.startsWith(SWIPER_MEDIA_ID_PREFIX) &&
            !previousId.startsWith(CLOCK_MEDIA_ID_PREFIX) &&
            !previousId.startsWith(CLOCK_SEGMENT_MEDIA_ID_PREFIX)
        ) {
            musicEntriesSinceSwiper++
        }
        if (previousId != null && queueFolders.containsKey(previousId) &&
            (previousId != mediaId || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        ) {
            playedFolderEntries += previousId
        }
        lastQueueMediaId = mediaId
        if (mediaId != null && playedFolderEntries.remove(mediaId)) refreshFolderEntry(mediaId)
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && mediaId != null && swiperQueueEntries.containsKey(mediaId)) {
            refreshSwiperEntry(mediaId)
        }
        if (naturalTransition && mediaId != null && musicEntriesSinceSwiper >= SWIPER_INTERVAL_TRACKS &&
            !mediaId.startsWith(SWIPER_MEDIA_ID_PREFIX) && swiperFolder != null
        ) {
            insertSwiperBeforeCurrent()
        }
    }

    private fun refreshFolderEntry(folderId: String) {
        val folder = queueFolders[folderId] ?: return
        val index = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount) return
        val previousUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val candidates = folder.tracks.filterNot { it.uri == previousUri }.ifEmpty { folder.tracks }
        player.replaceMediaItem(index, resolveFolderMediaItem(folderId, candidates.random()))
    }

    private fun createSwiperMediaItem(folder: QueueFolderEntry, previousUri: String? = null): MediaItem {
        val candidates = folder.tracks.filterNot { it.uri == previousUri }.ifEmpty { folder.tracks }
        val track = candidates.random()
        val swiperId = "$SWIPER_MEDIA_ID_PREFIX${UUID.randomUUID()}"
        swiperQueueEntries[swiperId] = track.title
        return MediaItem.Builder()
            .setMediaId(swiperId)
            .setUri(Uri.parse(track.uri))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).build())
            .build()
    }

    private fun insertSwiperBeforeCurrent() {
        val folder = swiperFolder ?: return
        val index = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount) return
        val currentId = player.currentMediaItem?.mediaId ?: return
        val swiperItem = createSwiperMediaItem(folder)
        player.addMediaItem(index, swiperItem)
        musicEntriesSinceSwiper = 0
        lastQueueMediaId = swiperItem.mediaId
        player.seekTo(index, 0L)
        player.play()
        Log.i(TAG, "Swiper añadido antes de $currentId: ${swiperQueueEntries[swiperItem.mediaId]}")
    }

    private fun refreshSwiperEntry(swiperId: String) {
        val folder = swiperFolder ?: return
        val index = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount) return
        val previousUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val replacement = createSwiperMediaItem(folder, previousUri)
        swiperQueueEntries.remove(swiperId)
        player.replaceMediaItem(index, replacement)
    }

    private fun restoreSwiperFolder() {
        val saved = getSharedPreferences(SWIPER_PREFS, Context.MODE_PRIVATE).getString(SWIPER_JSON, null)
            ?: return publishSwiperStatus()
        try {
            val json = JSONObject(saved)
            val tracksJson = json.getJSONArray("tracks")
            val tracks = (0 until tracksJson.length()).map { index ->
                val track = tracksJson.getJSONObject(index)
                StoredTrack(track.getString("uri"), track.getString("title"))
            }
            swiperFolder = QueueFolderEntry(json.getString("uri"), json.getString("name"), tracks)
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo restaurar la carpeta Swiper", error)
        }
        publishSwiperStatus()
    }

    private fun persistSwiperFolder() {
        val preferences = getSharedPreferences(SWIPER_PREFS, Context.MODE_PRIVATE)
        val folder = swiperFolder
        if (folder == null) {
            preferences.edit().remove(SWIPER_JSON).apply()
        } else {
            val tracks = JSONArray()
            folder.tracks.forEach { tracks.put(JSONObject().put("uri", it.uri).put("title", it.title)) }
            val saved = JSONObject()
                .put("uri", folder.treeUri)
                .put("name", folder.name)
                .put("tracks", tracks)
            preferences.edit().putString(SWIPER_JSON, saved.toString()).apply()
        }
    }

    private fun publishSwiperStatus() {
        val folder = swiperFolder
        _swiperStatus.value = SwiperSnapshot(
            isConfigured = folder != null,
            folderName = folder?.name.orEmpty(),
            trackCount = folder?.tracks?.size ?: 0,
        )
    }

    private fun restoreClockAudioFolder() {
        val saved = getSharedPreferences(CLOCK_AUDIO_PREFS, Context.MODE_PRIVATE)
            .getString(CLOCK_AUDIO_JSON, null) ?: return publishClockAudioFolderStatus()
        try {
            val json = JSONObject(saved)
            clockPhraseAudio = Uri.parse(json.getString("phrase"))
            val hours = json.getJSONArray("hours")
            val minutes = json.getJSONArray("minutes")
            for (index in 0 until hours.length()) {
                val entry = hours.getJSONObject(index)
                clockHourAudioFiles[entry.getInt("value")] = Uri.parse(entry.getString("uri"))
            }
            for (index in 0 until minutes.length()) {
                val entry = minutes.getJSONObject(index)
                clockMinuteAudioFiles[entry.getInt("value")] = Uri.parse(entry.getString("uri"))
            }
            clockAudioFolderName = json.getString("name")
            if (clockPhraseAudio == null || clockHourAudioFiles.isEmpty() || clockMinuteAudioFiles.isEmpty()) {
                throw IllegalArgumentException("La carpeta guardada no contiene los segmentos requeridos")
            }
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo restaurar la carpeta de segmentos de locución; se usará TTS", error)
            clockAudioFolderName = ""
            clockPhraseAudio = null
            clockHourAudioFiles.clear()
            clockMinuteAudioFiles.clear()
            getSharedPreferences(CLOCK_AUDIO_PREFS, Context.MODE_PRIVATE)
                .edit().remove(CLOCK_AUDIO_JSON).apply()
        }
        publishClockAudioFolderStatus()
    }

    private fun persistClockAudioFolder() {
        val hours = JSONArray()
        clockHourAudioFiles.toSortedMap().forEach { (value, uri) ->
            hours.put(JSONObject().put("value", value).put("uri", uri.toString()))
        }
        val minutes = JSONArray()
        clockMinuteAudioFiles.toSortedMap().forEach { (value, uri) ->
            minutes.put(JSONObject().put("value", value).put("uri", uri.toString()))
        }
        val saved = JSONObject()
            .put("name", clockAudioFolderName)
            .put("phrase", clockPhraseAudio.toString())
            .put("hours", hours)
            .put("minutes", minutes)
        getSharedPreferences(CLOCK_AUDIO_PREFS, Context.MODE_PRIVATE)
            .edit().putString(CLOCK_AUDIO_JSON, saved.toString()).apply()
    }

    private fun publishClockAudioFolderStatus() {
        _clockAudioFolderStatus.value = ClockAudioFolderSnapshot(
            isConfigured = clockPhraseAudio != null &&
                clockHourAudioFiles.isNotEmpty() &&
                clockMinuteAudioFiles.isNotEmpty(),
            folderName = clockAudioFolderName,
            hasPhrase = clockPhraseAudio != null,
            configuredHours = clockHourAudioFiles.keys.sorted(),
            configuredMinutes = clockMinuteAudioFiles.keys.sorted(),
        )
    }

    fun removeSwiperItemsFromQueue() {
        val swiperIds = swiperQueueEntries.keys.toSet()
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(index).mediaId in swiperIds) player.removeMediaItem(index)
        }
        swiperQueueEntries.clear()
        musicEntriesSinceSwiper = 0
        syncPlaylist()
        publishPlayback()
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
                    when (track.optString("type", "track")) {
                        "folder" -> {
                            val folderTracksJson = track.optJSONArray("tracks") ?: JSONArray()
                            val folderTracks = (0 until folderTracksJson.length()).map { folderTrackIndex ->
                                val folderTrack = folderTracksJson.getJSONObject(folderTrackIndex)
                                StoredTrack(folderTrack.getString("uri"), folderTrack.getString("title"))
                            }
                            StoredPlaylistEntry.Folder(
                                treeUri = track.getString("uri"),
                                name = track.getString("name"),
                                tracks = folderTracks,
                            )
                        }
                        "clock" -> StoredPlaylistEntry.ClockAnnouncement
                        else -> StoredPlaylistEntry.Track(track.getString("uri"), track.getString("title"))
                    }
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
        storedPlaylists.forEach { (name, entries) ->
            val trackArray = JSONArray()
            entries.forEach { entry ->
                when (entry) {
                    is StoredPlaylistEntry.Track -> {
                        trackArray.put(
                            JSONObject()
                                .put("type", "track")
                                .put("uri", entry.uri)
                                .put("title", entry.title),
                        )
                    }
                    is StoredPlaylistEntry.Folder -> {
                        val folderTracks = JSONArray()
                        entry.tracks.forEach { track ->
                            folderTracks.put(JSONObject().put("uri", track.uri).put("title", track.title))
                        }
                        trackArray.put(
                            JSONObject()
                                .put("type", "folder")
                                .put("uri", entry.treeUri)
                                .put("name", entry.name)
                                .put("tracks", folderTracks),
                        )
                    }
                    StoredPlaylistEntry.ClockAnnouncement -> {
                        trackArray.put(JSONObject().put("type", "clock"))
                    }
                }
            }
            array.put(JSONObject().put("name", name).put("tracks", trackArray))
        }
        getSharedPreferences(PLAYLIST_PREFS, Context.MODE_PRIVATE)
            .edit().putString(PLAYLISTS_JSON, array.toString()).apply()
    }

    private fun publishSavedPlaylists() {
        _savedPlaylists.value = storedPlaylists.map { (name, entries) ->
            val summaryTracks = entries.map { entry ->
                when (entry) {
                    is StoredPlaylistEntry.Track -> SavedPlaylistTrack(entry.uri, entry.title)
                    is StoredPlaylistEntry.Folder -> SavedPlaylistTrack(
                        uri = entry.treeUri,
                        title = entry.name,
                        isFolder = true,
                        folderTrackCount = entry.tracks.size,
                    )
                    StoredPlaylistEntry.ClockAnnouncement -> SavedPlaylistTrack(
                        uri = "",
                        title = CLOCK_ANNOUNCEMENT_TITLE,
                        isClockAnnouncement = true,
                    )
                }
            }
            SavedPlaylistSummary(name, entries.size, summaryTracks)
        }
    }

    private fun restoreFavorites() {
        val saved = getSharedPreferences(FAVORITES_PREFS, Context.MODE_PRIVATE)
            .getString(FAVORITES_JSON, "[]") ?: "[]"
        try {
            val array = JSONArray(saved)
            for (index in 0 until array.length()) {
                val favorite = array.getJSONObject(index)
                val uri = favorite.getString("uri")
                storedFavorites[uri] = FavoriteTrack(uri, favorite.getString("title"))
            }
        } catch (error: Exception) {
            Log.w(TAG, "No se pudieron cargar los favoritos", error)
        }
        publishFavorites()
    }

    private fun persistFavorites() {
        val array = JSONArray()
        storedFavorites.values.forEach { favorite ->
            array.put(JSONObject().put("uri", favorite.uri).put("title", favorite.title))
        }
        getSharedPreferences(FAVORITES_PREFS, Context.MODE_PRIVATE)
            .edit().putString(FAVORITES_JSON, array.toString()).apply()
    }

    private fun publishFavorites() {
        _favoriteTracks.value = storedFavorites.values.toList()
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
            ACTION_PLAY_SCHEDULED_EVENT -> {
                if (!isStreaming) startForegroundCompat(includeMicrophone = false)
                playScheduledEvent(intent.getStringExtra(EXTRA_EVENT_ID).orEmpty())
            }
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
        if (streamEncoder != null) return false
        if (host.isBlank() || port !in 1..65535 || mount.isBlank()) {
            return failBroadcastStart("Revisa el host, puerto y punto de montaje del perfil.")
        }
        if (!engine.isRendering) {
            return failBroadcastStart("El motor de audio no está disponible. Reinicia la app e inténtalo de nuevo.")
        }

        val client = IcecastClient(host, port, mount, password, StreamCodec.Mp3, bitrate)
        val encoder = StreamEncoder(engine.nativeHandle, client::sendPacket)
        var recorder: AudioRecord? = null

        return try {
            startForegroundCompat(includeMicrophone = true)
            recorder = createAudioRecord()
                ?: throw IOException("Android no pudo inicializar la captura del micrófono.")
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("La captura del micrófono no llegó al estado de grabación.")
            }
            audioRecord = recorder
            icecast = client
            streamEncoder = encoder
            client.start()
            if (!encoder.start(StreamCodec.Mp3, SAMPLE_RATE, CHANNELS, bitrate)) {
                throw IOException("El codificador MP3 no pudo iniciar.")
            }
            captureActive = true
            captureThread = Thread({ captureMicrophone(recorder) }, "RadioMicCapture").apply { start() }
            streamMonitorJob?.cancel()
            streamMonitorJob = scope.launch {
                while (isActive && streamEncoder != null) {
                    publishStreamStatus(host, port, mount)
                    delay(250)
                }
            }
            listenerCountJob?.cancel()
            listenerCountJob = scope.launch {
                while (isActive && streamEncoder != null) {
                    val count = queryActiveListeners(host, port, mount)
                    _streamStatus.value = _streamStatus.value.copy(activeListeners = count)
                    delay(LISTENER_POLL_INTERVAL_MS)
                }
            }
            publishStreamStatus(host, port, mount)
            Log.i(TAG, "Emisión iniciada hacia ${host}:${port}${mount} (MP3, ${bitrate}kbps)")
            true
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo iniciar la emisión", error)
            recorder?.stopSafely()
            if (audioRecord !== recorder) recorder?.release()
            stopStreaming()
            _streamStatus.value = StreamSnapshot(error = error.message ?: "No se pudo iniciar la emisión.")
            false
        }
    }

    private fun failBroadcastStart(message: String): Boolean {
        _streamStatus.value = StreamSnapshot(error = message)
        Log.e(TAG, message)
        return false
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
        listenerCountJob?.cancel()
        listenerCountJob = null
        _streamStatus.value = StreamSnapshot()
        if (hasPendingRadioEvent()) {
            startForegroundCompat(includeMicrophone = false)
            eventForegroundActive = true
        } else {
            stopForegroundCompat()
            stopSelf()
        }
        Log.i(TAG, "Streaming detenido")
    }

    private fun hasPendingRadioEvent(): Boolean =
        (0 until player.mediaItemCount).any {
            player.getMediaItemAt(it).mediaId.startsWith(RADIO_EVENT_MEDIA_ID_PREFIX)
        }

    private fun publishStreamStatus(host: String, port: Int, mount: String) {
        _streamStatus.value = StreamSnapshot(
            isStreaming = streamEncoder != null,
            isConnected = icecast?.isConnected() == true,
            endpoint = "$host:$port$mount",
            error = icecast?.lastError.orEmpty(),
            activeListeners = _streamStatus.value.activeListeners,
        )
    }

    private suspend fun queryActiveListeners(host: String, port: Int, mount: String): Int? =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            val response = try {
                socket.connect(InetSocketAddress(host, port), LISTENER_CONNECT_TIMEOUT_MS)
                socket.soTimeout = LISTENER_READ_TIMEOUT_MS
                val authority = if (host.count { it == ':' } > 1 && !host.startsWith("[")) "[$host]" else host
                socket.getOutputStream().write(
                    (
                        "GET /status-json.xsl HTTP/1.0\r\n" +
                            "Host: $authority:$port\r\n" +
                            "Accept: application/json\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            } catch (_: Exception) {
                null
            } finally {
                runCatching { socket.close() }
            } ?: return@withContext null

            val headerEnd = response.indexOf("\r\n\r\n").takeIf { it >= 0 }
                ?: response.indexOf("\n\n").takeIf { it >= 0 }
                ?: return@withContext null
            val headers = response.substring(0, headerEnd)
            val statusCode = headers.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
            if (statusCode !in 200..299) return@withContext null
            val rawBody = response.substring(headerEnd + if (response.startsWith("\r\n\r\n", headerEnd)) 4 else 2)
            val body = if (headers.contains("transfer-encoding: chunked", ignoreCase = true)) {
                decodeChunkedBody(rawBody) ?: return@withContext null
            } else rawBody

            runCatching {
                val stats = JSONObject(body).optJSONObject("icestats") ?: return@withContext null
                val normalizedMount = mount.trim().let { if (it.startsWith("/")) it else "/$it" }.trimEnd('/')
                val source = stats.opt("source")
                when (source) {
                    null, JSONObject.NULL -> 0
                    is JSONObject -> listenerCountForSource(source, normalizedMount, true)
                    is JSONArray -> {
                        val sources = (0 until source.length()).mapNotNull(source::optJSONObject)
                        sources.firstNotNullOfOrNull { listenerCountForSource(it, normalizedMount, sources.size == 1) } ?: 0
                    }
                    else -> null
                }
            }.getOrNull()
        }

    private fun decodeChunkedBody(body: String): String? {
        val decoded = StringBuilder()
        var cursor = 0
        while (cursor < body.length) {
            val lineEnd = body.indexOf("\r\n", cursor)
            if (lineEnd < 0) return null
            val chunkSize = body.substring(cursor, lineEnd).substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (chunkSize == 0) return decoded.toString()
            val contentStart = lineEnd + 2
            val contentEnd = contentStart + chunkSize
            if (contentEnd + 2 > body.length) return null
            decoded.append(body, contentStart, contentEnd)
            if (!body.startsWith("\r\n", contentEnd)) return null
            cursor = contentEnd + 2
        }
        return null
    }

    private fun listenerCountForSource(source: JSONObject, mount: String, isSingleSource: Boolean): Int? {
        val sourceMount = source.optString("mount").ifBlank {
            Uri.parse(source.optString("listenurl")).path.orEmpty()
        }.trimEnd('/')
        if (sourceMount.isNotBlank() && sourceMount != mount) return null
        if (sourceMount.isBlank() && !isSingleSource) return null
        return source.optString("listeners").toIntOrNull()?.coerceAtLeast(0)
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
            val count = try {
                recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            } catch (error: Exception) {
                reportCaptureFailure(recorder, "Falló la captura del micrófono: ${error.message ?: "error desconocido"}.")
                break
            }
            if (count < 0) {
                val reason = if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                    "Android perdió el dispositivo de captura del micrófono."
                } else {
                    "La captura del micrófono devolvió el error $count."
                }
                reportCaptureFailure(recorder, reason)
                break
            }
            if (count == 0) continue
            for (index in 0 until count) samples[index] = pcm[index] / 32768f
            engine.pushInputSamples(samples.copyOf(count))
        }
    }

    private fun reportCaptureFailure(recorder: AudioRecord, message: String) {
        Log.e(TAG, message)
        scope.launch(Dispatchers.Main.immediate) {
            if (!captureActive || audioRecord !== recorder) return@launch
            stopStreaming()
            _streamStatus.value = StreamSnapshot(error = message)
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
        if (::clockSynthesizer.isInitialized) clockSynthesizer.shutdown()
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
        private const val LISTENER_POLL_INTERVAL_MS = 10_000L
        private const val LISTENER_CONNECT_TIMEOUT_MS = 4_000
        private const val LISTENER_READ_TIMEOUT_MS = 5_000
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val CAPTURE_BUFFER_SAMPLES = 1024
        private const val PLAYLIST_PREFS = "radio_playlists"
        private const val PLAYLISTS_JSON = "saved_playlists"
        private const val FAVORITES_PREFS = "radio_favorites"
        private const val FAVORITES_JSON = "favorite_tracks"
        private const val SWIPER_PREFS = "radio_swiper"
        private const val SWIPER_JSON = "swiper_source"
        private const val CLOCK_AUDIO_PREFS = "radio_clock_audio"
        private const val CLOCK_AUDIO_JSON = "clock_audio_folder"
        private const val SWIPER_MEDIA_ID_PREFIX = "swiper:"
        private const val CLOCK_MEDIA_ID_PREFIX = "clock:"
        private const val CLOCK_SEGMENT_MEDIA_ID_PREFIX = "clock-segment:"
        private const val CLOCK_MEDIA_URI_PREFIX = "radiosuite-clock://"
        private const val CLOCK_ANNOUNCEMENT_TITLE = "Locución de hora"
        private const val RADIO_EVENT_MEDIA_ID_PREFIX = "radio-event:"
        private const val EVENT_MISSED_TOLERANCE_MS = 5 * 60 * 1000L
        private const val SWIPER_INTERVAL_TRACKS = 2
        private const val MIX_PREFS = "radio_mix"
        private const val PLAYER_GAIN_KEY = "player_gain_db"
        private const val MIC_GAIN_KEY = "mic_gain_db"
        private const val PLAYER_MUTED_KEY = "player_muted"
        private const val MIC_MUTED_KEY = "mic_muted"
        private const val MUTED_GAIN_DB = -60f

        const val ACTION_START_STREAM = "com.radiosuite.pro.START_STREAM"
        const val ACTION_STOP_STREAM  = "com.radiosuite.pro.STOP_STREAM"
        const val ACTION_UPDATE_DSP   = "com.radiosuite.pro.UPDATE_DSP"
        const val ACTION_PLAY_SCHEDULED_EVENT = "com.radiosuite.pro.PLAY_SCHEDULED_EVENT"

        const val EXTRA_EVENT_ID = "eventId"
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
