package com.clawmate.app.gallery.ocr

import android.content.Context
import com.clawmate.app.gallery.analysis.CapabilityState
import com.clawmate.app.gallery.analysis.OcrEngine
import com.clawmate.app.gallery.analysis.OcrResult
import com.clawmate.app.gallery.model.GalleryPhotoItem

/** Compose domain adapter; the actual OCR engine is shared with ArkTS. */
class AndroidOcrEngine(context: Context) : OcrEngine {
    private val recognizer = LocalOcrRecognizer(context)
    override val state: CapabilityState
        get() = if (recognizer.available) CapabilityState.Ready("ML Kit 中文文本识别") else CapabilityState.Unsupported
    override suspend fun recognize(photo: GalleryPhotoItem): OcrResult = recognizer.recognize(photo.uri)
}

fun androidOcrEngine(context: Context): OcrEngine = AndroidOcrEngine(context)
