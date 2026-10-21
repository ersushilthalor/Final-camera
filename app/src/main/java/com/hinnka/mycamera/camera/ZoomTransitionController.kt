package com.hinnka.mycamera.camera

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

class ZoomTransitionController(
    private val onApplyZoomToEngine: (zoom: Float, isContinuous: Boolean) -> Unit = { _, _ -> },
    private val onSelectLensOnEngine: (lens: CameraInfo, targetZoom: Float, isContinuous: Boolean) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "ZoomTransitionCtrl"
        const val TOTAL_TRANSITION_DURATION_MS = 400L
        const val DEFAULT_HOLD_AT_ONE_X_DURATION_MS = 200L
        const val HOLD_AT_ONE_X_DURATION_MS = 20L
        const val DEFAULT_OVERLAP_DURATION_MS = 300L

        const val FRAME_HANDOFF_WARMUP_FRAMES = 2
        const val FRAME_HANDOFF_REQUIRED_FRAMES = 16
        const val FRAME_HANDOFF_TIMEOUT_MS = 650L
        private const val MAIN_FOV_DIVERGENCE_FADE_RANGE = 0.15f
        private const val MAX_HANDOFF_ALPHA_STEP = 0.20f

        fun isHalfXToTelephotoPresetTransition(fromZoom: Float, targetZoom: Float): Boolean =
            fromZoom <= 0.6f && targetZoom >= 1.9f

        fun isTelephotoPresetToHalfXTransition(fromZoom: Float, targetZoom: Float): Boolean =
            fromZoom >= 1.9f && targetZoom <= 0.6f

        internal fun calculateFrameHandoffProgress(
            incomingFrameCount: Int,
            targetSource: PreviewStreamSource,
            currentZoom: Float,
            previousProgress: Float = 0f
        ): Float {
            val frameProgress = incomingFrameCount.coerceIn(0, FRAME_HANDOFF_REQUIRED_FRAMES).toFloat() /
                FRAME_HANDOFF_REQUIRED_FRAMES.toFloat()
            val frameEased = (0.5 * (1.0 - cos(PI * frameProgress.toDouble()))).toFloat()

            val zoomEased = if (targetSource == PreviewStreamSource.ULTRAWIDE) {
                val zoomProgress = ((1.0f - currentZoom) / (1.0f - 0.5f)).coerceIn(0f, 1f)
                val acceleratedZoomProgress = 1f - (1f - zoomProgress) * (1f - zoomProgress) * (1f - zoomProgress)
                (0.5 * (1.0 - cos(PI * acceleratedZoomProgress.toDouble()))).toFloat()
            } else {
                val zoomProgress = ((currentZoom - 1.0f) / MAIN_FOV_DIVERGENCE_FADE_RANGE).coerceIn(0f, 1f)
                val acceleratedZoomProgress = 1f - (1f - zoomProgress) * (1f - zoomProgress) * (1f - zoomProgress)
                (0.5 * (1.0 - cos(PI * acceleratedZoomProgress.toDouble()))).toFloat()
            }

            val previous = previousProgress.coerceIn(0f, 1f)
            val desired = maxOf(frameEased, zoomEased)
            return minOf(desired, previous + MAX_HANDOFF_ALPHA_STEP).coerceIn(previous, 1f)
        }

        fun isFrameBasedHandoffEligible(fromZoom: Float, targetZoom: Float): Boolean =
            isHalfXToTelephotoPresetTransition(fromZoom, targetZoom) ||
                isTelephotoPresetToHalfXTransition(fromZoom, targetZoom)
    }

    var overlapDurationMs: Long = DEFAULT_OVERLAP_DURATION_MS
    var holdAtOneXDurationMs: Long = DEFAULT_HOLD_AT_ONE_X_DURATION_MS
    var isOverlapAnimationEnabled: Boolean = true

    private val _isTransitionActive = MutableStateFlow(false)
    val isTransitionActive: StateFlow<Boolean> = _isTransitionActive.asStateFlow()

    private val _currentInterpolatedZoom = MutableStateFlow(1.0f)
    val currentInterpolatedZoom: StateFlow<Float> = _currentInterpolatedZoom.asStateFlow()

    private val _previewOverlapState = MutableStateFlow(PreviewOverlapState())
    val previewOverlapState: StateFlow<PreviewOverlapState> = _previewOverlapState.asStateFlow()

    private var pendingFrameHandoffTarget: PreviewStreamSource? = null
    private var frameHandoffFromSource: PreviewStreamSource? = null
    private var frameHandoffFrameCount: Int = 0
    private var frameHandoffTimeoutJob: Job? = null

    private var preparedFrameHandoffFromSource: PreviewStreamSource? = null
    private var preparedFrameHandoffTargetSource: PreviewStreamSource? = null
    private var preparedTargetFrameCount: Int = 0
    private var preparedTargetLastFrameAtMs: Long = 0L

    private var activeTransitionJob: Job? = null

    fun setSteadyStatePreview(isUltraWide: Boolean) {
        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = false,
            mainAlpha = if (isUltraWide) 0.0f else 1.0f,
            ultraWideAlpha = if (isUltraWide) 1.0f else 0.0f,
            activeSource = if (isUltraWide) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN,
            overlapProgress = 0.0f,
            topSource = if (isUltraWide) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
        )
    }

    fun prepareFrameBasedHandoff(
        fromSource: PreviewStreamSource,
        targetSource: PreviewStreamSource
    ) {
        if (fromSource == targetSource) {
            clearPreparedFrameHandoff()
            return
        }
        preparedFrameHandoffFromSource = fromSource
        preparedFrameHandoffTargetSource = targetSource
        preparedTargetFrameCount = 0
        preparedTargetLastFrameAtMs = 0L
    }

    private fun clearPreparedFrameHandoff() {
        preparedFrameHandoffFromSource = null
        preparedFrameHandoffTargetSource = null
        preparedTargetFrameCount = 0
        preparedTargetLastFrameAtMs = 0L
    }

    fun beginFrameBasedHandoff(
        fromSource: PreviewStreamSource,
        targetSource: PreviewStreamSource,
        scope: CoroutineScope
    ) {
        frameHandoffTimeoutJob?.cancel()
        val nowMs = SystemClock.uptimeMillis()
        val hasFreshPreparedTargetFrames =
            preparedFrameHandoffFromSource == fromSource &&
                preparedFrameHandoffTargetSource == targetSource &&
                preparedTargetFrameCount >= FRAME_HANDOFF_WARMUP_FRAMES &&
                preparedTargetLastFrameAtMs > 0L &&
                nowMs - preparedTargetLastFrameAtMs <= 500L

        clearPreparedFrameHandoff()

        if (fromSource == targetSource) {
            pendingFrameHandoffTarget = null
            frameHandoffFromSource = null
            setSteadyStatePreview(targetSource == PreviewStreamSource.ULTRAWIDE)
            return
        }

        frameHandoffFromSource = fromSource
        pendingFrameHandoffTarget = targetSource
        frameHandoffFrameCount = if (hasFreshPreparedTargetFrames) FRAME_HANDOFF_WARMUP_FRAMES else 0

        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = true,
            mainAlpha = if (fromSource == PreviewStreamSource.MAIN) 1f else 0f,
            ultraWideAlpha = if (fromSource == PreviewStreamSource.ULTRAWIDE) 1f else 0f,
            activeSource = targetSource,
            overlapProgress = 0f,
            topSource = fromSource
        )

        frameHandoffTimeoutJob = scope.launch {
            delay(FRAME_HANDOFF_TIMEOUT_MS)
            if (pendingFrameHandoffTarget == targetSource) {
                pendingFrameHandoffTarget = null
                frameHandoffFromSource = null
                frameHandoffTimeoutJob = null
                setSteadyStatePreview(targetSource == PreviewStreamSource.ULTRAWIDE)
            }
        }
    }

    fun onPreviewFrameAvailable(source: PreviewStreamSource) {
        val targetSource = pendingFrameHandoffTarget
        if (targetSource == null) {
            if (source == preparedFrameHandoffTargetSource) {
                preparedTargetFrameCount =
                    (preparedTargetFrameCount + 1).coerceAtMost(FRAME_HANDOFF_WARMUP_FRAMES)
                preparedTargetLastFrameAtMs = SystemClock.uptimeMillis()
            }
            return
        }
        if (source != targetSource) return

        frameHandoffFrameCount = (frameHandoffFrameCount + 1).coerceAtMost(
            FRAME_HANDOFF_WARMUP_FRAMES + FRAME_HANDOFF_REQUIRED_FRAMES
        )
        val fromSource = frameHandoffFromSource ?: return

        if (frameHandoffFrameCount <= FRAME_HANDOFF_WARMUP_FRAMES) {
            _previewOverlapState.value = PreviewOverlapState(
                isOverlapping = true,
                mainAlpha = if (fromSource == PreviewStreamSource.MAIN) 1f else 0f,
                ultraWideAlpha = if (fromSource == PreviewStreamSource.ULTRAWIDE) 1f else 0f,
                activeSource = targetSource,
                overlapProgress = 0f,
                topSource = fromSource
            )
            return
        }

        val blendFrameCount = frameHandoffFrameCount - FRAME_HANDOFF_WARMUP_FRAMES
        val previousProgress = _previewOverlapState.value.overlapProgress
        val progress = calculateFrameHandoffProgress(
            incomingFrameCount = blendFrameCount,
            targetSource = targetSource,
            currentZoom = _currentInterpolatedZoom.value,
            previousProgress = previousProgress
        )

        val mainAlpha = if (targetSource == PreviewStreamSource.MAIN) progress else 1f
        val ultraWideAlpha = if (targetSource == PreviewStreamSource.ULTRAWIDE) progress else 1f

        _previewOverlapState.value = PreviewOverlapState(
            isOverlapping = true,
            mainAlpha = mainAlpha,
            ultraWideAlpha = ultraWideAlpha,
            activeSource = targetSource,
            overlapProgress = progress,
            topSource = targetSource
        )

        if (blendFrameCount >= FRAME_HANDOFF_REQUIRED_FRAMES) {
            frameHandoffTimeoutJob?.cancel()
            frameHandoffTimeoutJob = null
            pendingFrameHandoffTarget = null
            frameHandoffFromSource = null
            setSteadyStatePreview(targetSource == PreviewStreamSource.ULTRAWIDE)
        }
    }

    fun cancelTransition() {
        activeTransitionJob?.cancel()
        activeTransitionJob = null
        _isTransitionActive.value = false
        frameHandoffTimeoutJob?.cancel()
        frameHandoffTimeoutJob = null
        pendingFrameHandoffTarget = null
        frameHandoffFromSource = null
        frameHandoffFrameCount = 0
        clearPreparedFrameHandoff()
    }

    fun startTransition(
        fromZoom: Float,
        targetZoom: Float,
        targetLens: CameraInfo?,
        availableLenses: List<CameraInfo>,
        currentLens: CameraInfo?,
        switchPointMm: Float = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM,
        scope: CoroutineScope,
        durationMs: Long = TOTAL_TRANSITION_DURATION_MS,
        skipHoldAtSwitchPoint: Boolean = false,
        onZoomUpdate: (Float) -> Unit,
        onComplete: (Float, CameraInfo?) -> Unit
    ): Job {
        cancelTransition()
        val transitionDurationMs = maxOf(TOTAL_TRANSITION_DURATION_MS, durationMs)
        val job = scope.launch(Dispatchers.Main.immediate) {
            _isTransitionActive.value = true
            val startZ = fromZoom.coerceAtLeast(0.1f)
            val endZ = targetZoom.coerceAtLeast(0.1f)
            val switchZoom = CameraOpticalCalibration.switchPointToZoom(switchPointMm)

            val startTime = SystemClock.uptimeMillis()
            var lastDispatchedZoom = startZ
            var lastDispatchedTime = 0L

            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val elapsed = now - startTime
                if (elapsed >= transitionDurationMs) break

                val linearProgress = (elapsed.toDouble() / transitionDurationMs.toDouble()).coerceIn(0.0, 1.0)
                val smoothProgress = (0.5 * (1.0 - cos(PI * linearProgress))).toFloat().coerceIn(0f, 1f)
                val currentZ = startZ + (endZ - startZ) * smoothProgress

                if (abs(currentZ - lastDispatchedZoom) >= 0.01f || (now - lastDispatchedTime) >= 16L) {
                    lastDispatchedZoom = currentZ
                    lastDispatchedTime = now
                    _currentInterpolatedZoom.value = currentZ
                    onZoomUpdate(currentZ)
                    onApplyZoomToEngine(currentZ, true)
                }

                val nextTick = minOf(startTime + transitionDurationMs, now + 16L)
                val waitMs = nextTick - SystemClock.uptimeMillis()
                if (waitMs > 0) delay(waitMs)
            }

            _currentInterpolatedZoom.value = endZ
            onZoomUpdate(endZ)
            onApplyZoomToEngine(endZ, false)
            _isTransitionActive.value = false
            activeTransitionJob = null
            onComplete(endZ, targetLens)
        }
        activeTransitionJob = job
        return job
    }
}
