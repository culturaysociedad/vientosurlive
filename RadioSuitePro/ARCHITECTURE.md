# RadioSuitePro — Arquitectura General

## 1. Visión

Aplicación Android nativa que combina un motor DSP de audio profesional (estilo
Breakaway / PulseEffects) con automatización de reproducción y transmisión en vivo
a Icecast/Shoutcast (estilo BroadcastSelf), orientada a operación 24/7.

## 2. Stack tecnológico

| Capa | Tecnología |
|------|-----------|
| UI | Kotlin + Jetpack Compose, Material3, tema oscuro tipo consola |
| Reproductor / Streamer | Kotlin (ExoPlayer para decodificación, OkHttp para Icecast) |
| Motor DSP + Codificadores | C++ nativo (NDK), CMake |
| Audio I/O baja latencia | Oboe (AAudio / OpenSL ES) |
| Codificación de stream | libogg, libvorbis, libopus, libmp3lame |
| Persistencia | Supabase (playlists, ajustes de DSP, sesión de emisión) |
| Ejecución 24/7 | Foreground Service + WorkManager + permisos de batería |

## 3. Estructura de módulos Gradle

```
RadioSuitePro/
├── settings.gradle.kts
├── build.gradle.kts                  (root)
├── gradle/libs.versions.toml         (version catalog)
└── app/
    ├── build.gradle.kts
    ├── CMakeLists.txt                 (punto de entrada CMake NDK)
    └── src/main/
        ├── AndroidManifest.xml
        ├── jni/                       (puente JNI Kotlin <-> C++)
        ├── java/com/radiosuite/pro/   (Kotlin)
        ├── cpp/                        (C++ nativo)
        └── res/                        (recursos Compose / drawables)
```

## 4. Paquetes Kotlin

```
com.radiosuite.pro
├── ui                          Compose: consola, paneles, vúmetros
│   ├── theme                   Colores, tipografía, formas (tema estudio)
│   ├── components              VUMeter, Knob, Fader, Spectrum, Crossfader
│   └── screens                 ConsolaScreen, PlaylistScreen, StreamScreen, SettingsScreen
├── service
│   └── RadioEngineService.kt   Foreground Service orquestador (reproductor + DSP + stream)
├── player
│   ├── PlaylistManager.kt      Cola, crossfade, mezcla micrófono/música
│   ├── ClockAnnouncementDataSource.kt  Locución TTS de la hora dentro de la cola
│   └── AudioSource.kt          Abstracción micrófono / archivo / stream entrante
├── stream
│   ├── IcecastClient.kt        Cliente Icecast v2 (HTTP PUT chunked)
│   ├── ShoutcastClient.kt      Cliente Shoutcast (ICY)
│   └── StreamEncoder.kt        Puente JNI al codificador nativo (Opus/Vorbis/MP3)
├── dsp
│   ├── DspEngine.kt            Wrapper Kotlin sobre el pipeline C++ vía JNI
│   └── DspParameters.kt        Modelo de parámetros (data classes inmutables)
├── meters
│   ├── LevelMeter.kt           Medidor RMS/Pico en tiempo real
│   └── MeterViewModel.kt       Publica niveles a la UI vía StateFlow
└── model
    ├── Playlist.kt
    ├── Track.kt
    └── BroadcastProfile.kt     Perfil de emisión (codec, bitrate, servidor)
```

## 5. Módulos C++ (NDK / CMake)

```
app/src/main/cpp/
├── CMakeLists.txt                 顶层
├── audio/
│   ├── AudioPipeline.h/.cpp        Cadena DSP secuencial
│   ├── AudioBlock.h                Estructura de buffer estéreo float
│   ├── processors/
│   │   ├── Agc.h/.cpp              (a) AGC de entrada
│   │   ├── ParametricEq.h/.cpp     (b) EQ paramétrico multibanda
│   │   ├── MultibandComp.h/.cpp    (c) Compresor/limitador multibanda
│   │   ├── StereoEnhancer.h/.cpp   (d) Expansor de presencia estéreo
│   │   └── BrickwallLimiter.h/.cpp (e) Limitador de picos 0 dB
│   ├── engine/
│   │   ├── OboeRenderer.h/.cpp     Salida baja latencia (AAudio/OpenSL)
│   │   └── OboeRecorder.h/.cpp     Captura de micrófono/consola
│   └── dsp/
│       ├── Biquad.h/.cpp           Filtros biquad (EQ / crossover)
│       ├── EnvelopeFollower.h/.cpp Seguidor de envolvente RMS/pico
│       ├── GainSmoothing.h/.cpp    Ramping suave de ganancia
│       └── Crossfader.h/.cpp       Fundido entre fuentes
├── player/
│   └── AudioMixer.h/.cpp           Mezclador N fuentes -> estéreo
├── stream/
│   ├── IcecastEncoder.h/.cpp       Codificador Opus/Vorbis/MP3 + paquetización OGG
│   └── RingBuffer.h/.cpp           Buffer de red resiliente
└── jni/
    ├── jni_bridge.cpp              Punto único de entrada JNI
    ├── jni_pipeline.cpp            Bindings del pipeline DSP
    └── jni_stream.cpp              Bindings del codificador/stream
```

## 6. Flujo de señal de extremo a extremo

```
┌────────────┐  ┌─────────────┐  ┌────────────┐  ┌──────────────┐
│ Micrófono  │->│            │->│            │->│              │
│ (Oboe in)  │  │  Mezclador │  │ Pipeline   │  │  Oboe out    │ -> altavoces / monitor
└────────────┘  │  (C++)     │  │  DSP (C++) │  │  (monitor)   │
┌────────────┐  │            │  │            │  └──────────────┘
│ Reproductor│->│            │->│            │
│  (ExoPlayer│  └─────────────┘  └─────┬──────┘
│  -> PCM)   │                        │
└────────────┘                        │ memcpy -> RingBuffer
                                      v
                                ┌──────────────┐  HTTP PUT chunked
                                │ Codificador  │ ───────────────> Icecast/Shoutcast
                                │  Opus/MP3    │
                                └──────────────┘
```

Las locuciones de hora son elementos de cola/playlists. Se puede seleccionar una
carpeta con `frase.mp3`, `horas/00`–`horas/23` y `minutos/00`–`minutos/59`.
Cuando se reproduce el elemento, ExoPlayer reproduce la frase, la hora y los
minutos como segmentos consecutivos. Si falta cualquiera de los segmentos para
la hora actual, usa TTS para anunciar la hora completa. Los permisos y la
selección se guardan para los siguientes inicios de la aplicación. Los segmentos
personalizados también se incorporan a la salida PCM y al stream.

Los eventos de radio son de una sola ejecución y se programan con fecha, hora y
un archivo de audio local. Android dispara una alarma exacta; el servicio inserta
el evento después del elemento que esté sonando y conserva el acceso al audio.
Los eventos pendientes se guardan localmente y se restauran tras reiniciar el
dispositivo. Si una alarma llega con más de cinco minutos de retraso, se omite.
La programación requiere habilitar el permiso de alarmas exactas en Android.

El Foreground Service `RadioEngineService` posee y coordina los tres motores
(reproductor, DSP, streamer) y mantiene vivo el hilo de captura/render de Oboe
incluso cuando la app pasa a segundo plano.
