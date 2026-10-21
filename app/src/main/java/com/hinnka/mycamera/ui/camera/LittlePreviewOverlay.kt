package com.hinnka.mycamera.ui.camera

import android.graphics.SurfaceTexture
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.camera.BackgroundCameraStatus

/**
 * Compact Picture-in-Picture Floating Window Overlay for Instant Camera Switching.
 *
 * Designed as a pure UI overlay directly above the main viewfinder:
 * - Single camera preview source: Zero separate SurfaceTextures, camera sessions, or frame copies
 * - Zero additional latency: Shares main viewfinder timing directly
 * - Frosted Glass styling that preserves maximum viewfinder visibility
 */
@Composable
fun LittlePreviewOverlay(
    showUltraWidePreview: Boolean,
    showFrontPreview: Boolean,
    ultraWideStatus: BackgroundCameraStatus = BackgroundCameraStatus.OFF,
    frontStatus: BackgroundCameraStatus = BackgroundCameraStatus.OFF,
    onUltraWideSurfaceTextureAvailable: ((SurfaceTexture?) -> Unit)? = null,
    onFrontSurfaceTextureAvailable: ((SurfaceTexture?) -> Unit)? = null,
    onUltraWideClick: () -> Unit,
    onFrontClick: () -> Unit,
    onCloseUltraWidePreview: () -> Unit,
    onCloseFrontPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 80.dp, end = 12.dp, start = 12.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            // 1. Ultra-Wide Floating Switch Capsule
            AnimatedVisibility(
                visible = showUltraWidePreview,
                enter = fadeIn() + scaleIn(initialScale = 0.90f),
                exit = fadeOut() + scaleOut(targetScale = 0.90f)
            ) {
                LittlePreviewCard(
                    title = "0.5× UW",
                    subtitle = "Tap switch",
                    tag = "ultrawide_little_preview",
                    onClick = onUltraWideClick,
                    onClose = onCloseUltraWidePreview
                )
            }

            // 2. Front Camera Floating Switch Capsule
            AnimatedVisibility(
                visible = showFrontPreview,
                enter = fadeIn() + scaleIn(initialScale = 0.90f),
                exit = fadeOut() + scaleOut(targetScale = 0.90f)
            ) {
                LittlePreviewCard(
                    title = "FRONT",
                    subtitle = "Tap switch",
                    tag = "front_little_preview",
                    onClick = onFrontClick,
                    onClose = onCloseFrontPreview
                )
            }
        }
    }
}

@Composable
private fun LittlePreviewCard(
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .wrapContentSize()
            .testTag(tag)
            .shadow(elevation = 8.dp, shape = shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF141724).copy(alpha = 0.75f),
                        Color(0xFF0A0C13).copy(alpha = 0.88f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.40f),
                        Color.White.copy(alpha = 0.10f)
                    )
                ),
                shape = shape
            )
    ) {
        Row(
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // Glowing emerald / gold quick-switch indicator
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFD54F)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ElectricBolt,
                    contentDescription = null,
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(11.dp)
                )
            }

            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(2.dp))

            // Compact dismiss button
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close preview",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}
