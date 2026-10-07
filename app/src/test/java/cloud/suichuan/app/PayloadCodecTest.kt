package cloud.suichuan.app

import cloud.suichuan.app.model.TransferPayload
import cloud.suichuan.app.util.PayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PayloadCodecTest {

    private fun sample() = TransferPayload(
        appName = "微信 \"测试\" \\ 换行\n第二行",
        packageName = "com.example.app",
        versionName = "1.2.3",
        sizeBytes = 123456789L,
        sha256 = "abcdef0123456789",
        fileName = "微信.apks",
        downloadUrl = "https://litter.catbox.moe/abc123.apks"
    )

    @Test
    fun roundTripPreservesEverything() {
        val payload = sample()
        val decoded = PayloadCodec.decode(PayloadCodec.encode(payload))
        assertEquals(payload, decoded)
    }

    @Test
    fun extractFromShareTextFindsPayload() {
        val payload = sample()
        val text = PayloadCodec.shareText(payload, "123456")
        val decoded = PayloadCodec.extractFromText(text)
        assertNotNull(decoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun parserAcceptsNumericValuesFromBackend() {
        // The worker stores sizeBytes as a JSON string, but be liberal: a
        // backend (or a future version) might return it as a raw number.
        val json = """{"appName":"A","packageName":"p","versionName":"1","sizeBytes":42,"sha256":"s","fileName":"f.apk","downloadUrl":"https://x/y.apk"}"""
        val decoded = PayloadCodec.decode(json)
        assertNotNull(decoded)
        assertEquals(42L, decoded!!.sizeBytes)
    }

    @Test
    fun parserRejectsGarbage() {
        assertNull(PayloadCodec.decode("not json at all"))
        assertNull(PayloadCodec.decode("""{"unrelated":"x"}""")) // nothing identifiable
        assertNull(PayloadCodec.extractFromText("取件码：123456"))
    }

    @Test
    fun roundTripWithoutDownloadUrl() {
        // Backend mode: the payload is addressed by pickup code, so there is
        // no direct download URL yet — it must still round-trip.
        val payload = sample().copy(downloadUrl = "")
        val decoded = PayloadCodec.decode(PayloadCodec.encode(payload))
        assertEquals(payload, decoded)
    }

    @Test
    fun backendRecordWithoutDownloadUrlDecodes() {
        // What GET /t/:code returns: metadata + ready, no downloadUrl field.
        val json = """{"appName":"A","packageName":"p","versionName":"1","sizeBytes":42,"sha256":"s","fileName":"f.apk","ready":true}"""
        val decoded = PayloadCodec.decode(json)
        assertNotNull(decoded)
        assertEquals("", decoded!!.downloadUrl)
        assertEquals("f.apk", decoded.fileName)
    }

    @Test
    fun parseMapReadsWorkerResponses() {
        val map = PayloadCodec.parseMap("""{"code":"654321","deleteToken":"tok123"}""")
        assertNotNull(map)
        assertEquals("654321", map!!["code"])
        assertEquals("tok123", map["deleteToken"])
    }
}
