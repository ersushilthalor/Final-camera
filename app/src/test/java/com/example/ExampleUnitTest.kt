package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testInstantSwitchDefaults() {
    val prefs = com.hinnka.mycamera.data.UserPreferences()
    assertTrue(prefs.keepUltraWideReadyEnabled)
    assertFalse(prefs.isAutoSwitchToUltraWide)
    assertFalse(prefs.isShowUltraWidePreview)
    assertTrue(prefs.isKeepFrontCameraReady)
    assertFalse(prefs.isShowFrontCameraPreview)
    assertEquals(0.02f, prefs.lensSwitchHoldDurationSec, 0.001f)
    assertTrue(prefs.isLensSwitchOverlapAnimationEnabled)
    assertEquals(0.30f, prefs.lensSwitchOverlapDurationSec, 0.001f)
  }

  @Test
  fun testInstantSwitchStateModel() {
    val state = com.hinnka.mycamera.camera.MotorolaInstantSwitchState(
      isKeepUltraWideReady = true,
      isAutoSwitchToUltraWide = false,
      isShowUltraWidePreview = true,
      isKeepFrontCameraReady = true,
      isShowFrontCameraPreview = false,
      lensSwitchHoldDurationSec = 0.05f,
      isLensSwitchOverlapAnimationEnabled = true,
      lensSwitchOverlapDurationSec = 0.25f
    )
    assertTrue(state.isKeepUltraWideReady)
    assertFalse(state.isAutoSwitchToUltraWide)
    assertTrue(state.isShowUltraWidePreview)
    assertTrue(state.isKeepFrontCameraReady)
    assertFalse(state.isShowFrontCameraPreview)
    assertEquals(50L, state.lensSwitchHoldDurationMs)
    assertEquals(250L, state.lensSwitchOverlapDurationMs)
  }
}
