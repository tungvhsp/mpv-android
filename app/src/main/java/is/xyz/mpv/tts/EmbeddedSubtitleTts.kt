package `is`.xyz.mpv.tts

import android.content.Context
import android.os.Build
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
    private val usePcmFloat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

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
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to prepare embedded TTS", t)
                preparing = false
                mainHandler.post { callback(false, t.message ?: "unknown error") }
            }
        }
    }

    private fun initEngines() {
        viTts?.free()
        enTts?.free()
        enTts = null

        val viDir = SubtitleTtsModels.viModelDir(appContext).absolutePath
        val engine = OfflineTts(
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
                numThreads = 1,
            ),
        )
        engine.sampleRate()
        viTts = engine
    }

    private fun ensureEnEngine(): OfflineTts {
        enTts?.let { return it }

        val enDir = SubtitleTtsModels.enModelDir(appContext).absolutePath
        val engine = OfflineTts(
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
                numThreads = 1,
            ),
        )
        engine.sampleRate()
        enTts = engine
        return engine
    }

    private fun engineFor(lang: SubtitleTextSegmenter.Lang): OfflineTts? {
        return try {
            when (lang) {
                SubtitleTextSegmenter.Lang.EN -> ensureEnEngine()
                SubtitleTextSegmenter.Lang.VI -> viTts
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load ${lang.name} TTS engine", t)
            null
        }
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
                val cleanText = SubtitleTextSanitizer.forTts(text)
                val segments = SubtitleTextSegmenter.segment(cleanText)
                if (segments.isEmpty())
                    return@execute

                val viEngine = viTts ?: return@execute
                val samples = ArrayList<Float>(4096)
                var sampleRate = viEngine.sampleRate()

                for (segment in segments) {
                    if (stopRequested || generation != playGeneration.get())
                        return@execute

                    val segmentText = SubtitleTextSanitizer.forTts(segment.text)
                    if (segmentText.isEmpty())
                        continue

                    val tts = engineFor(segment.lang) ?: continue

                    val audio = tts.generate(
                        text = segmentText,
                        speed = speed.coerceIn(0.5f, 2.5f),
                    )
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
                Log.e(TAG, "speak failed for \"$text\"", e)
            } catch (e: Error) {
                Log.e(TAG, "speak native error for \"$text\"", e)
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

            try {
                val scaled = if (volume >= 0.999f) {
                    samples
                } else {
                    FloatArray(samples.size) { i -> samples[i] * volume }
                }

                val track = obtainAudioTrack(sampleRate)
                track.play()
                val chunkSize = 4096
                var offset = 0
                while (offset < scaled.size) {
                    if (stopRequested || generation != playGeneration.get())
                        return@post
                    val end = minOf(offset + chunkSize, scaled.size)
                    if (usePcmFloat) {
                        track.write(scaled, offset, end - offset, AudioTrack.WRITE_BLOCKING)
                    } else {
                        val pcm16 = ShortArray(end - offset) { i ->
                            (scaled[offset + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                        track.write(pcm16, 0, pcm16.size, AudioTrack.WRITE_BLOCKING)
                    }
                    offset = end
                }
            } catch (t: Throwable) {
                Log.e(TAG, "AudioTrack playback failed", t)
            }
        }
    }

    private fun obtainAudioTrack(sampleRate: Int): AudioTrack {
        if (audioTrack != null && audioTrackSampleRate == sampleRate)
            return audioTrack!!

        audioTrack?.release()
        val encoding = if (usePcmFloat) {
            AudioFormat.ENCODING_PCM_FLOAT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            encoding,
        )
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
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
