package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertFalse

class CameraRequestPolicyTest {
    @Test
    fun keepsAutoExposureAndWhiteBalanceUnlockedForChangingOutdoorLight() {
        val policy = CameraRequestPolicy.default()

        assertFalse(policy.autoExposureLock)
        assertFalse(policy.autoWhiteBalanceLock)
    }
}
