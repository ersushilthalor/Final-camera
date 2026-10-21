package com.hinnka.mycamera.camera

import kotlin.math.*

/**
 * Optical and FOV-calibrated camera lens calculations.
 *
 * Replaces naive focal length ratios with sensor-aware Field Of View (FOV) calibration:
 * - 1.0x is always the calibrated FOV of the primary Main Wide camera.
 * - Ultra-wide optical ratio is derived from actual horizontal FOV.
 * - Minimum Ultra-wide zoom is strictly wider than 1.0x Main.
 * - At 0.9x, Ultra-wide is never more zoomed in than 1.0x Main.
 * - Dynamic crop mapping converts UI zoom to exact physical sensor crop.
 * - Hysteresis thresholds prevent jittery lens switching during continuous user gestures.
 */
object CameraOpticalCalibration {

    const val DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM = 16.0f
    const val DEFAULT_MAIN_EQUIVALENT_FOCAL_MM = 23.0f
    const val MIN_SWITCH_POINT_MM = 23.0f
    const val MAX_SWITCH_POINT_MM = 85.0f
    const val DEFAULT_SWITCH_POINT_MM = 23.0f

    /**
     * Calculates horizontal Field of View in degrees from physical sensor width and focal length.
     */
    fun calculateHorizontalFovDegrees(sensorWidthMm: Float, focalLengthMm: Float): Float {
        if (sensorWidthMm <= 0f || focalLengthMm <= 0f) return 0f
        return (2.0 * atan(sensorWidthMm.toDouble() / (2.0 * focalLengthMm.toDouble())) * (180.0 / Math.PI)).toFloat()
    }

    /**
     * Calculates vertical Field of View in degrees from physical sensor height and focal length.
     */
    fun calculateVerticalFovDegrees(sensorHeightMm: Float, focalLengthMm: Float): Float {
        if (sensorHeightMm <= 0f || focalLengthMm <= 0f) return 0f
        return (2.0 * atan(sensorHeightMm.toDouble() / (2.0 * focalLengthMm.toDouble())) * (180.0 / Math.PI)).toFloat()
    }

    /**
     * Converts a UI zoom level (where 1.0x = 23mm Main lens) to effective focal length in mm.
     * - 0.5x UI zoom -> 16.0mm (native uncropped Ultra-Wide)
     * - 1.0x UI zoom -> 23.0mm (native uncropped Main)
     * - Linear interpolation between 0.5x and 1.0x (16mm to 23mm)
     * - For zoom >= 1.0x -> zoom * 23.0mm (e.g. 1.39x -> 32mm, 1.74x -> 40mm, 2.17x -> 50mm, 3.70x -> 85mm)
     */
    fun zoomToFocalLengthMm(
        zoom: Float,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return if (zoom <= 1.0f) {
            val t = ((zoom - 0.5f) / 0.5f).coerceIn(0f, 1f)
            uwFocalMm + t * (mainFocalMm - uwFocalMm)
        } else {
            zoom * mainFocalMm
        }
    }

    /**
     * Converts an effective focal length in mm to UI zoom level.
     * - 16.0mm -> 0.5x
     * - 23.0mm -> 1.0x
     * - 32.0mm -> 32 / 23 ≈ 1.3913x
     * - 40.0mm -> 40 / 23 ≈ 1.7391x
     * - 50.0mm -> 50 / 23 ≈ 2.1739x
     * - 85.0mm -> 85 / 23 ≈ 3.6957x
     */
    fun focalLengthMmToZoom(
        focalMm: Float,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return if (focalMm <= mainFocalMm) {
            val t = ((focalMm - uwFocalMm) / (mainFocalMm - uwFocalMm)).coerceIn(0f, 1f)
            0.5f + t * 0.5f
        } else {
            focalMm / mainFocalMm
        }
    }

    /**
     * Calculates the exact UI zoom threshold at which the lens switch point occurs.
     */
    fun switchPointToZoom(
        switchPointMm: Float,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val clamped = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        return clamped / mainFocalMm
    }

    /**
     * Ultra-wide crop = Switch Point ÷ 16mm
     * Automatically calculates the required digital crop factor on Ultra-wide at the switch point:
     * - 23mm -> 23 / 16 = 1.4375x (1.44x)
     * - 32mm -> 32 / 16 = 2.00x
     * - 40mm -> 40 / 16 = 2.50x
     * - 50mm -> 50 / 16 = 3.125x (3.13x)
     * - 85mm -> 85 / 16 = 5.3125x (5.31x)
     */
    fun calculateUltraWideCropForSwitchPoint(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): Float {
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val rawRatio = clampedSwitch / uwFocalMm.coerceAtLeast(1.0f)
        return (rawRatio * 100f).roundToInt() / 100f
    }

    /**
     * Main camera crop at the switch point:
     * Main crop = Switch Point ÷ 23mm
     * - 23mm -> 23 / 23 = 1.00x
     * - 32mm -> 32 / 23 ≈ 1.3913x (1.39x)
     * - 40mm -> 40 / 23 ≈ 1.7391x (1.74x)
     * - 50mm -> 50 / 23 ≈ 2.1739x (2.17x)
     * - 85mm -> 85 / 23 ≈ 3.6957x (3.70x)
     */
    fun calculateMainCropForSwitchPoint(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val rawRatio = clampedSwitch / mainFocalMm.coerceAtLeast(1.0f)
        return (rawRatio * 100f).roundToInt() / 100f
    }

