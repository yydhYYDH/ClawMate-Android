package com.clawmate.app.gallery.ocr

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import com.clawmate.app.gallery.analysis.OcrErrorKind
import com.clawmate.app.gallery.analysis.OcrResult
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * The real on-device Chinese OCR adapter — the Phase-11 replacement for the
 * NOT_CONFIGURED gallery OCR boundary.
 *
 * Pipeline (all performed on a background dispatcher, never the caller's):
 *  1. Parse the picked photo's `content://` URI.
 *  2. Open it through [ContentResolver] (no broad storage permission — the URI
 *     grant the Photo Picker handed us is enough).
 *  3. Decode a bounded-size [Bitmap] (see [OcrBitmapDecode]) and recycle it
 *     after use.
 *  4. Run ML Kit Chinese text recognition
 *     (`com.google.mlkit:text-recognition-chinese`).
 *  5. Map the outcome to the typed [OcrResult] states via [OcrMapping]:
 *     real text, empty, cancelled, model-download, unavailable, or error.
 *  6. Close the recognizer and all opened resources.
 *
 * Honesty: [available] reports true when the ML Kit recognizer is constructible on
 * this device, which only means recognition *can be attempted* — it never means
 * text was produced. Real text exists only through [OcrResult.Text], and [OcrMapping
 * .isRealText] is what callers guard on before persisting anything.
 *
 * Both Android frontends use this implementation; availability and recognition
 * are separate outcomes.
 */
class LocalOcrRecognizer(
    private val context: Context,
    private val maxDecodeDimension: Int = OcrBitmapDecode.DEFAULT_MAX_DIMENSION,
    private val engineFactory: EngineFactory = DefaultEngineFactory,
) {

    /** Factory seam so capability/availability can be reasoned about without a device. */
    interface EngineFactory {
        /** True when ML Kit text recognition is constructible on this device. */
        fun isAvailable(): Boolean
        /** Build a fresh Chinese text recognizer. */
        fun newChineseRecognizer(): com.google.mlkit.vision.text.TextRecognizer
    }

    private object DefaultEngineFactory : EngineFactory {
        override fun isAvailable(): Boolean =
            runCatching {
                val r = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                r.close()
            }.isSuccess

        override fun newChineseRecognizer(): com.google.mlkit.vision.text.TextRecognizer =
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    val available: Boolean get() = engineFactory.isAvailable()

    suspend fun recognize(uri: String): OcrResult =
        withContext(Dispatchers.IO) { recognizeBlocking(uri) }

    fun recognizeOnWorkerThread(uri: String): OcrResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "本地识别不能阻塞界面线程" }
        return recognizeBlocking(uri)
    }

    private fun recognizeBlocking(sourceUri: String): OcrResult {
        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull()
            ?: return OcrMapping.failure(OcrErrorKind.URI_ACCESS, "内容 URI 无效")
        if (sourceUri.isBlank()) {
            return OcrMapping.failure(OcrErrorKind.URI_ACCESS, "项目缺失内容 URI")
        }

        val resolver = context.contentResolver
        // Open the stream (from the picker's URI grant — no broad storage permission).
        val input = try {
            resolver.openInputStream(uri)
        } catch (t: Throwable) {
            return OcrMapping.failure(OcrErrorKind.URI_ACCESS, t.message ?: "无法打开内容流")
        } ?: return OcrMapping.failure(OcrErrorKind.URI_ACCESS, "无法打开内容流")

        var bitmap: Bitmap? = null
        val recognizer = try { engineFactory.newChineseRecognizer() } catch (error: Exception) {
            input.close()
            return OcrMapping.failure(OcrErrorKind.UNAVAILABLE, "无法初始化本地识别引擎")
        }
        try {
            bitmap = decodeBounded(resolver, uri, input, maxDecodeDimension)
                ?: return OcrMapping.failure(OcrErrorKind.DECODE, "无法解码位图")

            val image = InputImage.fromBitmap(bitmap, 0)
            val text: Text = try {
                // Await synchronously here (already on Dispatchers.IO); the ML Kit
                // Task completes on a background executor, so we do not block main.
                Tasks.await(
                    recognizer.process(image),
                    MAX_AWAIT_SECONDS,
                    TimeUnit.SECONDS,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return classifyProcessingThrowable(t, uri)
            }
            return OcrMapping.textFromRecognized(text.text)
        } finally {
            runCatching { recognizer.close() }
            runCatching { bitmap?.recycle() }
            runCatching { input.close() }
        }
    }

    private fun classifyProcessingThrowable(t: Throwable, uri: Uri): OcrResult {
        val uriString = uri.toString()
        if (t is MlKitException) {
            val code = t.errorCode
            // The on-device model is not present and cannot be downloaded
            // (missing/network/storage/space) — a real model-download state.
            if (code == MlKitException.NOT_FOUND ||
                code == MlKitException.NETWORK_ISSUE ||
                code == MlKitException.NOT_ENOUGH_SPACE ||
                code == MlKitException.RESOURCE_EXHAUSTED
            ) {
                return OcrMapping.modelDownload(t.message ?: "中文识别模型不可用，可能需要下载")
            }
            // The runtime/service is unavailable — a distinct unavailable state.
            if (code == MlKitException.UNAVAILABLE) {
                return OcrMapping.failure(OcrErrorKind.UNAVAILABLE, t.message ?: "ML Kit 服务不可用")
            }
            if (code == MlKitException.CANCELLED) {
                return OcrMapping.failure(OcrErrorKind.CANCELLED, "识别已取消")
            }
            // Any other engine error is a typed ML-Kit failure — never text.
            return OcrMapping.failure(
                OcrErrorKind.MLKIT_ENGINE,
                t.message ?: "ML Kit 处理失败（错误码 $code）",
            )
        }
        return OcrMapping.classifyException {
            when (t) {
                is CancellationException ->
                    OcrErrorKind.CANCELLED to "识别已取消"
                else ->
                    // Never fabricate text: any other failure is a typed failure.
                    OcrErrorKind.MLKIT_ENGINE to (t.message ?: "OCR 处理失败（$uriString）")
            }
        }
    }

    /** Decode a bounded-size bitmap from the resolver, honoring the grant. */
    private fun decodeBounded(
        resolver: ContentResolver,
        uri: Uri,
        input: java.io.InputStream,
        maxDimension: Int,
    ): Bitmap? {
        // Probe bounds once from the stream (must rewind by re-opening from the URI).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeStream(input, null, bounds) }
        runCatching { input.close() }

        val factor = OcrBitmapDecode.sampleFactor(bounds.outWidth, bounds.outHeight, maxDimension)
        if (!OcrBitmapDecode.fitsMemoryBudget(
                bounds.outWidth, bounds.outHeight, factor, MAX_DECODE_BYTES,
            )
        ) {
            return null
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = factor
            inJustDecodeBounds = false
        }
        return runCatching {
            resolver.openInputStream(uri)?.use { real ->
                BitmapFactory.decodeStream(real, null, opts)
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_AWAIT_SECONDS = 30L
        const val MAX_DECODE_BYTES = 64L * 1024L * 1024L // ~64 MB pixel budget
    }
}
