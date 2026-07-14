package cn.edu.xxq.faceenrollment.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceApiJsonTest {
    @Test
    fun orderPayload_usesOnlyPublicOrderContractFields() {
        val payload = FaceApiJson.orderPayload(
            personId = " person-123 ",
            personName = " 张三 ",
            roomNumber = " A302 ",
            items = listOf(OrderItemRequest("water", 2)),
        )

        assertEquals(setOf("person_id", "person_name", "room_number", "items"), payload.keySet())
        assertEquals("person-123", payload.getString("person_id"))
        assertEquals("张三", payload.getString("person_name"))
        assertEquals("A302", payload.getString("room_number"))
        val item = payload.getJSONArray("items").getJSONObject(0)
        assertEquals(setOf("product_id", "quantity"), item.keySet())
        assertEquals("water", item.getString("product_id"))
        assertEquals(2, item.getInt("quantity"))
    }

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
    fun parseEnrollment_keepsStablePersonIdForOrderDefaults() {
        val receipt = FaceApiJson.parseEnrollment(
            JSONObject(
                """{"ok":true,"person":{"person_id":"person-123","name":"张三","samples":2}}""",
            ),
            "fallback",
            2,
        )

        assertEquals("person-123", receipt.personId)
        assertEquals("张三", receipt.name)
    }

    @Test
    fun parseCatalog_readsProductsAndIgnoresDuplicateIds() {
        val products = FaceApiJson.parseCatalog(
            JSONObject(
                """
                {
                  "ok": true,
                  "products": [
                    {"product_id":"water","name":"矿泉水","enabled":true},
                    {"product_id":"snack","name":"饼干","enabled":false},
                    {"product_id":"water","name":"重复水","enabled":true}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(2, products.size)
        assertEquals("矿泉水", products[0].name)
        assertTrue(products[0].enabled)
        assertFalse(products[1].enabled)
    }

    @Test
    fun parseOrder_readsStableOrderFieldsAndItems() {
        val order = FaceApiJson.parseOrder(
            JSONObject(
                """
                {
                  "ok": true,
                  "order": {
                    "order_id":"order-456",
                    "person_id":"person-123",
                    "person_name":"张三",
                    "room_number":"A302",
                    "status":"PENDING",
                    "created_at":"2026-07-14T21:00:00+08:00",
                    "items":[
                      {"product_id":"water","product_name":"矿泉水","quantity":2},
                      {"product_id":"snack","product_name":"饼干","quantity":1}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("order-456", order.orderId)
        assertEquals("person-123", order.personId)
        assertEquals("张三", order.personName)
        assertEquals("A302", order.roomNumber)
        assertEquals("pending", order.status)
        assertEquals(listOf("矿泉水", "饼干"), order.items.map { it.productName })
        assertEquals(listOf(2, 1), order.items.map { it.quantity })
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

    @Test
    fun parseStatus_readsActiveRecognitionSession() {
        val status = FaceApiJson.parseStatus(
            JSONObject(
                """
                {
                  "ok": true,
                  "recognition_session": {
                    "state": "active",
                    "active": true,
                    "session_id": "session-123",
                    "duration_seconds": 5,
                    "remaining_seconds": 3.4,
                    "processed_frames": 2,
                    "result": null
                  }
                }
                """.trimIndent(),
            ),
        )

        assertTrue(status.recognitionSession?.active == true)
        assertEquals("session-123", status.recognitionSession?.sessionId)
        assertEquals(3.4, status.recognitionSession?.remainingSeconds ?: 0.0, 0.0001)
        assertEquals(2, status.recognitionSession?.processedFrames)
        assertNull(status.recognitionSession?.result)
    }

    @Test
    fun parseRecognitionSession_readsCompletedRecognizedResult() {
        val session = FaceApiJson.parseRecognitionSession(
            JSONObject(
                """
                {
                  "ok": true,
                  "recognition_session": {
                    "state": "completed",
                    "active": false,
                    "session_id": "session-456",
                    "duration_seconds": 5.0,
                    "remaining_seconds": 0,
                    "processed_frames": 9,
                    "result": {
                      "state": "recognized",
                      "name": "张三",
                      "similarity": 0.812,
                      "appearances": 4
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertFalse(session.active)
        assertEquals("completed", session.state)
        assertEquals("recognized", session.result?.state)
        assertEquals("张三", session.result?.name)
        assertEquals(0.812, session.result?.similarity ?: 0.0, 0.0001)
        assertEquals(4, session.result?.appearances)
    }

    @Test
    fun parseRecognitionSession_acceptsTopLevelAndNullUnknownResultFields() {
        val session = FaceApiJson.parseRecognitionSession(
            JSONObject(
                """
                {
                  "ok": true,
                  "state": "completed",
                  "active": false,
                  "processed_frames": -1,
                  "result": {
                    "state": "unknown",
                    "name": null,
                    "similarity": null,
                    "appearances": -3
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(0, session.processedFrames)
        assertEquals("unknown", session.result?.state)
        assertNull(session.result?.name)
        assertNull(session.result?.similarity)
        assertEquals(0, session.result?.appearances)
    }
}
