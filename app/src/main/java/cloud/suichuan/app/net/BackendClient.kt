package cloud.suichuan.app.net

import cloud.suichuan.app.model.TransferPayload
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.PayloadCodec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Client for our own pickup backend (see worker/): a Cloudflare Worker with
 * KV metadata + R2 file storage. When a base URL is configured the app does
 * not depend on any third-party file host at all:
 *
 *   createTransfer()  POST /t            -> pickup code + single-use upload token
 *   uploadFile()      PUT /f/<token>     -> raw file body into R2
 *   createTextTransfer() POST /t         -> text message transfer: the text
 *                     itself is the payload, ready the moment it is created
 *   fetchTransfer()   GET /t/<code>      -> metadata + ready flag (text
 *                     transfers also carry kind/text in the metadata)
 *                     (the file itself is then downloaded from <base>/f/<code>)
 *
 * Blocking: call every function here from a background thread.
 */
object BackendClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class CreatedTransfer(val code: String, val uploadToken: String)

    data class FetchedTransfer(
        val payload: TransferPayload,
        val ready: Boolean,
        // Text transfers only: kind == "text" and [text] holds the message
        // itself (it travels inside the metadata — no file follows). Both
        // stay null for ordinary file transfers.
        val kind: String? = null,
        val text: String? = null
    )

    /** Registers a transfer's metadata and returns its code + upload token. */
    fun createTransfer(base: String, payload: TransferPayload): CreatedTransfer {
        val trimmedBase = base.trimEnd('/')
        // Ask for the full 72h so the code lives as long as the UI promises.
        val bodyMap = payload.toMap() + ("ttlSeconds" to (72 * 60 * 60).toString())
        val request = Request.Builder()
            .url("$trimmedBase/t")
            .post(PayloadCodec.writeMap(bodyMap).toRequestBody(JSON))
            .build()
        return try {
            HttpClients.instance.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("取件服务出错（${response.code}），暂时没法发送。")
                }
                val map = PayloadCodec.parseMap(text)
                    ?: throw IOException("取件服务返回的内容看不懂，暂时没法发送。")
                val code = map["code"]?.takeIf { it.isNotBlank() }
                    ?: throw IOException("取件服务没有返回取件码。")
                val uploadToken = map["uploadToken"]?.takeIf { it.isNotBlank() }
                    ?: throw IOException("取件服务没有返回上传凭证。")
                AppLog.log("API", "POST /t -> ${response.code}，取件码=$code")
                CreatedTransfer(code, uploadToken)
            }
        } catch (e: Exception) {
            AppLog.log("API", "POST /t 失败", e)
            throw e
        }
    }

    /**
     * Registers a text message as a transfer and returns its pickup code.
     * The text itself is the payload — the server stores it in the record
     * and the transfer is ready immediately, so there is no upload step.
     * (The returned upload token exists only to keep the create response
     * shape identical; it is never used.)
     */
    fun createTextTransfer(base: String, text: String): CreatedTransfer {
        val trimmedBase = base.trimEnd('/')
        val bodyMap = linkedMapOf(
            "kind" to "text",
            "text" to text,
            "ttlSeconds" to (72 * 60 * 60).toString()
        )
        val request = Request.Builder()
            .url("$trimmedBase/t")
            .post(PayloadCodec.writeMap(bodyMap).toRequestBody(JSON))
            .build()
        return try {
            HttpClients.instance.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("取件服务出错（${response.code}），暂时没法发送。")
                }
                val map = PayloadCodec.parseMap(responseText)
                    ?: throw IOException("取件服务返回的内容看不懂，暂时没法发送。")
                val code = map["code"]?.takeIf { it.isNotBlank() }
                    ?: throw IOException("取件服务没有返回取件码。")
                val uploadToken = map["uploadToken"]?.takeIf { it.isNotBlank() }
                    ?: throw IOException("取件服务没有返回上传凭证。")
                // The message itself never goes into the log — only its size.
                AppLog.log(
                    "API",
                    "POST /t（文字） -> ${response.code}，取件码=$code，" +
                        "长度=${text.toByteArray(Charsets.UTF_8).size} 字节"
                )
                CreatedTransfer(code, uploadToken)
            }
        } catch (e: Exception) {
            AppLog.log("API", "POST /t（文字）失败", e)
            throw e
        }
    }

    /** Uploads the packed file as the raw request body. Single-use token. */
    fun uploadFile(
        base: String,
        uploadToken: String,
        file: File,
        onProgress: (written: Long, total: Long) -> Unit
    ) {
        val trimmedBase = base.trimEnd('/')
        val rawBody = file.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull())
        val counting = CountingRequestBody(rawBody, onProgress)
        val request = Request.Builder()
            .url("$trimmedBase/f/$uploadToken")
            .put(counting)
            .build()
        try {
            HttpClients.instance.newCall(request).execute().use { response ->
                // Drain the body so the connection can be reused.
                response.body?.string()
                if (!response.isSuccessful) {
                    throw IOException("文件没有传上去（${response.code}）。可以重试，或者改用临时托管发送。")
                }
                // The token itself never goes into the log — only its shape.
                AppLog.log("API", "PUT /f/:token -> ${response.code}，bytes=${file.length()}")
            }
        } catch (e: Exception) {
            AppLog.log("API", "PUT /f/:token 失败", e)
            throw e
        }
    }

    /** Looks a transfer up by pickup code for the receiving side. */
    fun fetchTransfer(base: String, code: String): FetchedTransfer {
        val trimmedBase = base.trimEnd('/')
        val request = Request.Builder().url("$trimmedBase/t/$code").get().build()
        return try {
            HttpClients.instance.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 404) {
                    throw IOException("这个取件码不存在，或者已经过期了。让对方重新发送一次。")
                }
                if (!response.isSuccessful) {
                    throw IOException("取件服务出错（${response.code}），稍后再试一次。")
                }
                val map = PayloadCodec.parseMap(text)
                    ?: throw IOException("取件码对应的内容看不懂，让对方重新发送一次。")
                // The worker never stores a download URL; the file is addressed
                // by code, so fill it in here for the downloader.
                val completed = map + ("downloadUrl" to "$trimmedBase/f/$code")
                val payload = TransferPayload.fromMap(completed)
                    ?: throw IOException("取件码对应的内容看不懂，让对方重新发送一次。")
                val ready = map["ready"]?.equals("true", ignoreCase = true) == true
                // Text transfers carry the message itself in the metadata.
                val kind = map["kind"]?.takeIf { it.isNotBlank() }
                val message = if (kind == "text") map["text"] else null
                AppLog.log(
                    "API",
                    "GET /t/$code -> ${response.code}，ready=$ready，kind=${kind ?: "file"}，" +
                        "应用=${payload.appName}，size=${payload.sizeBytes}"
                )
                FetchedTransfer(payload, ready, kind, message)
            }
        } catch (e: Exception) {
            AppLog.log("API", "GET /t/$code 失败", e)
            throw e
        }
    }
}
