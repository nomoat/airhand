package ai.rebyte.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Protects the cloud-to-device execution boundary, not Android framework behavior. */
class PhoneToolsTest {
    @Test fun allToolsAreClosedObjectSchemas() {
        val tools = PhoneTools.definitions
        val names = mutableSetOf<String>()
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            assertTrue(names.add(tool.getString("name")))
            val schema = tool.getJSONObject("parameters")
            assertFalse(schema.getBoolean("additionalProperties"))
            assertEquals(schema.getJSONObject("properties").length(), schema.getJSONArray("required").length())
        }
    }
    @Test fun rejectsUnknownToolsAndUnexpectedArguments() {
        assertThrows(IllegalStateException::class.java) { PhoneTools.validate("exec_command", JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) { PhoneTools.validate("phone_observe", JSONObject().put("command", "id")) }
    }
    @Test fun rejectsWrongTypesAndMissingFields() {
        assertThrows(IllegalArgumentException::class.java) { PhoneTools.validate("phone_tap", JSONObject().put("snapshot_id", "a").put("x", "1").put("y", 2)) }
        assertThrows(IllegalArgumentException::class.java) { PhoneTools.validate("phone_tap", JSONObject().put("x", 1)) }
        assertThrows(IllegalArgumentException::class.java) { PhoneTools.validate("phone_tap", JSONObject().put("snapshot_id", "a").put("x", 1.5).put("y", 2)) }
        assertThrows(IllegalArgumentException::class.java) { PhoneTools.validate("phone_tap", JSONObject().put("snapshot_id", "a").put("x", 4294967297L).put("y", 2)) }
        PhoneTools.validate("phone_tap", JSONObject().put("snapshot_id", "a").put("x", 1).put("y", 2))
    }
    @Test fun resizedScreenshotsMapToRealPixels() {
        assertEquals(540 to 1200, CoordinateSpace.toScreen(230, 512, 460, 1024, 1080, 2400))
        assertEquals(1078 to 2398, CoordinateSpace.toScreen(460, 1023, 461, 1024, 1080, 2400))
        assertThrows(IllegalArgumentException::class.java) { CoordinateSpace.toScreen(461, 0, 461, 1024, 1080, 2400) }
        assertThrows(IllegalArgumentException::class.java) { CoordinateSpace.toScreen(-1, 0, 461, 1024, 1080, 2400) }
        assertThrows(IllegalArgumentException::class.java) { CoordinateSpace.toScreen(1, 1, 0, 0, 1080, 2400) }
    }
    @Test fun replayFingerprintIgnoresJsonPropertyOrderButNotValues() {
        val a = JSONObject().put("x", 5).put("y", 9)
        val reordered = JSONObject().put("y", 9).put("x", 5)
        val changed = JSONObject().put("x", 6).put("y", 9)
        assertEquals(PhoneTools.fingerprint("phone_tap", "turn1", a), PhoneTools.fingerprint("phone_tap", "turn1", reordered))
        assertNotEquals(PhoneTools.fingerprint("phone_tap", "turn1", a), PhoneTools.fingerprint("phone_tap", "turn1", changed))
        assertNotEquals(PhoneTools.fingerprint("phone_tap", "turn1", a), PhoneTools.fingerprint("phone_tap", "turn2", a))
    }
}
