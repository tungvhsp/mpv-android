package `is`.xyz.mpv.tts

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads Piper TTS models on first use and stores them under filesDir. */
internal object SubtitleTtsModels {
    private const val TAG = "mpv-tts-models"
    const val STORAGE_ROOT = "tts_voice_model"
    private const val MARKER = ".install-complete"

    const val VI_DIR = "vits-piper-vi_VN-vais1000-medium"
    const val VI_ONNX = "vi_VN-vais1000-medium.onnx"
    const val VI_ARCHIVE = "vits-piper-vi_VN-vais1000-medium.tar.bz2"
    const val VI_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-vi_VN-vais1000-medium.tar.bz2"

    const val EN_DIR = "vits-piper-en_GB-southern_english_female-low"
    const val EN_ONNX = "en_GB-southern_english_female-low.onnx"
    const val EN_ARCHIVE = "vits-piper-en_GB-southern_english_female-low.tar.bz2"
    const val EN_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-southern_english_female-low.tar.bz2"

    fun rootDir(context: Context): File = File(context.filesDir, STORAGE_ROOT)

    fun viModelDir(context: Context): File = File(rootDir(context), VI_DIR)

    fun enModelDir(context: Context): File = File(rootDir(context), EN_DIR)

    @Synchronized
    fun ensureInstalled(context: Context, onProgress: ((String) -> Unit)? = null) {
        if (areAllModelsReady(context)) {
            writeMarker(context)
            return
        }

        val root = rootDir(context)
        root.mkdirs()
        Log.i(TAG, "Installing TTS models to ${root.absolutePath}")

        if (!isModelReady(viModelDir(context), VI_ONNX)) {
            onProgress?.invoke("tts:download:vi")
            downloadAndExtract(context, VI_URL, VI_ARCHIVE, onProgress)
        }

        if (!isModelReady(enModelDir(context), EN_ONNX)) {
            onProgress?.invoke("tts:download:en")
            downloadAndExtract(context, EN_URL, EN_ARCHIVE, onProgress)
        }

        if (!areAllModelsReady(context)) {
            throw IllegalStateException("TTS model install incomplete")
        }

        writeMarker(context)
        Log.i(TAG, "TTS models installed")
    }

    private fun areAllModelsReady(context: Context): Boolean {
        return isModelReady(viModelDir(context), VI_ONNX) &&
            isModelReady(enModelDir(context), EN_ONNX)
    }

    private fun isModelReady(modelDir: File, onnxFileName: String): Boolean {
        return File(modelDir, onnxFileName).exists() &&
            File(modelDir, "tokens.txt").exists() &&
            File(modelDir, "espeak-ng-data").isDirectory
    }

    private fun writeMarker(context: Context) {
        File(rootDir(context), MARKER).writeText("ok")
    }

    private fun downloadAndExtract(
        context: Context,
        urlString: String,
        archiveName: String,
        onProgress: ((String) -> Unit)?,
    ) {
        val root = rootDir(context)
        val archive = File(context.cacheDir, archiveName)
        if (archive.exists())
            archive.delete()

        downloadFile(urlString, archive) { percent ->
            onProgress?.invoke("tts:progress:$archiveName:$percent")
        }

        onProgress?.invoke("tts:extract:$archiveName")
        extractTarBz2(archive, root)
        archive.delete()
    }

    private fun downloadFile(
        urlString: String,
        dest: File,
        onPercent: ((Int) -> Unit)?,
    ) {
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 60_000
            readTimeout = 300_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode for $urlString")
            }

            val totalBytes = connection.contentLengthLong
            dest.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0)
                            break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val percent = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onPercent?.invoke(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarBz2(archive: File, destParent: File) {
        FileInputStream(archive).use { fileInput ->
            BufferedInputStream(fileInput).use { buffered ->
                BZip2CompressorInputStream(buffered).use { bzip ->
                    TarArchiveInputStream(bzip).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextTarEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val outFile = File(destParent, entry.name)
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { output ->
                                    tar.copyTo(output)
                                }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
            }
        }
    }
}
