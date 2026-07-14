package cn.edu.xxq.rosmastercontrol.network

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
              "recognition": {"ready": false}
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
}
