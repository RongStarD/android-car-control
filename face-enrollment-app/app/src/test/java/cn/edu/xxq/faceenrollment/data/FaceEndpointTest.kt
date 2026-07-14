package cn.edu.xxq.faceenrollment.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceEndpointTest {
    @Test
    fun normalize_acceptsHttpAndRemovesTrailingSlash() {
        assertEquals(
            "http://10.39.132.165:9095",
            FaceEndpoint.normalize("  http://10.39.132.165:9095/  "),
        )
    }

    @Test
    fun normalize_rejectsUnsupportedOrAmbiguousAddresses() {
        assertNull(FaceEndpoint.normalize("10.39.132.165:9095"))
        assertNull(FaceEndpoint.normalize("ftp://10.39.132.165/file"))
        assertNull(FaceEndpoint.normalize("http://10.39.132.165:9095/?token=secret"))
    }

}
