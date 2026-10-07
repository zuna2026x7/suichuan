package cloud.suichuan.app.net

import cloud.suichuan.app.model.TransferPayload
import cloud.suichuan.app.util.PayloadCodec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Client for the optional pickup-code worker (see worker/).
 * When no base URL is configured the app simply skips codes and works with
 * QR / share text only. Blocking: call from a background thread.
 */
object BackendClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Registers a transfer and returns its 6-digit pickup code. */
    fun register(base: String, payload: TransferPayload): String {
        val url = base.trimEnd('/') + "/t"
        val request = Request.Builder()
            .url(url)
            .post(PayloadCodec.encode(payload).toRequestBody(JSON))
            .build()
        HttpClients.instance.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("取件码服务出错（${response.code}）")
            }
            val map = PayloadCodec.parseMap(text) ?: throw IOException("取件码服务返回的内容看不懂")
            return map["code"]?.takeIf { it.isNotBlank() }
                ?: throw IOException("取件码服务没有返回取件码")
        }
    }

    /** Looks up a transfer by its 6-digit pickup code. */
    fun fetchByCode(base: String, code: String): TransferPayload {
        val url = base.trimEnd('/') + "/t/" + code
        val request = Request.Builder().url(url).get().build()
        HttpClients.instance.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404) {
                throw IOException("这个取件码不存在，或者已经过期了。让对方重新发送一次。")
            }
            if (!response.isSuccessful) {
                throw IOException("取件码服务出错（${response.code}）")
            }
            return PayloadCodec.decode(text)
                ?: throw IOException("取件码对应的内容看不懂，让对方重新发送一次。")
        }
    }
}
