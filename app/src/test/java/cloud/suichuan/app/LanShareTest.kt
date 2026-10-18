package cloud.suichuan.app

import cloud.suichuan.app.lan.LanAddress
import cloud.suichuan.app.lan.MiniHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LanShareTest {

    private lateinit var apk: File
    private lateinit var server: MiniHttpServer
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        apk = File.createTempFile("suichuan", ".apk").apply { writeBytes(bytes) }
        server = MiniHttpServer(apk, "suichuan-1.4.apk", "随传", "1.4", token = "abc23xyz")
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        apk.delete()
    }

    private fun open(path: String, range: String? = null, ua: String? = null): HttpURLConnection {
        val c = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        range?.let { c.setRequestProperty("Range", it) }
        ua?.let { c.setRequestProperty("User-Agent", it) }
        return c
    }

    @Test
    fun servesDownloadPage() {
        val c = open("/abc23xyz/")
        assertEquals(200, c.responseCode)
        assertTrue(c.contentType.startsWith("text/html"))
        val html = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(html.contains("/abc23xyz/suichuan-1.4.apk"))
        assertTrue(html.contains("下载安装"))
        assertFalse(html.contains("在浏览器打开"))
    }

    @Test
    fun warnsInsideWeChat() {
        val c = open("/abc23xyz/", ua = "Mozilla/5.0 MicroMessenger/8.0.40")
        val html = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(html.contains("在浏览器打开"))
    }

    @Test
    fun servesWholeApk() {
        val c = open("/abc23xyz/suichuan-1.4.apk")
        assertEquals(200, c.responseCode)
        assertEquals("application/vnd.android.package-archive", c.contentType)
        assertEquals(bytes.size.toLong(), c.contentLengthLong)
        assertTrue(c.getHeaderField("Content-Disposition").contains("suichuan-1.4.apk"))
        assertTrue(bytes.contentEquals(c.inputStream.readBytes()))
    }

    @Test
    fun resumesWithRange() {
        val c = open("/abc23xyz/suichuan-1.4.apk", range = "bytes=1000-")
        assertEquals(206, c.responseCode)
        assertEquals((bytes.size - 1000).toLong(), c.contentLengthLong)
        assertTrue(bytes.copyOfRange(1000, bytes.size).contentEquals(c.inputStream.readBytes()))
    }

    @Test
    fun wrongTokenIs404() {
        assertEquals(404, open("/wrong/suichuan-1.4.apk").responseCode)
        assertEquals(404, open("/").responseCode)
    }

    @Test
    fun tokensAreRandomAndUrlSafe() {
        val a = MiniHttpServer.newToken()
        val b = MiniHttpServer.newToken()
        assertEquals(8, a.length)
        assertTrue(a != b)
        assertTrue(a.all { it.isLetterOrDigit() })
    }

    @Test
    fun picksWifiOverHotspotAndSkipsMobileData() {
        val list = listOf(
            LanAddress.Candidate("rmnet_data0", "10.45.3.2"),
            LanAddress.Candidate("ap0", "192.168.43.1"),
            LanAddress.Candidate("wlan0", "192.168.1.23"),
        )
        assertEquals("192.168.1.23", LanAddress.pick(list))
        assertEquals("192.168.43.1", LanAddress.pick(list.filter { it.iface != "wlan0" }))
        assertNull(LanAddress.pick(list.take(1)))
        assertNull(LanAddress.pick(listOf(LanAddress.Candidate("wlan0", "100.64.0.5"))))
    }

    @Test
    fun privateRanges() {
        assertTrue(LanAddress.isPrivate("172.20.10.1"))
        assertFalse(LanAddress.isPrivate("172.32.0.1"))
        assertFalse(LanAddress.isPrivate("8.8.8.8"))
    }
}
