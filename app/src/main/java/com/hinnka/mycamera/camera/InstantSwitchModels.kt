package com.hinnka.mycamera.camera

import com.hinnka.mycamera.camera.LensType

/**
 * Preview stream source identifier for instant lens switching.
 */
enum class PreviewStreamSource {
    MAIN,
    ULTRAWIDE
}

/**
 * Status of the standby background camera stream.
 */
enum class BackgroundCameraStatus(val label: String, val shortDesc: String) {
    OFF("Off", "Standby Disabled"),
    PREPARING("Preparing", "Starting background stream..."),
    READY_QUIET("Ready (Quiet)", "Ready in background (invisible)"),
    READY_PREVIEW("Live Preview", "Streaming to little preview"),
    UNAVAILABLE("Unavailable", "Camera not present or in use")
}

/**
 * High-level state representing instant camera switching and background lens readiness.
 */
data class MotorolaInstantSwitchState(
    val isKeepUltraWideReady: Boolean = true,
    val isAutoSwitchToUltraWide: Boolean = false,
    val isAutoMacroActive: Boolean = false,
    val isShowUltraWidePreview: Boolean = false,
    val isKeepFrontCameraReady: Boolean = false,
    val isShowFrontCameraPreview: Boolean = false,
    val ultraWideStatus: BackgroundCameraStatus = BackgroundCameraStatus.OFF,
    val frontStatus: BackgroundCameraStatus = BackgroundCameraStatus.OFF,
    val isMotorolaDevice: Boolean = false,
    val isConcurrentHardwareSupported: Boolean = true,
    val activeStandbyLens: LensType? = null,
    val switchLatencyEstimateMs: Int = 0,
    val lastMeasuredLatencyMs: Long = 0L,
    val statusMessage: String = "Instant Switching Ready",
    val displayedPreviewSource: PreviewStreamSource = PreviewStreamSource.MAIN,
    val lensSwitchOverlapDurationSec: Float = 0.3f,
    val lensSwitchHoldDurationSec: Float = 0.02f,
    val isLensSwitchOverlapAnimationEnabled: Boolean = true,
    val lensSwitchPointMm: Float = 23.0f
)

/**
 * Preview layer state for legacy overlap and frame-based lens handoff.
 */
data class PreviewOverlapState(
    val isOverlapping: Boolean = false,
    val mainAlpha: Float = 1.0f,
    val ultraWideAlpha: Float = 0.0f,
    val activeSource: PreviewStreamSource = PreviewStreamSource.MAIN,
    val overlapProgress: Float = 0.0f,
    val topSource: PreviewStreamSource = PreviewStreamSource.MAIN
)

/**
 * Strategy for switching between lenses.
 */
enum class LensSwitchStrategy {
    SAME_DEVICE,
    INDEPENDENT_DEVICE,
    LOGICAL_PHYSICAL_STREAM,
    LOGICAL_ZOOM
}
