package `is`.xyz.mpv.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Offline Sherpa-ONNX subtitle TTS with Vietnamese + English Piper models. */
class EmbeddedSubtitleTts(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var viTts: OfflineTts? = null
    private var enTts: OfflineTts? = null

    @Volatile
    private var ready = false

    @Volatile
    private var preparing = false

    @Volatile
    private var stopRequested = false

    private val playGeneration = AtomicInteger(0)
    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate = 0

    fun isReady(): Boolean = ready

    fun isPreparing(): Boolean = preparing

    fun prepare(
        onProgress: ((String) -> Unit)? = null,
        callback: (Boolean, String?) -> Unit,
    ) {
        if (ready) {
            callback(true, null)
            return
        }
        if (preparing)
            return

        preparing = true
        worker.execute {
            try {
                SubtitleTtsModels.ensureInstalled(appContext) { status ->
                    mainHandler.post { onProgress?.invoke(status) }
                }
                initEngines()
                ready = true
                preparing = false
                mainHandler.post { callback(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare embedded TTS", e)
                preparing = false
                mainHandler.post { callback(false, e.message ?: "unknown error") }
            }
        }
    }

    private fun initEngines() {
        viTts?.free()
        enTts?.free()

        val viDir = SubtitleTtsModels.viModelDir(appContext).absolutePath
        viTts = OfflineTts(
            config = getOfflineTtsConfig(
                modelDir = viDir,
                modelName = SubtitleTtsModels.VI_ONNX,
                acousticModelName = "",
                vocoder = "",
                voices = "",
                lexicon = "",
                dataDir = "$viDir/espeak-ng-data",
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                numThreads = 2,
            ),
        )

        val enDir = SubtitleTtsModels.enModelDir(appContext).absolutePath
        enTts = OfflineTts(
            config = getOfflineTtsConfig(
                modelDir = enDir,
                modelName = SubtitleTtsModels.EN_ONNX,
                acousticModelName = "",
                vocoder = "",
                voices = "",
                lexicon = "",
                dataDir = "$enDir/espeak-ng-data",
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                numThreads = 2,
            ),
        )
    }

    fun speak(text: String, speed: Float, volume: Float) {
        if (!ready)
            return

        val generation = playGeneration.incrementAndGet()
        stopRequested = false

        worker.execute {
            if (generation != playGeneration.get())
                return@execute

            try {
                val segments = SubtitleTextSegmenter.segment(text)
                if (segments.isEmpty())
                    return@execute

                val samples = ArrayList<Float>(4096)
                var sampleRate = viTts!!.sampleRate()

                for (segment in segments) {
                    if (stopRequested || generation != playGeneration.get())
                        return@execute

                    val tts = when (segment.lang) {
                        SubtitleTextSegmenter.Lang.EN -> enTts
                        SubtitleTextSegmenter.Lang.VI -> viTts
                    } ?: continue

                    val audio = tts.generate(text = segment.text, speed = speed.coerceIn(0.5f, 2.5f))
                    if (audio.samples.isEmpty())
                        continue

                    sampleRate = audio.sampleRate
                    for (s in audio.samples)
                        samples.add(s)
                }

                if (stopRequested || generation != playGeneration.get() || samples.isEmpty())
                    return@execute

                playSamples(samples.toFloatArray(), sampleRate, volume.coerceIn(0f, 1f), generation)
            } catch (e: Exception) {
                Log.e(TAG, "speak failed", e)
            }
        }
    }

    fun stop() {
        stopRequested = true
        playGeneration.incrementAndGet()
        mainHandler.post {
            audioTrack?.pause()
            audioTrack?.flush()
        }
    }

    fun shutdown() {
        stop()
        worker.execute {
            viTts?.free()
            enTts?.free()
            viTts = null
            enTts = null
            ready = false
        }
        worker.shutdown()
        mainHandler.post {
            audioTrack?.release()
            audioTrack = null
            audioTrackSampleRate = 0
        }
    }

    fun statusLine(): String {
        return when {
            ready -> "TTS: Sherpa embedded (VI+EN)"
            preparing -> "TTS: preparing models..."
            else -> "TTS: not ready"
        }
    }

    private fun playSamples(samples: FloatArray, sampleRate: Int, volume: Float, generation: Int) {
        mainHandler.post {
            if (stopRequested || generation != playGeneration.get())
                return@post

            val scaled = if (volume >= 0.999f) {
                samples
            } else {
                FloatArray(samples.size) { i -> samples[i] * volume }
            }

            val track = obtainAudioTrack(sampleRate)
            track.play()
            track.write(scaled, 0, scaled.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    private fun obtainAudioTrack(sampleRate: Int): AudioTrack {
        if (audioTrack != null && audioTrackSampleRate == sampleRate)
            return audioTrack!!

        audioTrack?.release()
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        audioTrack = AudioTrack(
            attr,
            format,
            bufLength,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        audioTrackSampleRate = sampleRate
        return audioTrack!!
    }

    companion object {
        private const val TAG = "mpv-embedded-tts"
    }
}
