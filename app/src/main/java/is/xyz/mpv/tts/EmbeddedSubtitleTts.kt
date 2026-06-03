package `is`.xyz.mpv.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
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
    private var activeLang: SubtitleTextSegmenter.Lang? = null

    @Volatile
    private var modelsInstalled = SubtitleTtsModels.isInstalled(appContext)

    @Volatile
    private var preparing = false

    private val prepareCallbacks = ArrayList<(Boolean, String?) -> Unit>()

    @Volatile
    private var stopRequested = false

    private val playGeneration = AtomicInteger(0)
    private val engineLock = Any()
    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate = 0
    private val usePcmFloat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /** Called on the main thread when engine load or synth fails. */
    var onEngineError: ((String) -> Unit)? = null

    /** Pause video before loading ONNX to reduce OOM/native crashes. */
    var onRequestPauseForLoad: (() -> Unit)? = null

    var onRequestResumeAfterLoad: (() -> Unit)? = null

    init {
        // Touch JNI once at startup (cheaper than during first subtitle line).
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
        } catch (t: Throwable) {
            Log.e(TAG, "Sherpa ONNX classes missing", t)
        }
    }

    fun hasModelsOnDisk(): Boolean = modelsInstalled

    fun isReady(): Boolean = modelsInstalled

    fun isPreparing(): Boolean = preparing

    fun prepare(
        onProgress: ((String) -> Unit)? = null,
        callback: (Boolean, String?) -> Unit,
    ) {
        if (modelsInstalled) {
            mainHandler.post { callback(true, null) }
            return
        }

        var startInstall = false
        synchronized(this) {
            prepareCallbacks.add(callback)
            if (preparing)
                return
            preparing = true
            startInstall = true
        }
        if (!startInstall)
            return

        worker.execute {
            try {
                SubtitleTtsModels.ensureInstalled(appContext) { status ->
                    mainHandler.post { onProgress?.invoke(status) }
                }
                modelsInstalled = true
                finishPrepare(success = true, error = null)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to prepare embedded TTS", t)
                finishPrepare(success = false, error = t.message ?: "unknown error")
            }
        }
    }

    /** Unload ONNX from RAM; model files stay on disk. */
    fun releaseEngines() {
        worker.execute {
            synchronized(engineLock) {
                releaseAllEngines()
            }
            Log.i(TAG, "TTS engines released from memory")
        }
    }

    private fun finishPrepare(success: Boolean, error: String?) {
        val callbacks: List<(Boolean, String?) -> Unit>
        synchronized(this) {
            preparing = false
            callbacks = prepareCallbacks.toList()
            prepareCallbacks.clear()
        }
        mainHandler.post {
            for (cb in callbacks)
                cb(success, error)
        }
    }

    private fun releaseAllEngines() {
        viTts?.free()
        enTts?.free()
        viTts = null
        enTts = null
        activeLang = null
    }

    private fun validateModelDir(modelDir: File, onnxFileName: String) {
        val onnx = File(modelDir, onnxFileName)
        val tokens = File(modelDir, "tokens.txt")
        val espeak = File(modelDir, "espeak-ng-data")
        if (!onnx.isFile)
            throw IllegalStateException("Missing model: ${onnx.absolutePath}")
        if (!tokens.isFile)
            throw IllegalStateException("Missing tokens: ${tokens.absolutePath}")
        if (!espeak.isDirectory)
            throw IllegalStateException("Missing espeak-ng-data: ${espeak.absolutePath}")
    }

    /** Caller must hold [engineLock]. */
    private fun ensureViEngine(): OfflineTts {
        viTts?.let { return it }

        val modelDir = SubtitleTtsModels.viModelDir(appContext)
        validateModelDir(modelDir, SubtitleTtsModels.VI_ONNX)
        notifyError(appContext.getString(
            `is`.xyz.mpv.R.string.tts_loading_voice,
        ))
        requestPauseForLoad()
        Log.i(TAG, "Loading VI TTS engine...")
        val engine = OfflineTts(
            config = getOfflineTtsConfig(
                modelDir = modelDir.absolutePath,
                modelName = SubtitleTtsModels.VI_ONNX,
                acousticModelName = "",
                vocoder = "",
                voices = "",
                lexicon = "",
                dataDir = "${modelDir.absolutePath}/espeak-ng-data",
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                numThreads = 1,
            ),
        )
        engine.sampleRate()
        viTts = engine
        Log.i(TAG, "VI TTS engine ready @ ${engine.sampleRate()}Hz")
        return engine
    }

    /** Caller must hold [engineLock]. */
    private fun ensureEnEngine(): OfflineTts {
        enTts?.let { return it }

        val modelDir = SubtitleTtsModels.enModelDir(appContext)
        validateModelDir(modelDir, SubtitleTtsModels.EN_ONNX)
        notifyError(appContext.getString(
            `is`.xyz.mpv.R.string.tts_loading_voice,
        ))
        requestPauseForLoad()
        Log.i(TAG, "Loading EN TTS engine...")
        val engine = OfflineTts(
            config = getOfflineTtsConfig(
                modelDir = modelDir.absolutePath,
                modelName = SubtitleTtsModels.EN_ONNX,
                acousticModelName = "",
                vocoder = "",
                voices = "",
                lexicon = "",
                dataDir = "${modelDir.absolutePath}/espeak-ng-data",
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                numThreads = 1,
            ),
        )
        engine.sampleRate()
        enTts = engine
        Log.i(TAG, "EN TTS engine ready @ ${engine.sampleRate()}Hz")
        return engine
    }

    private fun engineFor(lang: SubtitleTextSegmenter.Lang): OfflineTts? {
        return try {
            synchronized(engineLock) {
                // Only one Piper model in RAM at a time (avoids OOM / native crash on mid-range phones).
                if (activeLang != null && activeLang != lang)
                    releaseAllEngines()
                activeLang = lang
                when (lang) {
                    SubtitleTextSegmenter.Lang.EN -> ensureEnEngine()
                    SubtitleTextSegmenter.Lang.VI -> ensureViEngine()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load ${lang.name} TTS engine", t)
            notifyError("TTS engine (${lang.name}): ${t.message ?: "load failed"}")
            null
        }
    }

    private fun notifyError(message: String) {
        mainHandler.post { onEngineError?.invoke(message) }
    }

    private fun requestPauseForLoad() {
        mainHandler.post { onRequestPauseForLoad?.invoke() }
    }

    private fun requestResumeAfterLoad() {
        mainHandler.post { onRequestResumeAfterLoad?.invoke() }
    }

    fun speak(text: String, speed: Float, volume: Float) {
        if (!modelsInstalled)
            return

        val generation = playGeneration.incrementAndGet()
        stopRequested = false

        worker.execute {
            synchronized(engineLock) {
            if (generation != playGeneration.get())
                return@execute

            try {
                val cleanText = SubtitleTextSanitizer.forTts(text)
                val segments = SubtitleTextSegmenter.segment(cleanText)
                Log.i(TAG, "speak: ${cleanText.length} chars, ${segments.size} segment(s)")
                if (segments.isEmpty())
                    return@execute

                val samples = ArrayList<Float>(4096)
                var sampleRate = 22050

                for (segment in segments) {
                    if (stopRequested || generation != playGeneration.get())
                        return@execute

                    val segmentText = SubtitleTextSanitizer.forTts(segment.text)
                    if (segmentText.isEmpty())
                        continue

                    val tts = engineFor(segment.lang) ?: continue

                    Log.i(TAG, "generate ${segment.lang}: \"${segmentText.take(40)}\"")
                    val audio = tts.generate(
                        text = segmentText,
                        speed = speed.coerceIn(0.5f, 2.5f),
                    )
                    if (audio.samples.isEmpty()) {
                        Log.w(TAG, "generate ${segment.lang} returned empty audio")
                        continue
                    }

                    sampleRate = audio.sampleRate
                    for (s in audio.samples)
                        samples.add(s)
                }

                if (stopRequested || generation != playGeneration.get() || samples.isEmpty()) {
                    Log.w(TAG, "speak produced no audio")
                    return@execute
                }

                Log.i(TAG, "playing ${samples.size} samples @ ${sampleRate}Hz")
                requestResumeAfterLoad()
                playSamples(samples.toFloatArray(), sampleRate, volume.coerceIn(0f, 1f), generation)
            } catch (e: Exception) {
                Log.e(TAG, "speak failed for \"$text\"", e)
                notifyError("TTS: ${e.message ?: "speak failed"}")
            } catch (e: Error) {
                Log.e(TAG, "speak native error for \"$text\"", e)
                notifyError("TTS native error")
            }
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
            synchronized(engineLock) { releaseAllEngines() }
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
            viTts != null || enTts != null -> "TTS: voice loaded"
            modelsInstalled -> "TTS: models OK"
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
                track.setVolume(1.0f)
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
                notifyError("TTS playback failed")
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
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            encoding,
        )
        val bytesPerSample = if (usePcmFloat) 4 else 2
        val bufLength = maxOf(minBuf, sampleRate * bytesPerSample * 2)
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
