package dev.yuhee.ailimits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Diagnostics is offered for pasting into a bug report, so "field names only" is a
 * privacy guarantee and gets tested like one. It previously had no tests at all, which
 * is how a raw Google error body — naming the user's Cloud project — reached the
 * clipboard under a toast promising no credentials.
 *
 * Robolectric because the stock JVM `org.json` is a stub that throws on every call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemaTest {

    @Test
    fun `records the field names of a real usage payload`() {
        val body = """
            {"rate_limit":{"primary_window":{"used_percent":41,"limit_window_seconds":18000}},
             "plan_type":"plus"}
        """.trimIndent()
        val keys = Schema.keysOf(body)
        assertTrue(keys, keys.contains("rate_limit.primary_window.used_percent"))
        assertTrue(keys, keys.contains("plan_type"))
    }

    @Test
    fun `never reproduces a value`() {
        val body = """{"plan_type":"chatgpt_business","utilization":73,"token":"sk-secret-value"}"""
        val keys = Schema.keysOf(body)
        assertFalse(keys, keys.contains("chatgpt_business"))
        assertFalse(keys, keys.contains("sk-secret-value"))
        assertFalse(keys, keys.contains("73"))
        assertTrue(keys, keys.contains("plan_type"))
    }

    /**
     * A JSON *name* is server-controlled data. A payload keyed by account id, project
     * resource name or email would otherwise put that identifier straight into text the
     * app invites the user to paste somewhere.
     */
    @Test
    fun `an identifier used as a key is redacted, not reproduced`() {
        val uuid = """{"by_account":{"9f3c1a24-7e5b-4d02-9c11-8ab6e0f4d7c3":{"used_percent":41}}}"""
        Schema.keysOf(uuid).let {
            assertFalse(it, it.contains("9f3c1a24"))
            assertTrue(it, it.contains("<redacted>"))
            assertTrue(it, it.contains("by_account"))
        }

        val email = """{"usage_by_seat":{"ra@redoxbio.co.kr":{"utilization":12}}}"""
        Schema.keysOf(email).let {
            assertFalse(it, it.contains("redoxbio"))
            assertFalse(it, it.contains("@"))
        }

        val project = """{"quotaByProject":{"projects/482913756123":{"buckets":[{"modelId":"x"}]}}}"""
        Schema.keysOf(project).let {
            assertFalse(it, it.contains("482913756123"))
            assertTrue(it, it.contains("quotaByProject"))
        }
    }

    /**
     * The whole point of this file is spotting a newly-published count. A provider may
     * report such a field on only some array elements, so sampling the first element
     * alone could miss exactly the field this exists to find.
     */
    @Test
    fun `a field present on only some array elements is still found`() {
        val body = """
            {"buckets":[
              {"modelId":"gemini-3-pro","remainingFraction":0.7},
              {"modelId":"gemini-2.5-flash","remainingFraction":0.9,"remainingAmount":"120000"}]}
        """.trimIndent()
        val keys = Schema.keysOf(body)
        assertTrue("the optional count must be reported", keys.contains("buckets[].remainingAmount"))
        assertFalse("but never its value", keys.contains("120000"))
    }

    @Test
    fun `containers with nothing in them still report their name`() {
        assertTrue(Schema.keysOf("""{"credits":[]}""").contains("credits"))
        assertTrue(Schema.keysOf("""{"credits":[100,200]}""").contains("credits"))
        assertTrue(Schema.keysOf("""{"meta":{}}""").contains("meta"))
    }

    @Test
    fun `truncation is announced rather than silent`() {
        val many = (1..60).joinToString(",") { "\"field_$it\":$it" }
        val keys = Schema.keysOf("{$many}")
        assertTrue(keys, keys.contains("more"))
    }

    @Test
    fun `malformed input does not echo the body`() {
        val keys = Schema.keysOf("this is not json, secret=abc123")
        assertEquals("unparseable", keys)
    }

    @Test
    fun `a provider error is summarised before it can reach the clipboard`() {
        // A provider's error envelope can carry an account or project identifier;
        // this text is offered for pasting into a bug report, so it must not survive.
        val raw = "Usage failed (HTTP 403): {\"error\":{\"message\":\"API " +
            "has not been used in project 482913756123 before or it is disabled\"}}"
        val short = WidgetRenderer.shortError(raw)
        assertFalse(short, short.contains("482913756123"))
        assertEquals("Update failed", short)
    }
}
