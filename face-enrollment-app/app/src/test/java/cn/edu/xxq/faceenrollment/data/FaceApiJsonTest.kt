package cn.edu.xxq.faceenrollment.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceApiJsonTest {
    @Test
    fun parseError_prefersStructuredMessageAndKeepsCode() {
        val json = JSONObject(
            """{"ok":false,"error":{"code":"NO_FACE","message":"没有检测到人脸","retryable":true}}""",
        )

        assertEquals("没有检测到人脸（NO_FACE）", FaceApiJson.parseError(json))
    }

    @Test
    fun parseError_keepsLegacyStringCompatibility() {
        assertEquals(
            "API Key 无效",
            FaceApiJson.parseError(JSONObject("{\"ok\":false,\"error\":\"API Key 无效\"}")),
        )
    }

    @Test
    fun parseEnrollment_supportsNestedCurrentResponse() {
        val json = JSONObject(
            """
            {
              "ok": true,
              "person": {"name": "张三", "samples": 7},
              "added_samples": 3,
              "database_revision": 42
            }
            """.trimIndent(),
        )

        val receipt = FaceApiJson.parseEnrollment(json, "fallback", 3)

        assertEquals("张三", receipt.name)
        assertEquals(3, receipt.acceptedSamples)
        assertEquals(7, receipt.totalSamples)
        assertEquals(42L, receipt.databaseRevision)
    }

    @Test
    fun parseEnrollment_keepsLegacyTopLevelCompatibility() {
        val receipt = FaceApiJson.parseEnrollment(
            JSONObject("{\"ok\":true,\"name\":\"Alice\",\"samples\":2}"),
            "fallback",
            5,
        )

        assertEquals("Alice", receipt.name)
        assertEquals(2, receipt.acceptedSamples)
        assertEquals(2, receipt.totalSamples)
    }

    @Test
    fun parseStatus_readsCameraDatabaseAndLatestFaces() {
        val json = JSONObject(
            """
            {
              "ok": true,
              "camera": {"ready": true, "source": "/dev/video2", "error": ""},
              "database": {"people": 3, "samples": 12},
              "latest": {
                "timestamp": "2026-07-14T10:15:00+08:00",
                "faces": [
                  {"state": "recognized", "name": "张三", "similarity": 0.812},
                  {"state": "recognized", "name": "李四", "similarity": 0.731}
                ]
              }
            }
            """.trimIndent(),
        )

        val status = FaceApiJson.parseStatus(json)

        assertTrue(status.camera.ready)
        assertEquals("/dev/video2", status.camera.source)
        assertNull(status.camera.error)
        assertEquals(3, status.database.people)
        assertEquals(12, status.database.samples)
        assertEquals("张三", status.latest?.faces?.first()?.name)
        assertEquals("recognized", status.latest?.faces?.first()?.state)
        assertEquals(0.812, status.latest?.faces?.first()?.similarity ?: 0.0, 0.0001)
    }

    @Test
    fun parseStatus_doesNotTurnJsonNullIntoTextAndOnlyKeepsRecognizedFaces() {
        val json = JSONObject(
            """
            {
              "ok": true,
              "camera": {"ready": true, "source": null, "error": null},
              "recognition": {
                "enabled": true,
                "ready": false,
                "error": "ONNX 模型加载失败",
                "last_success_at": null
              },
              "latest": {
                "timestamp": null,
                "faces": [
                  {"state": "recognized", "name": "Alice", "similarity": 0.81},
                  {"state": "unknown", "name": "Unknown", "similarity": 0.22},
                  {"state": "detected", "name": null, "similarity": 0.7},
                  {"state": "recognized", "name": null, "similarity": 0.9},
                  {"state": "recognized", "name": "Bob", "similarity": null},
                  {"state": "recognized", "name": "Eve", "similarity": "NaN"}
                ]
              }
            }
            """.trimIndent(),
        )

        val status = FaceApiJson.parseStatus(json)

        assertNull(status.camera.source)
        assertNull(status.camera.error)
        assertTrue(status.recognition.enabled)
        assertFalse(status.recognition.ready)
        assertEquals("ONNX 模型加载失败", status.recognition.error)
        assertNull(status.recognition.lastSuccessAt)
        assertNull(status.latest?.timestamp)
        assertEquals(listOf("Alice"), status.latest?.faces?.map { it.name })
    }

    @Test
    fun parseStatus_toleratesMissingOptionalObjects() {
        val status = FaceApiJson.parseStatus(JSONObject("{\"ok\":true}"))

        assertFalse(status.camera.ready)
        assertEquals(0, status.database.people)
        assertNull(status.latest)
    }
}
