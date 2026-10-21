package com.hinnka.mycamera.stabilization

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software implementation of the EIS frame-engine contract.
 *
 * The previous bridge required a prebuilt libmy-native-lib.so which was not present in this
 * repository or APK. That made Session.start() fail every time; LutRenderer then cleared the
 * viewfinder while waiting for a stabilized frame. This implementation keeps the same API but
 * computes rolling-shutter, gyro-based frame transforms in Kotlin, so EIS+ is actually executable
 * from the source that is shipped in the APK.
 */
internal object MgcEisNativeBridge {
    private const val MAX_GYRO_STEP_NS = 50_000_000L
    private const val POSE_HISTORY_NS = 8_000_000_000L
    private const val OFFSET_HISTORY_NS = 1_000_000_000L
    private const val MAX_OFFSET_SAMPLES = 512
    private const val MAX_INTRINSICS_SAMPLES = 128
    private const val BASE_CROP_MARGIN = 0.05
    private const val MAX_CLIP_TRANSLATION = 0.20
    private const val MIN_CLIP_FOCAL = 0.25
    private const val MAX_CLIP_FOCAL = 12.0

    private val nextHandle = AtomicLong(1L)
    private val engines = ConcurrentHashMap<Long, EngineState>()

    fun create(
        width: Int,
        height: Int,
        frontFacing: Boolean,
        strength: Float,
        lookaheadFrames: Int,
    ): Long {
        if (width <= 0 || height <= 0) return 0L
        val handle = nextHandle.getAndIncrement().takeIf { it > 0L } ?: return 0L
        engines[handle] = EngineState(
            width = width,
            height = height,
            frontFacing = frontFacing,
            strength = normalizeStabilizationStrength(strength),
            lookaheadFrames = normalizeStabilizationLookahead(lookaheadFrames),
        )
        return handle
    }

    fun release(handle: Long) {
        engines.remove(handle)?.let { engine ->
            synchronized(engine) {
                engine.clear()
            }
        }
    }

    fun processGyro(
        handle: Long,
        x: Float,
        y: Float,
        z: Float,
        timestampNs: Long,
    ): Boolean {
        val engine = engines[handle] ?: return false
        synchronized(engine) {
            return engine.addGyro(x, y, z, timestampNs)
        }
    }

    fun processLensOffset(
        handle: Long,
        xShiftPixels: Float,
        yShiftPixels: Float,
        timestampNs: Long,
        cameraType: Int,
    ): Boolean {
        val engine = engines[handle] ?: return false
        synchronized(engine) {
            return engine.addLensOffset(
                xShiftPixels = xShiftPixels,
                yShiftPixels = yShiftPixels,
                timestampNs = timestampNs,
                cameraType = cameraType,
            )
        }
    }

    fun processLensIntrinsics(
        handle: Long,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        skew: Float,
        timestampNs: Long,
        cameraType: Int,
    ): Boolean {
        val engine = engines[handle] ?: return false
        synchronized(engine) {
            return engine.addLensIntrinsics(
                values = floatArrayOf(fx, fy, cx, cy, skew),
                timestampNs = timestampNs,
                cameraType = cameraType,
            )
        }
    }

    fun processFrame(
        handle: Long,
        sourceTimestampNs: Long,
        firstRowCenterTimestampNs: Long,
        exposureTimeNs: Long,
        frameDurationNs: Long,
        rollingShutterSkewNs: Long,
        inverseFocalLength: Float,
        activeWidth: Int,
        activeHeight: Int,
        cropWidth: Int,
        cropHeight: Int,
        preCorrectionActiveWidth: Int,
        preCorrectionActiveHeight: Int,
        nominalLensIntrinsics: FloatArray?,
        rowHomographies: FloatArray,
        state: FloatArray,
    ): Long {
        val engine = engines[handle] ?: return droppedTimestamp(sourceTimestampNs)
        synchronized(engine) {
            return engine.processFrame(
                frame = FrameInput(
                    sourceTimestampNs = sourceTimestampNs,
                    firstRowCenterTimestampNs = firstRowCenterTimestampNs,
                    exposureTimeNs = exposureTimeNs,
                    frameDurationNs = frameDurationNs,
                    rollingShutterSkewNs = rollingShutterSkewNs,
                    inverseFocalLength = inverseFocalLength,
                    activeWidth = activeWidth,
                    activeHeight = activeHeight,
                    cropWidth = cropWidth,
                    cropHeight = cropHeight,
                    preCorrectionActiveWidth = preCorrectionActiveWidth,
                    preCorrectionActiveHeight = preCorrectionActiveHeight,
                    nominalLensIntrinsics = nominalLensIntrinsics?.copyOf(),
                ),
                rowHomographies = rowHomographies,
                resultState = state,
            )
        }
    }

