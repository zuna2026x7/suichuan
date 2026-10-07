package cloud.suichuan.app.net

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

/**
 * Uploads one file to Litterbox (the temporary sister service of catbox.moe).
 * Files expire after the requested time (this MVP always uses 72h, the max)
 * and the response body is the direct download URL.
 *
 * Blocking: call from a background thread.
 */
object LitterboxUploader {

    private const val API_URL = "https://litterbox.catbox.moe/resources/internals/api.php"
    const val EXPIRES_TEXT = "72h"

    fun upload(file: File, onProgress: (written: Long, total: Long) -> Unit): String {
        val fileBody = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("time", EXPIRES_TEXT)
            .addFormDataPart("fileToUpload", file.name, fileBody)
            .build()
        val counting = CountingRequestBody(multipart, onProgress)
        val request = Request.Builder()
            .url(API_URL)
            .post(counting)
            .build()
        HttpClients.instance.newCall(request).execute().use { response ->
            val bodyText = response.body?.string()?.trim().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("上传服务返回了错误（${response.code}）。请稍后再试。")
            }
            if (!bodyText.startsWith("http")) {
                throw IOException("上传没有成功：$bodyText")
            }
            return bodyText
        }
    }
}