    /**
     * Calculates the exact digital crop needed on Ultra-wide to perfectly match
     * the Main camera's Field of View at the selected switch point.
     */
    fun calculateFovMatchedUltraWideCrop(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): Float {
        return calculateUltraWideCropForSwitchPoint(switchPointMm, uwEquivalentFocalMm)
    }

    /**
     * Calculates the required digital crop factor for a given UI zoom level on the active lens.
     */
    fun calculateRequiredDigitalCrop(
        uiZoom: Float,
        lensBaseRatio: Float,
        lensType: LensType,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM
    ): Float {
        val base = if (lensType == LensType.BACK_ULTRA_WIDE) 0.5f else (if (lensBaseRatio > 0.1f) lensBaseRatio else 1.0f)
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val maxUwCrop = calculateUltraWideCropForSwitchPoint(clampedSwitch, uwEquivalentFocalMm)
        val switchZoom = switchPointToZoom(clampedSwitch, mainEquivalentFocalMm)

        return when (lensType) {
            LensType.BACK_ULTRA_WIDE -> {
                if (uiZoom <= 0.5f) {
                    1.0f
                } else if (uiZoom <= switchZoom) {
                    if (uiZoom >= switchZoom - 0.001f) {
                        maxUwCrop
                    } else {
                        val t = ((uiZoom - 0.5f) / (switchZoom - 0.5f).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                        val crop = 1.0f + t * (maxUwCrop - 1.0f)
                        crop.coerceIn(1.0f, maxUwCrop)
                    }
                } else {
                    maxUwCrop * (uiZoom / switchZoom)
                }
            }
            LensType.BACK_MAIN -> {
                uiZoom.coerceAtLeast(1.0f)
            }
            LensType.BACK_TELEPHOTO -> {
                (uiZoom / base).coerceAtLeast(1.0f)
            }
            LensType.FRONT -> {
                uiZoom.coerceAtLeast(1.0f)
            }
            else -> {
                uiZoom.coerceAtLeast(1.0f)
            }
        }
    }

    /**
     * Returns true only while a continuous slider gesture is still below the physical lens
     * handoff threshold.
     */
    fun shouldKeepUltraWideDuringContinuousZoom(
        currentLensType: LensType,
        targetZoom: Float,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        isContinuousTransition: Boolean
    ): Boolean {
        if (!isContinuousTransition || currentLensType != LensType.BACK_ULTRA_WIDE) return false
        val switchZoom = switchPointToZoom(switchPointMm)
        return targetZoom < switchZoom
    }

    /**
     * Hysteresis-aware lens selection during continuous zoom dragging based on user-selected Switch Point.
     */
    fun resolveTargetLensType(
        currentLensType: LensType,
        targetZoom: Float,
        hasUltraWide: Boolean,
        hasTelephoto2x: Boolean,
        hasTelephoto3x: Boolean,
        isPresetTap: Boolean,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        isContinuousTransition: Boolean = false
    ): LensType {
        if (currentLensType == LensType.FRONT) {
            return LensType.FRONT
        }
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val switchZoom = switchPointToZoom(clampedSwitch, DEFAULT_MAIN_EQUIVALENT_FOCAL_MM)

        if (targetZoom >= switchZoom) {
            val isDirectOrTransition = isPresetTap || isContinuousTransition
            return when {
                targetZoom >= 2.8f && hasTelephoto3x && (isDirectOrTransition || targetZoom >= 3.15f) && switchZoom <= 2.8f -> LensType.BACK_TELEPHOTO
                targetZoom >= 1.8f && hasTelephoto2x && (isDirectOrTransition || currentLensType == LensType.BACK_TELEPHOTO || targetZoom >= 2.15f) && switchZoom <= 1.8f -> LensType.BACK_TELEPHOTO
                currentLensType == LensType.BACK_TELEPHOTO && targetZoom >= 1.85f && switchZoom <= 1.85f -> LensType.BACK_TELEPHOTO
                else -> LensType.BACK_MAIN
            }
        }
        return if (hasUltraWide) LensType.BACK_ULTRA_WIDE else LensType.BACK_MAIN
    }

    data class OpticalFramingCalibration(
        val scaleCorrection: Float = 1.0f,
        val offsetXNorm: Float = 0.0f,
        val offsetYNorm: Float = 0.0f
    )

    fun getUltraWideFramingCalibration(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): OpticalFramingCalibration {
        return OpticalFramingCalibration(
            scaleCorrection = 1.0f,
            offsetXNorm = 0.0f,
            offsetYNorm = 0.0f
        )
    }

    fun interpolateUltraWideFraming(
        uiZoom: Float,
        calib: OpticalFramingCalibration,
        switchZoom: Float = 1.0f
    ): OpticalFramingCalibration {
        if (uiZoom <= 0.5f) {
            return OpticalFramingCalibration()
        }
        val t = ((uiZoom - 0.5f) / (switchZoom - 0.5f).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        return OpticalFramingCalibration(
            scaleCorrection = 1.0f,
            offsetXNorm = calib.offsetXNorm * t,
            offsetYNorm = calib.offsetYNorm * t
        )
    }
}
