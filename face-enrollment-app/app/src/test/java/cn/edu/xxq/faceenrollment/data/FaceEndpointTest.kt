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

    @Test
    fun apiUrl_buildsRecognitionSessionPath() {
        assertEquals(
            "http://10.39.132.165:9095/api/v1/recognition/session",
            FaceEndpoint.apiUrl(
                "http://10.39.132.165:9095",
                "api",
                "v1",
                "recognition",
                "session",
            )?.toString(),
        )
    }

    @Test
    fun apiUrl_buildsCatalogAndOrdersPaths() {
        val baseUrl = "http://10.39.132.165:9095"

        assertEquals(
            "$baseUrl/api/v1/catalog",
            FaceEndpoint.apiUrl(baseUrl, "api", "v1", "catalog")?.toString(),
        )
        assertEquals(
            "$baseUrl/api/v1/orders",
            FaceEndpoint.apiUrl(baseUrl, "api", "v1", "orders")?.toString(),
        )
    }

}
