package ru.nedestroy.nedestroyme

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ExifInterface
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Range
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SilentCameraService : Service() {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { cleanupAndExit() }
    private var videoStopRunnable: Runnable? = null

    private lateinit var prefs: AppPreferences

    @Volatile
    private var isCapturing = false
    @Volatile
    private var isRecordingVideo = false
    private val isClosed = AtomicBoolean(false)

    private var photosSaved = 0
    private var targetPhotoCount = 1
    private var attemptsCount = 1

    private var sensorOrientationDegrees = 270
    private var captureTimestamp: Long = 0L
    private var videoStartTime: Long = 0L

    // ID сообщения первого текстового алерта в Telegram для создания цепочки ответов
    private var initialTelegramMessageId: Long? = null

    private lateinit var incidentDir: File
    private var videoFile: File? = null

    // Постоянный GPS-трекер на время съемки
    private var locationManager: LocationManager? = null
    private var bestLocation: Location? = null
    private var locationListener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = AppPreferences(this)
        if (!prefs.isProtectionEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRecordingVideo) {
            attemptsCount++
            extendVideoTimer()
            return START_NOT_STICKY
        }
        if (isClosed.get()) return START_NOT_STICKY

        prefs = AppPreferences(this)
        targetPhotoCount = prefs.photoCount
        attemptsCount = 1
        captureTimestamp = System.currentTimeMillis()

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(captureTimestamp))
        val baseFolder = File(getExternalFilesDir(null), "intruders").apply { if (!exists()) mkdirs() }
        incidentDir = File(baseFolder, "EVENT_$timeStamp").apply { mkdirs() }

        startBackgroundThread()
        startForegroundNotification()

        val maxTimeout = if (prefs.captureMode == "VIDEO") {
            (prefs.videoDuration + 30) * 1000L
        } else {
            (12000 + targetPhotoCount * 3000).toLong()
        }
        mainHandler.postDelayed(watchdog, maxTimeout)

        startContinuousLocationTracking()

        // 2. Старт съемки фото или видео
        if (prefs.captureMode == "VIDEO") {
            startVideoRecordingPipeline()
        } else {
            startPhotoCapturePipeline()
        }

        return START_NOT_STICKY
    }

    private fun extendVideoTimer() {
        val extensionMillis = prefs.videoDuration * 1000L
        videoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(watchdog)

        videoStopRunnable = Runnable { stopAndFinalizeVideo() }
        mainHandler.postDelayed(videoStopRunnable!!, extensionMillis)
        mainHandler.postDelayed(watchdog, extensionMillis + 20000L)
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join(1000)
            backgroundThread = null
            backgroundHandler = null
        } catch (_: Exception) {}
    }

    private fun startForegroundNotification() {
        val channelId = "security_cam"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Security", NotificationManager.IMPORTANCE_MIN)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("nedestroyme")
            .setContentText("Фиксирую улов...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (hasAudio) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            startForeground(1337, notification, type)
        } else {
            startForeground(1337, notification)
        }
    }

    private fun startContinuousLocationTracking() {
        if (!prefs.isGeoEnabled) return
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            for (provider in locationManager?.getProviders(true) ?: emptyList()) {
                @SuppressLint("MissingPermission")
                val loc = locationManager?.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation!!.accuracy) {
                    bestLocation = loc
                }
            }

            locationListener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (bestLocation == null || loc.accuracy < bestLocation!!.accuracy || (loc.time - bestLocation!!.time > 10000)) {
                        bestLocation = loc
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                @SuppressLint("MissingPermission")
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener!!, Looper.getMainLooper())
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                @SuppressLint("MissingPermission")
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 500L, 0f, locationListener!!, Looper.getMainLooper())
            }
        } catch (_: Exception) {}
    }

    private fun stopLocationTracking() {
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
            locationListener = null
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun startVideoRecordingPipeline() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontCamId = getBestFrontCameraId(manager) ?: run { cleanupAndExit(); return }
        val chars = manager.getCameraCharacteristics(frontCamId)
        sensorOrientationDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270

        videoFile = File(incidentDir, "video.mp4")

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
        val videoSize = videoSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: videoSizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: Size(1280, 720)

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        mediaRecorder?.apply {
            if (hasAudio) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoFile!!.absolutePath)

            if (hasAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)
            }

            setVideoEncodingBitRate(6_000_000)
            setVideoFrameRate(30)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setOrientationHint(sensorOrientationDegrees)
            prepare()
        }

        val recorderSurface = mediaRecorder!!.surface

        manager.openCamera(frontCamId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (isClosed.get()) { camera.close(); return }
                cameraDevice = camera

                val recordBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(recorderSurface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    configureSharpnessAndOptics(this, chars)
                }

                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    listOf(recorderSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (isClosed.get()) return
                            captureSession = session
                            try {
                                session.setRepeatingRequest(recordBuilder.build(), null, backgroundHandler)
                                mediaRecorder?.start()
                                isRecordingVideo = true
                                videoStartTime = System.currentTimeMillis()

                                extendVideoTimer()
                            } catch (_: Exception) {
                                cleanupAndExit()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) = cleanupAndExit()
                    },
                    backgroundHandler
                )
            }

            override fun onDisconnected(camera: CameraDevice) = cleanupAndExit()
            override fun onError(camera: CameraDevice, error: Int) = cleanupAndExit()
        }, backgroundHandler)
    }

    private fun stopAndFinalizeVideo() {
        if (!isRecordingVideo) return
        isRecordingVideo = false
        val durationSec = ((System.currentTimeMillis() - videoStartTime) / 1000).toInt().coerceAtLeast(1)

        try {
            captureSession?.stopRepeating()
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (_: Exception) {}

        finalizeAndSendAlerts(isVideo = true, durationSec = durationSec)
    }

    @SuppressLint("MissingPermission")
    private fun startPhotoCapturePipeline() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontCamId = getBestFrontCameraId(manager) ?: run { cleanupAndExit(); return }
        val chars = manager.getCameraCharacteristics(frontCamId)
        sensorOrientationDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        val candidateSizes = jpegSizes.filter { size ->
            val ratio = size.width.toFloat() / size.height
            (ratio in 1.25f..1.4f || (1f / ratio) in 1.25f..1.4f) && size.width <= 3264 && size.height <= 3264
        }
        val bestSize = candidateSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: jpegSizes.filter { it.width <= 3264 && it.height <= 3264 }.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(1920, 1080)

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val bestFpsRange = fpsRanges?.firstOrNull { it.lower >= 24 && it.upper >= 24 }
            ?: fpsRanges?.maxByOrNull { it.lower }
            ?: Range(15, 30)

        imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, targetPhotoCount + 2).apply {
            setOnImageAvailableListener({ reader ->
                try {
                    if (isClosed.get()) return@setOnImageAvailableListener
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    if (isCapturing && photosSaved < targetPhotoCount) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        photosSaved++
                        processAndSavePhoto(bytes, photosSaved)

                        if (photosSaved >= targetPhotoCount) {
                            finalizeAndSendAlerts(isVideo = false, durationSec = 0)
                        }
                    } else {
                        image.close()
                    }
                } catch (_: Throwable) {}
            }, backgroundHandler)
        }

        manager.openCamera(frontCamId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (isClosed.get()) { camera.close(); return }
                cameraDevice = camera

                val warmupBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader!!.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFpsRange)
                    configureSharpnessAndOptics(this, chars)
                }

                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    listOf(imageReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (isClosed.get()) return
                            captureSession = session
                            try {
                                session.setRepeatingRequest(warmupBuilder.build(), null, backgroundHandler)
                                backgroundHandler?.postDelayed({
                                    executeBurstCaptures(camera, session, chars, bestFpsRange)
                                }, 1000)
                            } catch (_: Exception) {
                                cleanupAndExit()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) = cleanupAndExit()
                    },
                    backgroundHandler
                )
            }

            override fun onDisconnected(camera: CameraDevice) = cleanupAndExit()
            override fun onError(camera: CameraDevice, error: Int) = cleanupAndExit()
        }, backgroundHandler)
    }

    private fun executeBurstCaptures(
        camera: CameraDevice,
        session: CameraCaptureSession,
        chars: CameraCharacteristics,
        fpsRange: Range<Int>
    ) {
        try {
            if (isClosed.get()) return
            session.stopRepeating()
            isCapturing = true

            fun requestSingle(index: Int) {
                if (isClosed.get() || cameraDevice == null || captureSession == null) return
                val stillBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader!!.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    set(CaptureRequest.JPEG_QUALITY, 94.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, sensorOrientationDegrees)
                    configureSharpnessAndOptics(this, chars)
                }

                session.capture(stillBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        if (index + 1 < targetPhotoCount) {
                            backgroundHandler?.postDelayed({ requestSingle(index + 1) }, 1000)
                        }
                    }
                }, backgroundHandler)
            }

            requestSingle(0)
        } catch (_: Exception) {
            cleanupAndExit()
        }
    }

    // Финализация: остановка GPS-трекера, геокодирование и отправка ОДНОГО финального сообщения
    private fun finalizeAndSendAlerts(isVideo: Boolean, durationSec: Int) {
        stopLocationTracking()

        val lat = bestLocation?.latitude
        val lon = bestLocation?.longitude
        val accuracy = bestLocation?.accuracy

        var finalAddress = if (lat != null && lon != null) {
            "Координаты: %.5f, %.5f".format(lat, lon)
        } else {
            "Координаты не зафиксированы"
        }

        Thread {
            if (lat != null && lon != null) {
                try {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocation(lat, lon, 1)
                    if (!list.isNullOrEmpty()) {
                        val addr = list[0]
                        finalAddress = addr.getAddressLine(0) ?: "${addr.locality ?: ""}, ${addr.thoroughfare ?: ""}"
                    }
                } catch (_: Exception) {}
            }

            // 1. Сохраняем локальные метаданные
            saveIncidentMetadata(isVideo, durationSec, finalAddress, lat, lon)

            // 2. Локальное уведомление на телефоне
            showIntruderNotification(isVideo, finalAddress)

            // 3. Единственное полное сообщение в Telegram со всеми данными
            sendTelegramFinalAlert(isVideo, durationSec, finalAddress, lat, lon, accuracy)

            cleanupAndExit()
        }.start()
    }

    private fun sendTelegramFinalAlert(
        isVideo: Boolean,
        durationSec: Int,
        address: String,
        lat: Double?,
        lon: Double?,
        accuracy: Float?
    ) {
        if (!prefs.isTelegramEnabled || prefs.telegramToken.isBlank() || prefs.telegramChatId.isBlank()) return

        val time = SimpleDateFormat("dd.MM.yyyy в HH:mm:ss", Locale.getDefault()).format(Date(captureTimestamp))
        val mapLink = if (lat != null && lon != null) {
            "\n\n🗺 <a href=\"https://maps.google.com/?q=$lat,$lon\">Показать на карте</a>"
        } else ""

        val modeInfo = if (isVideo) {
            "📹 <b>Записано видео:</b> ${durationSec}s\n            🔢 <b>Попыток ввода пароля:</b> $attemptsCount"
        } else {
            "📸 <b>Серия фото:</b> $photosSaved шт."
        }

        val accuracyInfo = if (accuracy != null) " (точность: ±${accuracy.toInt()}м)" else ""

        val text = """
            🚨 <b>Неудачная попытка входа!</b>
            🕒 <b>Время:</b> $time
            $modeInfo
            📍 <b>Локация:</b> $address$accuracyInfo$mapLink
        """.trimIndent()

        TelegramSender.sendTextMessage(
            token = prefs.telegramToken,
            chatId = prefs.telegramChatId,
            textHtml = text
        )
    }

    private fun showIntruderNotification(isVideo: Boolean, address: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "intruder_alerts"

            val channel = NotificationChannel(channelId, "Тревога: попытка взлома", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)

            val clickIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(captureTimestamp))
            val title = if (isVideo) "🚨 Неудачная попытка входа!" else "🚨 Неудачная попытка входа!"

            val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentText("Неверный пароль в $time ($attemptsCount поп.).")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (_: Throwable) {}
    }

    private fun getBestFrontCameraId(manager: CameraManager): String? {
        val frontCams = manager.cameraIdList.filter { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        if (frontCams.isEmpty()) return null
        return if (prefs.isWideLensEnabled && frontCams.size > 1) {
            frontCams.minByOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE
            } ?: frontCams.first()
        } else {
            frontCams.first()
        }
    }

    private fun configureSharpnessAndOptics(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (activeArray != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, activeArray)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && prefs.isWideLensEnabled) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null && range.lower < 1.0f) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, range.lower)
            }
        }

        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val afMode = when {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CameraMetadata.CONTROL_AF_MODE_AUTO in afModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> CameraMetadata.CONTROL_AF_MODE_OFF
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
    }

    private fun processAndSavePhoto(rawBytes: ByteArray, index: Int) {
        try {
            val photoFile = File(incidentDir, "photo_$index.jpg")
            photoFile.outputStream().use { it.write(rawBytes) }

            try {
                val exif = ExifInterface(photoFile.absolutePath)
                val exifVal = when (sensorOrientationDegrees) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifVal.toString())
                exif.saveAttributes()
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun saveIncidentMetadata(isVideo: Boolean, durationSec: Int, address: String, lat: Double?, lon: Double?) {
        try {
            val jsonFile = File(incidentDir, "meta.json")
            val metaJson = JSONObject().apply {
                put("timestamp", captureTimestamp)
                put("type", if (isVideo) "video" else "photo")
                put("duration", durationSec)
                put("attemptsCount", attemptsCount)
                put("address", address)
                put("lat", lat ?: JSONObject.NULL)
                put("lon", lon ?: JSONObject.NULL)
            }
            jsonFile.writeText(metaJson.toString())
        } catch (_: Throwable) {}
    }

    private fun cleanupAndExit() {
        if (isClosed.getAndSet(true)) return
        mainHandler.removeCallbacks(watchdog)
        videoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        stopLocationTracking()

        try {
            if (isRecordingVideo) mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (_: Throwable) {}

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
        } catch (_: Throwable) {}

        stopBackgroundThread()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}