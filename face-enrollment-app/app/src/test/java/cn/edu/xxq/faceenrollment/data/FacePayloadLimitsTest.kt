package cn.edu.xxq.faceenrollment.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacePayloadLimitsTest {
    @Test
    fun validationError_acceptsExactlyTwentyMiB() {
        val sizes = List(4) { MAX_FACE_IMAGE_BYTES }

        assertNull(FacePayloadLimits.validationError(sizes))
    }

    @Test
    fun validationError_rejectsTotalAboveTwentyMiBEvenWhenEachImageIsValid() {
        val sizes = List(4) { MAX_FACE_IMAGE_BYTES } + 1

        assertEquals(
            "所有 JPEG 合计必须小于或等于 20 MiB",
            FacePayloadLimits.validationError(sizes),
        )
    }

    @Test
    fun validationError_rejectsAnIndividualImageAboveFiveMiB() {
        assertEquals(
            "每张 JPEG 必须小于或等于 5 MiB",
            FacePayloadLimits.validationError(listOf(MAX_FACE_IMAGE_BYTES + 1)),
        )
    }
}