    private fun droppedTimestamp(timestampNs: Long): Long = -timestampNs.coerceAtLeast(2L)

    private data class GyroSample(
        val timestampNs: Long,
        val x: Double,
        val y: Double,
        val z: Double,
    )

    private data class PoseSample(
        val timestampNs: Long,
        val orientation: Quaternion,
    )

    private data class LensOffsetSample(
        val timestampNs: Long,
        val xPixels: Float,
        val yPixels: Float,
        val cameraType: Int,
    )

    private data class LensIntrinsicsSample(
        val timestampNs: Long,
        val values: FloatArray,
        val cameraType: Int,
    )

    private data class FrameInput(
        val sourceTimestampNs: Long,
        val firstRowCenterTimestampNs: Long,
        val exposureTimeNs: Long,
        val frameDurationNs: Long,
        val rollingShutterSkewNs: Long,
        val inverseFocalLength: Float,
        val activeWidth: Int,
        val activeHeight: Int,
        val cropWidth: Int,
        val cropHeight: Int,
        val preCorrectionActiveWidth: Int,
        val preCorrectionActiveHeight: Int,
        val nominalLensIntrinsics: FloatArray?,
    )

    private class EngineState(
        private val width: Int,
        private val height: Int,
        private val frontFacing: Boolean,
        private val strength: Float,
        private val lookaheadFrames: Int,
    ) {
        private val gyroSamples = ArrayList<GyroSample>()
        private val poseSamples = ArrayList<PoseSample>()
        private var oldestPoseIndex = 0
        private val lensOffsets = ArrayDeque<LensOffsetSample>()
        private val lensIntrinsics = ArrayDeque<LensIntrinsicsSample>()
        private val pendingFrames = ArrayDeque<FrameInput>()
        private var orientation = Quaternion.IDENTITY
        private var lastGyro: GyroSample? = null

        fun clear() {
            gyroSamples.clear()
            poseSamples.clear()
            oldestPoseIndex = 0
            lensOffsets.clear()
            lensIntrinsics.clear()
            pendingFrames.clear()
            orientation = Quaternion.IDENTITY
            lastGyro = null
        }

        fun addGyro(x: Float, y: Float, z: Float, timestampNs: Long): Boolean {
            if (timestampNs <= 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
                return false
            }
            val sample = GyroSample(
                timestampNs = timestampNs,
                x = x.toDouble(),
                y = y.toDouble(),
                z = z.toDouble(),
            )
            val previous = lastGyro
            if (previous != null && timestampNs <= previous.timestampNs) return false

            if (previous == null) {
                orientation = Quaternion.IDENTITY
                poseSamples.add(PoseSample(timestampNs, orientation))
            } else {
                val elapsedNs = (timestampNs - previous.timestampNs)
                    .coerceAtMost(MAX_GYRO_STEP_NS)
                val elapsedSeconds = elapsedNs.toDouble() / 1_000_000_000.0
                val rx = (previous.x + sample.x) * 0.5 * elapsedSeconds
                val ry = (previous.y + sample.y) * 0.5 * elapsedSeconds
                val rz = (previous.z + sample.z) * 0.5 * elapsedSeconds
                orientation = (orientation * Quaternion.fromRotationVector(rx, ry, rz)).normalized()
                poseSamples.add(PoseSample(timestampNs, orientation))
            }
            gyroSamples.add(sample)
            lastGyro = sample
            trimHistories(timestampNs)
            return true
        }

        fun addLensOffset(
            xShiftPixels: Float,
            yShiftPixels: Float,
            timestampNs: Long,
            cameraType: Int,
        ): Boolean {
            if (timestampNs <= 0L || !xShiftPixels.isFinite() || !yShiftPixels.isFinite()) {
                return false
            }
            lensOffsets.addLast(
                LensOffsetSample(timestampNs, xShiftPixels, yShiftPixels, cameraType)
            )
            while (lensOffsets.size > MAX_OFFSET_SAMPLES ||
                (lensOffsets.peekFirst()?.timestampNs ?: timestampNs) <
                timestampNs - OFFSET_HISTORY_NS
            ) {
                lensOffsets.pollFirst()
            }
            return true
        }

        fun addLensIntrinsics(
            values: FloatArray,
            timestampNs: Long,
            cameraType: Int,
        ): Boolean {
            if (timestampNs <= 0L || values.size != 5 ||
                values.any { !it.isFinite() } || values[0] <= 0f || values[1] <= 0f
            ) {
                return false
            }
            lensIntrinsics.addLast(LensIntrinsicsSample(timestampNs, values.copyOf(), cameraType))
            while (lensIntrinsics.size > MAX_INTRINSICS_SAMPLES) lensIntrinsics.pollFirst()
            return true
        }

        fun processFrame(
            frame: FrameInput,
            rowHomographies: FloatArray,
            resultState: FloatArray,
        ): Long {
            if (frame.sourceTimestampNs <= 0L ||
                frame.activeWidth <= 0 || frame.activeHeight <= 0 ||
                frame.cropWidth <= 0 || frame.cropHeight <= 0 ||
                rowHomographies.size < MgcEisNativeEngine.STRIP_COUNT * 9
            ) {
                return droppedTimestamp(frame.sourceTimestampNs)
            }

            val previous = pendingFrames.peekLast()
            if (previous != null && frame.sourceTimestampNs <= previous.sourceTimestampNs) {
                // A recreated Camera2 session can restart its timestamp sequence.
                pendingFrames.clear()
            }
            pendingFrames.addLast(frame)
            if (pendingFrames.size <= lookaheadFrames) return -1L

            val source = pendingFrames.removeFirst()
            val target = pendingFrames.peekLast() ?: frame
            val latestPose = poseSamples.lastOrNull()?.timestampNs ?: return droppedTimestamp(source.sourceTimestampNs)
            val targetCenterTimestampNs = target.firstRowCenterTimestampNs +
                target.rollingShutterSkewNs.coerceAtLeast(0L) / 2L
            if (targetCenterTimestampNs > latestPose + 100_000_000L) {
                // Do not invent a future pose when the sensor stream has stalled.
                return droppedTimestamp(source.sourceTimestampNs)
            }

            val sourceStrength = strength.coerceIn(0f, 1f).toDouble()
            val rowCount = MgcEisNativeEngine.STRIP_COUNT
            val targetOrientation = orientationAt(targetCenterTimestampNs)
            val (focalX, focalY) = resolveClipFocal(source)
            val inverseCropZoom = 1.0 / (1.0 + (1.0 / 9.0) * sourceStrength)
            val offsetSamples = lensOffsets.toList()
            val intrinsicSamples = lensIntrinsics.toList()

            for (row in 0 until rowCount) {
                val rowFraction = if (rowCount <= 1) 0.0 else row.toDouble() / (rowCount - 1)
                val rowTimestampNs = source.firstRowCenterTimestampNs +
                    (source.rollingShutterSkewNs.coerceAtLeast(0L).toDouble() * rowFraction).toLong()
                val sourceOrientation = orientationAt(rowTimestampNs)
                val relativeRotation = (
                    targetOrientation.inverse() * sourceOrientation
                ).normalized()
                val smoothedRotation = Quaternion.slerp(
                    Quaternion.IDENTITY,
                    relativeRotation,
                    sourceStrength,
                )
                val rotation = smoothedRotation.toMatrix3()
                val outputOffset = row * 9

                // K * R * inverse(K), expressed in the renderer's clip-space coordinates.
                var h00 = rotation[0]
                var h01 = focalX * rotation[1] / focalY
                var h02 = focalX * rotation[2]
                var h10 = focalY * rotation[3] / focalX
                var h11 = rotation[4]
                var h12 = focalY * rotation[5]
                var h20 = rotation[6] / focalX
                var h21 = rotation[7] / focalY
                var h22 = rotation[8]

                val offset = findLensOffset(offsetSamples, rowTimestampNs)
                if (offset != null && width > 0 && height > 0) {
                    // OIS shift samples are supplementary to the gyro pose, not a replacement
                    // for it. Keep their contribution small and sign-inverted as a correction.
                    h02 -= (2.0 * offset.xPixels / width) * sourceStrength * 0.25
                    h12 += (2.0 * offset.yPixels / height) * sourceStrength * 0.25
                }

                h02 = h02.coerceIn(-MAX_CLIP_TRANSLATION, MAX_CLIP_TRANSLATION)
                h12 = h12.coerceIn(-MAX_CLIP_TRANSLATION, MAX_CLIP_TRANSLATION)

                // Reserve a fixed motion margin so the rolling mesh does not expose black edges.
                h20 *= inverseCropZoom
                h21 *= inverseCropZoom
                h22 *= inverseCropZoom
                rowHomographies[outputOffset] = h00.toFloat()
                rowHomographies[outputOffset + 1] = h01.toFloat()
                rowHomographies[outputOffset + 2] = h02.toFloat()
                rowHomographies[outputOffset + 3] = h10.toFloat()
                rowHomographies[outputOffset + 4] = h11.toFloat()
                rowHomographies[outputOffset + 5] = h12.toFloat()
                rowHomographies[outputOffset + 6] = h20.toFloat()
                rowHomographies[outputOffset + 7] = h21.toFloat()
                rowHomographies[outputOffset + 8] = h22.toFloat()
            }

            if (resultState.isNotEmpty()) resultState[0] = sourceStrength.toFloat()
            if (resultState.size > 1) resultState[1] = 0f
            // Keep a small amount of history for interpolation without allowing an unbounded
            // per-frame allocation. The intrinsics are retained for timestamp-matched frames.
            trimIntrinsicsThrough(source.sourceTimestampNs, intrinsicSamples)
            return source.sourceTimestampNs
        }

        private fun resolveClipFocal(frame: FrameInput): Pair<Double, Double> {
            val dynamic = latestIntrinsicsAt(
                timestampNs = frame.sourceTimestampNs,
                samples = lensIntrinsics.toList(),
            )?.values
            val intrinsics = dynamic ?: frame.nominalLensIntrinsics
            val estimatedFx = (2.0 / frame.inverseFocalLength.toDouble())
                .takeIf { it.isFinite() && it > 0.0 }
                ?: 1.0
            val fx = intrinsics?.getOrNull(0)?.let { 2.0 * it / frame.cropWidth }
                ?.takeIf { it.isFinite() && it in MIN_CLIP_FOCAL..MAX_CLIP_FOCAL }
                ?: estimatedFx.coerceIn(MIN_CLIP_FOCAL, MAX_CLIP_FOCAL)
            val fy = intrinsics?.getOrNull(1)?.let { 2.0 * it / frame.cropHeight }
                ?.takeIf { it.isFinite() && it in MIN_CLIP_FOCAL..MAX_CLIP_FOCAL }
                ?: (fx * frame.width.toDouble() / frame.height.toDouble())
                    .coerceIn(MIN_CLIP_FOCAL, MAX_CLIP_FOCAL)
            return fx to fy
        }

        private fun findLensOffset(
            samples: List<LensOffsetSample>,
            timestampNs: Long,
        ): LensOffsetSample? {
            for (index in samples.indices.reversed()) {
                val sample = samples[index]
                if (sample.timestampNs <= timestampNs &&
                    timestampNs - sample.timestampNs <= OFFSET_HISTORY_NS
                ) {
                    return sample
                }
            }
            return null
        }

        private fun latestIntrinsicsAt(
            timestampNs: Long,
            samples: List<LensIntrinsicsSample>,
        ): LensIntrinsicsSample? {
            for (index in samples.indices.reversed()) {
                val sample = samples[index]
                if (sample.timestampNs <= timestampNs &&
                    timestampNs - sample.timestampNs <= POSE_HISTORY_NS
                ) {
                    return sample
                }
            }
            return null
        }

        private fun trimIntrinsicsThrough(
            timestampNs: Long,
            samplesSnapshot: List<LensIntrinsicsSample>,
        ) {
            while (lensIntrinsics.size > 1) {
                val oldest = lensIntrinsics.peekFirst() ?: break
                val next = samplesSnapshot.firstOrNull { it.timestampNs > oldest.timestampNs }
                if (next == null || next.timestampNs >= timestampNs) break
                lensIntrinsics.pollFirst()
            }
        }

        private fun orientationAt(timestampNs: Long): Quaternion {
            if (poseSamples.isEmpty()) return Quaternion.IDENTITY
            val firstIndex = oldestPoseIndex.coerceIn(0, poseSamples.lastIndex)
            val first = poseSamples[firstIndex]
            if (timestampNs <= first.timestampNs) return first.orientation
            val last = poseSamples.last()
            if (timestampNs >= last.timestampNs) return last.orientation

            var low = firstIndex
            var high = poseSamples.lastIndex
            while (low + 1 < high) {
                val middle = (low + high) ushr 1
                if (poseSamples[middle].timestampNs <= timestampNs) {
                    low = middle
                } else {
                    high = middle
                }
            }
            val before = poseSamples[low]
            val after = poseSamples[high]
            val interval = (after.timestampNs - before.timestampNs).coerceAtLeast(1L)
            val fraction = ((timestampNs - before.timestampNs).toDouble() / interval).coerceIn(0.0, 1.0)
            return Quaternion.slerp(before.orientation, after.orientation, fraction)
        }

        private fun trimHistories(timestampNs: Long) {
            val cutoff = timestampNs - POSE_HISTORY_NS
            while (oldestPoseIndex + 1 < poseSamples.size &&
                poseSamples[oldestPoseIndex + 1].timestampNs < cutoff
            ) {
                oldestPoseIndex += 1
            }
            if (oldestPoseIndex > 256 && oldestPoseIndex * 2 > poseSamples.size) {
                poseSamples.subList(0, oldestPoseIndex).clear()
                oldestPoseIndex = 0
            }
            while (gyroSamples.isNotEmpty() &&
                gyroSamples.first().timestampNs < cutoff
            ) {
                gyroSamples.removeAt(0)
            }
        }
    }

