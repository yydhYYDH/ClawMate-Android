package com.clawmate.app.gallery.analysis

import com.clawmate.app.gallery.geocode.GalleryLocationInfo
import com.clawmate.app.gallery.geocode.ReverseGeocodeError
import com.clawmate.app.gallery.geocode.ReverseGeocodeOutcome
import com.clawmate.app.gallery.model.GalleryPhotoItem

/**
 * OCR / cloud-vision / reverse-geocoding capability boundaries.
 *
 * Honesty first — a capability is only ever claimed when it really works:
 *   - OCR: Harmony CoreVisionKit `textRecognition` maps on Android to on-device
 *     ML Kit `com.google.mlkit:text-recognition-chinese` (16.0.1, resolved on
 *     Google Maven in this worktree). Phase 11 wires a real Android adapter
 *     ([com.clawmate.app.gallery.ocr.AndroidOcrEngine]) that opens the picked
 *     content URI via ContentResolver, decodes a bounded-size Bitmap, and runs
 *     on-device Chinese text recognition. It maps every outcome to the typed
 *     [OcrResult] states (success/empty/cancel/model-download/unavailable/error)
 *     and never fabricates text. ML Kit's runtime + model download (via Play
 *     services) still require a real device to exercise; that is documented, and
 *     the deterministic mapping is covered by device-free unit tests.
 *   - Cloud vision uses the configured gallery model through the explicit-consent
 *     GalleryVisionViewModel/Client pipeline. This older synchronous capability
 *     interface remains an unavailable fallback; it never invents a result.
 *   - Reverse geocoding: Harmony uses AMap Web Service (keyed). On Android the
 *     same concept is served by the platform [android.location.Geocoder] with no
 *     key; the Android adapter is wired as an explicit injected capability (see
 *     com.clawmate.app.gallery.geocode). It reports [CapabilityState.Ready] only
 *     when the device has a geocoder and never claims a location on failure
 *     (unchanged).
 *
 * Each capability is behind an explicit interface. Callers receive a typed
 * [CapabilityState] describing NOT_CONFIGURED / UNSUPPORTED / READY and must
 * surface it to the user. No fake OCR / vision text or location is ever produced.
 */
sealed class CapabilityState {
    /** The capability exists but is not configured (no key / no endpoint). */
    data object NotConfigured : CapabilityState()

    /** The platform cannot support this capability in this build. */
    data object Unsupported : CapabilityState()

    /** Adapter is available for an explicit attempt; this is not proof of remote service success. */
    data class Ready(val description: String) : CapabilityState()
}

/**
 * Port of Harmony `GalleryOcrService` (CoreVisionKit text recognition).
 * On Android this maps to on-device ML Kit Chinese Text Recognition
 * (com.google.mlkit:text-recognition-chinese) via the real adapter in
 * [com.clawmate.app.gallery.ocr.AndroidOcrEngine]. Implementations must return
 * a [CapabilityState] and only ever return real recognized text through their
 * concrete result type — no fake text is produced.
 */
interface OcrEngine {
    val state: CapabilityState

    /**
     * Recognize text in the photo's content URI. Implementations that hit the
     * platform (e.g. ML Kit) perform real on-device recognition; this is a
     * suspend function so the platform call is never run on the caller's thread.
     * Success produces [OcrResult.Text] only for genuinely recognized text;
     * every non-success path returns a typed non-text [OcrResult].
     */
    suspend fun recognize(photo: GalleryPhotoItem): OcrResult
}

/**
 * Port of the gallery cloud vision analysis step (multimodal model that
 * extracts title/category/summary/tags/details from a photo). The HarmonyOS
 * source calls a cloud model with an API key; that is not configured on
 * Android yet, so the default implementation reports NOT_CONFIGURED.
 */
interface CloudVisionAnalyzer {
    val state: CapabilityState
    fun analyze(photo: GalleryPhotoItem): VisionResult
}

sealed class VisionResult {
    data object NotConfigured : VisionResult()
    data object Unsupported : VisionResult()
    data class Failed(val reason: String) : VisionResult()
}

/**
 * Port of Harmony `GalleryReverseGeocoder`. On Android this maps to the
 * platform [android.location.Geocoder] (a local heuristics lookup the device
 * exposes; no web-service key). A provider is always injected explicitly; a
 * caller may inject the default NOT_CONFIGURED engine or the Android adapter
 * ([com.clawmate.app.gallery.geocode.AndroidReverseGeocoder]).
 */
interface ReverseGeocoder {
    val state: CapabilityState
    fun reverse(latitude: Double, longitude: Double): ReverseGeocodeResult
}

sealed class ReverseGeocodeResult {
    /** The provider is not configured (default). */
    data object NotConfigured : ReverseGeocodeResult()

    /** A real place was resolved (never fabricated). */
    data class Resolved(val location: GalleryLocationInfo) : ReverseGeocodeResult()

    /** The platform has no geocoder / the lookup failed for the given reason. */
    data class Failed(val error: ReverseGeocodeError) : ReverseGeocodeResult()
}

/**
 * Direct adapter from the typed [com.clawmate.app.gallery.geocode]
 * ReverseGeocodeOutcome to the capability-boundary [ReverseGeocodeResult].
 */
fun ReverseGeocodeOutcome.asCapabilityResult(): ReverseGeocodeResult = when (this) {
    is ReverseGeocodeOutcome.Resolved -> ReverseGeocodeResult.Resolved(location)
    is ReverseGeocodeOutcome.Failed -> ReverseGeocodeResult.Failed(error)
    ReverseGeocodeOutcome.NotConfigured -> ReverseGeocodeResult.NotConfigured
}

/** Default unconfigured engine instances (controllable, no fake data). */
object UnconfiguredCapabilities {
    val ocr: OcrEngine = UnconfiguredOcrEngine
    val vision: CloudVisionAnalyzer = UnconfiguredVisionAnalyzer
    val reverseGeocoder: ReverseGeocoder = UnconfiguredReverseGeocoder
}

private object UnconfiguredOcrEngine : OcrEngine {
    override val state: CapabilityState = CapabilityState.NotConfigured
    override suspend fun recognize(photo: GalleryPhotoItem): OcrResult =
        OcrResult.Unavailable("on-device OCR 尚未配置（需接入本地 OCR 引擎）")
}

private object UnconfiguredVisionAnalyzer : CloudVisionAnalyzer {
    override val state: CapabilityState = CapabilityState.NotConfigured
    override fun analyze(photo: GalleryPhotoItem): VisionResult = VisionResult.NotConfigured
}

private object UnconfiguredReverseGeocoder : ReverseGeocoder {
    override val state: CapabilityState = CapabilityState.NotConfigured
    override fun reverse(latitude: Double, longitude: Double): ReverseGeocodeResult =
        ReverseGeocodeResult.NotConfigured
}
