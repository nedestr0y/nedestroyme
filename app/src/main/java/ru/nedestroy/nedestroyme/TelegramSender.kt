package ru.nedestroy.nedestroyme

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TelegramSender {

    private const val API_URL = "https://api.telegram.org/bot"

    // 1. Мгновенная отправка текста (возвращает message_id отправленного сообщения)
    fun sendTextMessage(
        token: String,
        chatId: String,
        textHtml: String,
        replyToMessageId: Long? = null
    ): Long? {
        if (token.isBlank() || chatId.isBlank()) return null
        return try {
            val encodedText = URLEncoder.encode(textHtml, "UTF-8")
            var urlStr = "$API_URL$token/sendMessage?chat_id=$chatId&text=$encodedText&parse_mode=HTML"
            if (replyToMessageId != null) {
                urlStr += "&reply_to_message_id=$replyToMessageId"
            }

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
            }

            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                if (json.optBoolean("ok")) {
                    json.getJSONObject("result").optLong("message_id")
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // 2. Отправка медиа инцидента (альбом фото или видео) в ответ на первое сообщение
    fun sendIncidentMedia(
        token: String,
        chatId: String,
        files: List<File>,
        isVideo: Boolean,
        captionHtml: String,
        replyToMessageId: Long? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (token.isBlank() || chatId.isBlank() || files.isEmpty()) {
            onComplete?.invoke(false)
            return
        }

        Thread {
            val success = try {
                if (isVideo) {
                    sendSingleVideo(token, chatId, files.first(), captionHtml, replyToMessageId)
                } else {
                    if (files.size == 1) {
                        sendSinglePhoto(token, chatId, files.first(), captionHtml, replyToMessageId)
                    } else {
                        sendMediaGroupPhotos(token, chatId, files.take(10), captionHtml, replyToMessageId)
                    }
                }
            } catch (_: Exception) {
                false
            }
            onComplete?.invoke(success)
        }.start()
    }

    // Отправка сетки/альбома из нескольких фото (sendMediaGroup)
    private fun sendMediaGroupPhotos(
        token: String,
        chatId: String,
        photoFiles: List<File>,
        captionHtml: String,
        replyToMessageId: Long?
    ): Boolean {
        val boundary = "Boundary-" + System.currentTimeMillis()
        val url = URL("$API_URL$token/sendMediaGroup")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 40000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        val mediaArray = JSONArray()
        photoFiles.forEachIndexed { index, _ ->
            val mediaObj = JSONObject().apply {
                put("type", "photo")
                put("media", "attach://photo_$index")
                if (index == 0 && captionHtml.isNotBlank()) {
                    put("caption", captionHtml)
                    put("parse_mode", "HTML")
                }
            }
            mediaArray.put(mediaObj)
        }

        val multipart = MultipartWriter(conn.outputStream, boundary)
        multipart.addField("chat_id", chatId)
        multipart.addField("media", mediaArray.toString())
        if (replyToMessageId != null) {
            multipart.addField("reply_to_message_id", replyToMessageId.toString())
        }

        photoFiles.forEachIndexed { index, file ->
            multipart.addFile("photo_$index", file, "image/jpeg")
        }

        val code = multipart.finish()
        return code in 200..299
    }

    // Отправка одного фото (sendPhoto)
    private fun sendSinglePhoto(
        token: String,
        chatId: String,
        photoFile: File,
        captionHtml: String,
        replyToMessageId: Long?
    ): Boolean {
        val boundary = "Boundary-" + System.currentTimeMillis()
        val url = URL("$API_URL$token/sendPhoto")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        val multipart = MultipartWriter(conn.outputStream, boundary)
        multipart.addField("chat_id", chatId)
        if (captionHtml.isNotBlank()) {
            multipart.addField("caption", captionHtml)
            multipart.addField("parse_mode", "HTML")
        }
        if (replyToMessageId != null) {
            multipart.addField("reply_to_message_id", replyToMessageId.toString())
        }
        multipart.addFile("photo", photoFile, "image/jpeg")

        val code = multipart.finish()
        return code in 200..299
    }

    // Отправка видеофайла (sendVideo)
    private fun sendSingleVideo(
        token: String,
        chatId: String,
        videoFile: File,
        captionHtml: String,
        replyToMessageId: Long?
    ): Boolean {
        val boundary = "Boundary-" + System.currentTimeMillis()
        val url = URL("$API_URL$token/sendVideo")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        val multipart = MultipartWriter(conn.outputStream, boundary)
        multipart.addField("chat_id", chatId)
        multipart.addField("supports_streaming", "true")
        if (captionHtml.isNotBlank()) {
            multipart.addField("caption", captionHtml)
            multipart.addField("parse_mode", "HTML")
        }
        if (replyToMessageId != null) {
            multipart.addField("reply_to_message_id", replyToMessageId.toString())
        }
        multipart.addFile("video", videoFile, "video/mp4")

        val code = multipart.finish()
        return code in 200..299
    }

    // Вспомогательный класс для прямой потоковой записи multipart-запроса без сторонних библиотек
    private class MultipartWriter(private val outputStream: OutputStream, private val boundary: String) {
        private val writer = PrintWriter(outputStream.bufferedWriter(Charsets.UTF_8), true)
        private val LINE = "\r\n"

        fun addField(name: String, value: String) {
            writer.append("--$boundary").append(LINE)
            writer.append("Content-Disposition: form-data; name=\"$name\"").append(LINE)
            writer.append("Content-Type: text/plain; charset=UTF-8").append(LINE)
            writer.append(LINE).append(value).append(LINE)
            writer.flush()
        }

        fun addFile(fieldName: String, file: File, mimeType: String) {
            writer.append("--$boundary").append(LINE)
            writer.append("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"").append(LINE)
            writer.append("Content-Type: $mimeType").append(LINE)
            writer.append(LINE)
            writer.flush()

            file.inputStream().use { input ->
                input.copyTo(outputStream)
            }
            outputStream.flush()
            writer.append(LINE)
            writer.flush()
        }

        fun finish(): Int {
            writer.append("--$boundary--").append(LINE)
            writer.flush()
            writer.close()
            return 200
        }
    }
}