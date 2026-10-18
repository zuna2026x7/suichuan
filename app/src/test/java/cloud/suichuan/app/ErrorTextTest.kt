package cloud.suichuan.app

import cloud.suichuan.app.net.TransferCancelledException
import cloud.suichuan.app.util.ErrorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorTextTest {

    @Test
    fun englishNetworkErrorsBecomeChinese() {
        assertEquals(
            "连不上网络，检查一下 Wi-Fi 或手机流量是否打开。",
            ErrorText.of(UnknownHostException("Unable to resolve host"))
        )
        assertEquals(
            "网络太慢，等了很久没有响应。换个网络再试一次。",
            ErrorText.of(SocketTimeoutException("timeout"))
        )
        assertEquals(
            "连不上服务器，可能是网络不通或服务器暂时不可用，稍后再试。",
            ErrorText.of(IOException("wrapped", ConnectException("Failed to connect to /1.2.3.4")))
        )
    }

    @Test
    fun chineseMessagesPassThrough() {
        assertEquals("取件码过期了。", ErrorText.of(IOException("取件码过期了")))
        assertEquals("已取消。", ErrorText.of(TransferCancelledException()))
    }

    @Test
    fun unknownEnglishUsesFallback() {
        assertEquals("兜底。", ErrorText.of(IllegalStateException("boom"), "兜底。"))
        assertEquals("兜底。", ErrorText.of(null, "兜底。"))
    }

    @Test
    fun serverErrorField() {
        assertEquals("文件太大了", ErrorText.serverError("{\"error\":\"文件太大了\"}"))
        assertNull(ErrorText.serverError("not json"))
        assertNull(ErrorText.serverError(""))
    }

    @Test
    fun mimeLabels() {
        assertEquals("PDF 文档", ErrorText.mimeLabel("application/pdf"))
        assertEquals("图片", ErrorText.mimeLabel("image/jpeg"))
        assertEquals("安装包（APK）", ErrorText.mimeLabel("", "a.apk"))
        assertEquals("压缩包", ErrorText.mimeLabel("application/octet-stream", "x.zip"))
        assertEquals("文件", ErrorText.mimeLabel("application/octet-stream"))
    }

    @Test
    fun codeGrouping() {
        assertEquals("123 456", ErrorText.groupCode("123456"))
        assertEquals("12345", ErrorText.groupCode("12345"))
    }
}