    private data class Quaternion(
        val w: Double,
        val x: Double,
        val y: Double,
        val z: Double,
    ) {
        operator fun times(other: Quaternion) = Quaternion(
            w = w * other.w - x * other.x - y * other.y - z * other.z,
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w,
        )

        fun inverse(): Quaternion {
            val normSquared = w * w + x * x + y * y + z * z
            if (normSquared <= 1e-18) return IDENTITY
            return Quaternion(
                w / normSquared,
                -x / normSquared,
                -y / normSquared,
                -z / normSquared,
            )
        }

        fun normalized(): Quaternion {
            val length = sqrt(w * w + x * x + y * y + z * z)
            if (!length.isFinite() || length <= 1e-12) return IDENTITY
            return Quaternion(w / length, x / length, y / length, z / length)
        }

        fun toMatrix3(): DoubleArray {
            val q = normalized()
            val xx = q.x * q.x
            val yy = q.y * q.y
            val zz = q.z * q.z
            val xy = q.x * q.y
            val xz = q.x * q.z
            val yz = q.y * q.z
            val wx = q.w * q.x
            val wy = q.w * q.y
            val wz = q.w * q.z
            return doubleArrayOf(
                1.0 - 2.0 * (yy + zz),
                2.0 * (xy - wz),
                2.0 * (xz + wy),
                2.0 * (xy + wz),
                1.0 - 2.0 * (xx + zz),
                2.0 * (yz - wx),
                2.0 * (xz - wy),
                2.0 * (yz + wx),
                1.0 - 2.0 * (xx + yy),
            )
        }

        companion object {
            val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

            fun fromRotationVector(x: Double, y: Double, z: Double): Quaternion {
                val angle = sqrt(x * x + y * y + z * z)
                if (!angle.isFinite() || angle <= 1e-12) return IDENTITY
                val half = angle * 0.5
                val scale = sin(half) / angle
                return Quaternion(
                    w = cos(half),
                    x = x * scale,
                    y = y * scale,
                    z = z * scale,
                ).normalized()
            }

            fun slerp(start: Quaternion, end: Quaternion, fraction: Double): Quaternion {
                val t = fraction.coerceIn(0.0, 1.0)
                val a = start.normalized()
                var b = end.normalized()
                var dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
                if (dot < 0.0) {
                    b = Quaternion(-b.w, -b.x, -b.y, -b.z)
                    dot = -dot
                }
                dot = dot.coerceIn(-1.0, 1.0)
                if (dot > 0.9995) {
                    return Quaternion(
                        a.w + t * (b.w - a.w),
                        a.x + t * (b.x - a.x),
                        a.y + t * (b.y - a.y),
                        a.z + t * (b.z - a.z),
                    ).normalized()
                }
                val theta = acos(dot)
                val sinTheta = sin(theta)
                if (kotlin.math.abs(sinTheta) <= 1e-12) return a
                val aWeight = sin((1.0 - t) * theta) / sinTheta
                val bWeight = sin(t * theta) / sinTheta
                return Quaternion(
                    aWeight * a.w + bWeight * b.w,
                    aWeight * a.x + bWeight * b.x,
                    aWeight * a.y + bWeight * b.y,
                    aWeight * a.z + bWeight * b.z,
                ).normalized()
            }
        }
    }
}
