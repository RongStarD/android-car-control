package cn.edu.xxq.rosmastercontrol.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceServiceClientTest {
    @Test
    fun snapshotParsesDatabaseReadinessAndPeople() {
        val status = JSONObject(
            """
            {
              "ok": true,
              "database": {"people": 2, "samples": 7, "revision": 9},
              "camera": {"ready": true},
              "recognition": {"ready": false},
              "recognition_session": {
                "state": "running",
                "active": true,
                "session_id": "session-9",
                "duration_seconds": 5,
                "remaining_seconds": 3.4,
                "processed_frames": 2,
                "result": null
              }
            }
            """.trimIndent(),
        )
        val people = JSONObject(
            """
            {
              "ok": true,
              "people": [
                {"name": "张三", "samples": 5, "updated_at": "2026-07-14T12:00:00Z"},
                {"name": "Alice", "samples": 2}
              ],
              "database_revision": 9
            }
            """.trimIndent(),
        )

        val snapshot = FaceServiceJson.parseSnapshot(status, people)

        assertEquals(2, snapshot.peopleCount)
        assertEquals(7, snapshot.sampleCount)
        assertEquals(9L, snapshot.databaseRevision)
        assertTrue(snapshot.cameraReady)
        assertFalse(snapshot.recognitionReady)
        assertEquals(RecognitionSessionState.RUNNING, snapshot.recognitionSession.state)
        assertTrue(snapshot.recognitionSession.active)
        assertEquals(3.4, snapshot.recognitionSession.remainingSeconds, 0.001)
        assertEquals(2, snapshot.recognitionSession.processedFrames)
        assertEquals(listOf("Alice", "张三"), snapshot.people.map(FacePerson::name))
        assertEquals(2, snapshot.people.first().samples)
    }

    @Test
    fun personNameIsEncodedAsOnePathSegment() {
        val url = FaceServiceEndpoint.apiUrl(
            "http://10.39.132.165:9095",
            "api",
            "v1",
            "people",
            "张 三/测试",
        )

        requireNotNull(url)
        assertEquals("/api/v1/people/%E5%BC%A0%20%E4%B8%89%2F%E6%B5%8B%E8%AF%95", url.encodedPath)
    }

    @Test
    fun nestedServiceErrorUsesMessage() {
        val payload = JSONObject(
            """{"ok":false,"error":{"code":"PERSON_NOT_FOUND","message":"人员不存在"}}""",
        )

        assertEquals("人员不存在", FaceServiceJson.errorMessage(payload))
    }

    @Test
    fun knownOrderErrorIsLocalized() {
        val payload = JSONObject(
            """{"ok":false,"error":{"code":"DELIVERY_ALREADY_ACTIVE","message":"another order is already being delivered"}}""",
        )

        assertEquals(
            "已有配送中订单，请先完成或取消当前配送",
            FaceServiceJson.errorMessage(payload),
        )
    }

    @Test
    fun completedRecognitionParsesBestResult() {
        val payload = JSONObject(
            """
            {
              "ok": true,
              "recognition_session": {
                "state": "completed",
                "active": false,
                "session_id": "session-10",
                "duration_seconds": 5,
                "remaining_seconds": 0,
                "processed_frames": 9,
                "order_id": "order-10",
                "order_result": {
                  "matched": true,
                  "completed": true,
                  "status": "completed",
                  "message": "订单已完成"
                },
                "result": {
                  "state": "recognized",
                  "person_id": "person-3",
                  "name": "张三",
                  "similarity": 0.873,
                  "appearances": 6
                }
              }
            }
            """.trimIndent(),
        )

        val session = FaceServiceJson.parseRecognitionSession(payload)

        assertEquals(RecognitionSessionState.COMPLETED, session.state)
        assertFalse(session.active)
        assertEquals(RecognitionResultState.RECOGNIZED, session.result?.state)
        assertEquals("person-3", session.result?.personId)
        assertEquals("张三", session.result?.name)
        assertEquals(0.873, session.result?.similarity ?: 0.0, 0.001)
        assertEquals(6, session.result?.appearances)
        assertEquals("order-10", session.orderId)
        assertTrue(session.orderResult?.matched == true)
        assertTrue(session.orderResult?.completed == true)
        assertEquals("completed", session.orderResult?.status)
    }

    @Test
    fun missingRecognitionSessionIsBackwardCompatibleIdle() {
        val session = FaceServiceJson.parseRecognitionSession(JSONObject("""{"ok":true}"""))

        assertEquals(RecognitionSessionState.IDLE, session.state)
        assertFalse(session.active)
        assertEquals(null, session.result)
    }

    @Test
    fun jsonNullAndLiteralNullNeverBecomeDisplayStrings() {
        val payload = JSONObject(
            """
            {
              "ok": true,
              "recognition_session": {
                "state": "completed",
                "active": false,
                "session_id": null,
                "order_id": "NULL",
                "result": {
                  "state": "uncertain",
                  "person_id": "null",
                  "name": null,
                  "similarity": 0.5,
                  "appearances": 1
                }
              }
            }
            """.trimIndent(),
        )

        val session = FaceServiceJson.parseRecognitionSession(payload)

        assertEquals(null, session.sessionId)
        assertEquals(null, session.orderId)
        assertEquals(null, session.result?.personId)
        assertEquals(null, session.result?.name)
        assertEquals(
            null,
            FaceServiceJson.errorMessage(
                JSONObject("""{"error":{"code":null,"message":"null"}}"""),
            ),
        )
    }

    @Test
    fun jsonNullPeopleAndOrdersAreSkipped() {
        val status = JSONObject(
            """{"database":{},"camera":{},"recognition":{}}""",
        )
        val people = JSONObject(
            """{"people":[{"name":null,"samples":1},{"name":"Alice","samples":1,"updated_at":null}]}""",
        )
        val orders = JSONObject(
            """{"orders":[{"order_id":"1","person_id":"p1","person_name":null,"room_number":"302","status":"pending","items":[]}]}""",
        )

        val snapshot = FaceServiceJson.parseSnapshot(status, people)

        assertEquals(listOf("Alice"), snapshot.people.map(FacePerson::name))
        assertEquals(null, snapshot.people.single().updatedAt)
        assertTrue(FaceServiceJson.parseOrders(orders).isEmpty())
    }

    @Test
    fun startRecognitionPostsDurationAndAccepts202() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(202)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"ok":true,"recognition_session":{"state":"running","active":true,"session_id":"abc","duration_seconds":5,"remaining_seconds":5,"processed_frames":0,"result":null}}""",
                    ),
            )
            server.start()
            val client = FaceServiceClient(apiKey = "test-key")

            val result = client.startRecognitionSession(
                server.url("/").toString(),
                5,
                "order-42",
            )
            val request = server.takeRequest()

            assertTrue(result is FaceApiResult.Success)
            assertEquals(RecognitionSessionState.RUNNING, (result as FaceApiResult.Success).value.state)
            assertEquals("POST", request.method)
            assertEquals("/api/v1/recognition/session", request.path)
            assertEquals("test-key", request.getHeader("X-API-Key"))
            val requestBody = JSONObject(request.body.readUtf8())
            assertEquals(5, requestBody.getInt("duration_seconds"))
            assertEquals("order-42", requestBody.getString("order_id"))
            client.close()
        }
    }

    @Test
    fun ordersParseItemsStatusesAndTimestamps() {
        val payload = JSONObject(
            """
            {
              "ok": true,
              "orders": [
                {
                  "order_id": "order-1",
                  "person_id": "person-1",
                  "person_name": "张三",
                  "room_number": "302",
                  "status": "pending",
                  "items": [
                    {"product_id":"water","product_name":"矿泉水","quantity":2}
                  ],
                  "created_at": "2026-07-14T12:00:00Z",
                  "delivering_at": null
                },
                {
                  "order_id": "order-2",
                  "person_id": "person-2",
                  "person_name": "李四",
                  "room_number": "401",
                  "status": "completed",
                  "items": []
                }
              ]
            }
            """.trimIndent(),
        )

        val orders = FaceServiceJson.parseOrders(payload)

        assertEquals(2, orders.size)
        assertEquals(DeliveryOrderStatus.PENDING, orders[0].status)
        assertEquals("矿泉水", orders[0].items.single().productName)
        assertEquals(2, orders[0].items.single().quantity)
        assertEquals("2026-07-14T12:00:00Z", orders[0].createdAt)
        assertEquals(null, orders[0].deliveringAt)
        assertEquals(DeliveryOrderStatus.COMPLETED, orders[1].status)
    }

    @Test
    fun loadOrdersUsesAdminStatusAllEndpoint() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"orders":[]}"""),
            )
            server.start()
            val client = FaceServiceClient(apiKey = "test-key")

            val result = client.loadOrders(server.url("/").toString())
            val request = server.takeRequest()

            assertTrue(result is FaceApiResult.Success)
            assertEquals("/api/v1/admin/orders?status=all", request.path)
            assertEquals("test-key", request.getHeader("X-API-Key"))
            client.close()
        }
    }

    @Test
    fun startAndCancelOrderUseActionEndpoints() {
        val orderJson =
            """{"order_id":"order/1","person_id":"person-1","person_name":"张三","room_number":"302","status":"delivering","items":[]}"""
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"order":$orderJson}"""),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"ok":true,"order":${orderJson.replace("delivering", "cancelled")}}""",
                    ),
            )
            server.start()
            val client = FaceServiceClient(apiKey = "test-key")

            val started = client.startDelivery(server.url("/").toString(), "order/1")
            val startRequest = server.takeRequest()
            val cancelled = client.cancelOrder(server.url("/").toString(), "order/1")
            val cancelRequest = server.takeRequest()

            assertTrue(started is FaceApiResult.Success)
            assertEquals(DeliveryOrderStatus.DELIVERING, (started as FaceApiResult.Success).value.status)
            assertEquals("/api/v1/admin/orders/order%2F1/start", startRequest.path)
            assertEquals("POST", startRequest.method)
            assertEquals("{}", startRequest.body.readUtf8())
            assertTrue(cancelled is FaceApiResult.Success)
            assertEquals(DeliveryOrderStatus.CANCELLED, (cancelled as FaceApiResult.Success).value.status)
            assertEquals("/api/v1/admin/orders/order%2F1/cancel", cancelRequest.path)
            client.close()
        }
    }

    @Test
    fun stateChangingPostIsNotReplayedAfterConnectionLoss() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"order":{}}"""),
            )
            server.start()
            val client = FaceServiceClient(apiKey = "test-key")

            val result = client.startDelivery(server.url("/").toString(), "order-1")

            assertTrue(result is FaceApiResult.Failure)
            assertEquals(1, server.requestCount)
            client.close()
        }
    }

    @Test
    fun cancelRecognitionUsesDelete() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"ok":true,"recognition_session":{"state":"cancelled","active":false,"duration_seconds":5,"remaining_seconds":2,"processed_frames":4,"result":null}}""",
                    ),
            )
            server.start()
            val client = FaceServiceClient(apiKey = "test-key")

            val result = client.cancelRecognitionSession(server.url("/").toString())
            val request = server.takeRequest()

            assertTrue(result is FaceApiResult.Success)
            assertEquals(RecognitionSessionState.CANCELLED, (result as FaceApiResult.Success).value.state)
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/recognition/session", request.path)
            client.close()
        }
    }
}
