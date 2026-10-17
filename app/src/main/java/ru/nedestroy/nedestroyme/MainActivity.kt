package ru.nedestroy.nedestroyme

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class IntruderIncident(
    val id: String,
    val folder: File,
    val isVideo: Boolean,
    val videoFile: File?,
    val photoFiles: List<File>,
    val durationSeconds: Int,
    val attemptsCount: Int,
    val dateText: String,
    val address: String,
    val lat: Double?,
    val lon: Double?
)

class MainActivity : ComponentActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var prefs: AppPreferences

    private var onPermissionsResult: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsResult?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)
        prefs = AppPreferences(this)

        setContent {
            val isDark = isSystemInDarkTheme()
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ctx = LocalContext.current
                if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (isDark) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        prefs = prefs,
                        isCameraGranted = { hasCameraPermission() },
                        isAudioGranted = { hasAudioPermission() },
                        isLocationGranted = { hasLocationPermission() },
                        isNotificationGranted = { hasNotificationPermission() },
                        isAdminActive = { dpm.isAdminActive(adminComponent) },
                        onRequestPermissions = { requestAppPermissions() },
                        onOpenAdminSettings = { openDeviceAdminSettings() },
                        loadIncidents = { loadIntruderIncidents() },
                        clearAllIncidents = { clearAllData() },
                        openMap = { lat, lon -> openMapLocation(lat, lon) }
                    )
                }
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestAppPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun loadIntruderIncidents(): List<IntruderIncident> {
        val baseFolder = File(getExternalFilesDir(null), "intruders")
        if (!baseFolder.exists()) return emptyList()

        val incidents = mutableListOf<IntruderIncident>()
        baseFolder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val video = File(file, "video.mp4").takeIf { it.exists() && it.length() > 0 }
                val photos = file.listFiles { f -> f.extension.lowercase() == "jpg" && f.length() > 0 }
                    ?.sortedBy { it.name } ?: emptyList()

                if (video != null || photos.isNotEmpty()) {
                    val jsonFile = File(file, "meta.json")
                    var timestamp = video?.lastModified() ?: photos.first().lastModified()
                    var address = "Координаты не зафиксированы"
                    var lat: Double? = null
                    var lon: Double? = null
                    var duration = 5
                    var attempts = 1

                    if (jsonFile.exists()) {
                        try {
                            val json = JSONObject(jsonFile.readText())
                            if (json.has("timestamp")) timestamp = json.optLong("timestamp")
                            address = json.optString("address", address)
                            duration = json.optInt("duration", 5)
                            attempts = json.optInt("attemptsCount", 1)
                            if (!json.isNull("lat")) lat = json.optDouble("lat")
                            if (!json.isNull("lon")) lon = json.optDouble("lon")
                        } catch (_: Exception) {}
                    }

                    incidents.add(
                        IntruderIncident(
                            id = file.name,
                            folder = file,
                            isVideo = (video != null),
                            videoFile = video,
                            photoFiles = photos,
                            durationSeconds = duration,
                            attemptsCount = attempts,
                            dateText = formatCalendarDate(timestamp),
                            address = address,
                            lat = lat,
                            lon = lon
                        )
                    )
                }
            } else if (file.extension.lowercase() == "jpg" && file.length() > 0) {
                incidents.add(
                    IntruderIncident(
                        id = file.nameWithoutExtension,
                        folder = file,
                        isVideo = false,
                        videoFile = null,
                        photoFiles = listOf(file),
                        durationSeconds = 0,
                        attemptsCount = 1,
                        dateText = formatCalendarDate(file.lastModified()),
                        address = "Координаты не зафиксированы",
                        lat = null,
                        lon = null
                    )
                )
            }
        }
        return incidents.sortedByDescending { it.folder.lastModified() }
    }

    private fun clearAllData() {
        val folder = File(getExternalFilesDir(null), "intruders")
        folder.deleteRecursively()
        folder.mkdirs()
    }

    private fun openMapLocation(lat: Double, lon: Double) {
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Место взлома)")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(mapIntent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon"))
            startActivity(webIntent)
        }
    }

    private fun openDeviceAdminSettings() {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Фиксация неверных паролей")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        }
    }
}

fun formatCalendarDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Неизвестное время"
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }

    val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))
    val timeStr = timeFormat.format(Date(timestamp))

    val isSameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val isToday = isSameYear && now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

    if (isToday) return "Сегодня в $timeStr"

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

    if (isYesterday) return "Вчера в $timeStr"

    return if (isSameYear) {
        SimpleDateFormat("d MMMM 'в' HH:mm", Locale("ru")).format(Date(timestamp))
    } else {
        SimpleDateFormat("d MMMM yyyy 'в' HH:mm", Locale("ru")).format(Date(timestamp))
    }
}

fun formatAttempts(count: Int): String {
    val rem100 = count % 100
    val rem10 = count % 10
    return when {
        rem100 in 11..19 -> "$count попыток"
        rem10 == 1 -> "$count попытка"
        rem10 in 2..4 -> "$count попытки"
        else -> "$count попыток"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    prefs: AppPreferences,
    isCameraGranted: () -> Boolean,
    isAudioGranted: () -> Boolean,
    isLocationGranted: () -> Boolean,
    isNotificationGranted: () -> Boolean,
    isAdminActive: () -> Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAdminSettings: () -> Unit,
    loadIncidents: () -> List<IntruderIncident>,
    clearAllIncidents: () -> Unit,
    openMap: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    var incidents by remember { mutableStateOf(loadIncidents()) }
    var selectedIncident by remember { mutableStateOf<IntruderIncident?>(null) }
    var deleteCandidate by remember { mutableStateOf<IntruderIncident?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    var cameraOk by remember { mutableStateOf(isCameraGranted()) }
    var audioOk by remember { mutableStateOf(isAudioGranted()) }
    var locationOk by remember { mutableStateOf(isLocationGranted()) }
    var adminOk by remember { mutableStateOf(isAdminActive()) }
    var notifOk by remember { mutableStateOf(isNotificationGranted()) }

    // Состояние активности приложения
    var isProtectionEnabled by remember { mutableStateOf(prefs.isProtectionEnabled) }

    // Векторная иконка кнопки включения питания (Power / PowerSettingsNew)
    val powerIcon = remember {
        ImageVector.Builder(
            name = "PowerSettingsNew",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = PathParser().parsePathString(
                "M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z"
            ).toNodes(),
            fill = SolidColor(Color.White)
        ).build()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraOk = isCameraGranted()
                audioOk = isAudioGranted()
                locationOk = isLocationGranted()
                adminOk = isAdminActive()
                notifOk = isNotificationGranted()
                isProtectionEnabled = prefs.isProtectionEnabled
                incidents = loadIncidents()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("nedestroyme", fontWeight = FontWeight.Bold) },
                actions = {
                    if (incidents.isNotEmpty()) {
                        TextButton(
                            onClick = { showClearAllDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Очистить")
                        }
                    }

                    // Кнопка переключения работы приложения в правом верхнем углу
                    IconButton(
                        onClick = {
                            val newState = !isProtectionEnabled
                            isProtectionEnabled = newState
                            prefs.isProtectionEnabled = newState
                            val msg = if (newState) "Защита включена" else "Защита выключена"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        val activeColor = Color(0xFF4CAF50) // Зеленый
                        val inactiveColor = Color(0xFFE53935) // Красный
                        val currentColor = if (isProtectionEnabled) activeColor else inactiveColor

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(currentColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = powerIcon,
                                contentDescription = if (isProtectionEnabled) "Защита включена" else "Защита выключена",
                                tint = currentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showSettingsSheet = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (incidents.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(12.dp))
                        Text("Пока попыток взлома нет", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(incidents, key = { it.id }) { incident ->
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (incident.lat != null && incident.lon != null) {
                                            openMap(incident.lat, incident.lon)
                                        } else {
                                            selectedIncident = incident
                                        }
                                    },
                                    onLongClick = { deleteCandidate = incident }
                                ),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .combinedClickable(
                                            onClick = { selectedIncident = incident },
                                            onLongClick = { deleteCandidate = incident }
                                        )
                                ) {
                                    val thumbnail = remember(incident.id) {
                                        if (incident.isVideo && incident.videoFile != null) {
                                            loadVideoThumbnail(incident.videoFile)
                                        } else {
                                            incident.photoFiles.firstOrNull()?.let { loadSampledBitmap(it, 4) }
                                        }
                                    }

                                    if (thumbnail != null) {
                                        Image(
                                            bitmap = thumbnail,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
                                    }

                                    Surface(
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color.Black.copy(alpha = 0.65f)
                                    ) {
                                        val badgeText = if (incident.isVideo) "🎥 ${incident.durationSeconds}s" else if (incident.photoFiles.size > 1) "📸 ${incident.photoFiles.size}" else null
                                        if (badgeText != null) {
                                            Text(
                                                text = badgeText,
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = incident.dateText,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(4.dp))
                                        Text(text = incident.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Очистить все?") },
            text = { Text("Это безвозвратно удалит все сохраненные фото, видео и координаты.") },
            confirmButton = {
                TextButton(onClick = {
                    clearAllIncidents()
                    incidents = loadIncidents()
                    showClearAllDialog = false
                    Toast.makeText(context, "Все записи удалены", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Удалить всё", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearAllDialog = false }) { Text("Отмена") } }
        )
    }

    deleteCandidate?.let { incident ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Удалить запись?") },
            text = { Text("Удалить запись от ${incident.dateText}?") },
            confirmButton = {
                TextButton(onClick = {
                    incident.folder.deleteRecursively()
                    incidents = loadIncidents()
                    deleteCandidate = null
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Отмена") } }
        )
    }

    if (selectedIncident != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedIncident = null },
            sheetState = sheetState,
            modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            MediaViewerBottomSheetContent(incident = selectedIncident!!)
        }
    }

    if (showSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            SettingsSheetContent(
                prefs = prefs,
                cameraOk = cameraOk,
                audioOk = audioOk,
                locationOk = locationOk,
                adminOk = adminOk,
                notifOk = notifOk,
                onRequestPermissions = { onRequestPermissions() },
                onOpenAdminSettings = { onOpenAdminSettings() }
            )
        }
    }
}

// Контент шторки просмотра
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewerBottomSheetContent(incident: IntruderIncident) {
    val context = LocalContext.current
    val downloadIcon = remember {
        ImageVector.Builder(
            name = "Download",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = PathParser().parsePathString("M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z").toNodes(),
            fill = SolidColor(Color.White)
        ).build()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(incident.dateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (incident.isVideo) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Попыток подбора: ${formatAttempts(incident.attemptsCount)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (incident.isVideo && incident.videoFile != null) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(incident.videoFile.absolutePath)
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            )

            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { shareFile(context, incident.videoFile, "video/mp4") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Поделиться")
                }

                Button(
                    onClick = {
                        val success = saveVideoToGallery(context, incident.videoFile)
                        val msg = if (success) "Видео сохранено в галерею" else "Ошибка сохранения"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(downloadIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("В галерею")
                }
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { incident.photoFiles.size })
            val currentFile = incident.photoFiles.getOrNull(pagerState.currentPage)

            if (incident.photoFiles.size > 1) {
                Text(
                    text = "Фото ${pagerState.currentPage + 1} из ${incident.photoFiles.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(360.dp)) { page ->
                val file = incident.photoFiles[page]
                val bitmap = remember(file.name) { loadSampledBitmap(file, sampleSize = 1) }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { currentFile?.let { shareFile(context, it, "image/jpeg") } },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Поделиться")
                }

                Button(
                    onClick = {
                        currentFile?.let { file ->
                            val success = savePhotoToGallery(context, file)
                            val msg = if (success) "Сохранено в галерею" else "Ошибка сохранения"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(downloadIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("В галерею")
                }
            }
        }
    }
}

@Composable
fun SettingsSheetContent(
    prefs: AppPreferences,
    cameraOk: Boolean,
    audioOk: Boolean,
    locationOk: Boolean,
    adminOk: Boolean,
    notifOk: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAdminSettings: () -> Unit
) {
    val context = LocalContext.current
    var isTelegramEnabled by remember { mutableStateOf(prefs.isTelegramEnabled) }
    var tokenText by remember { mutableStateOf(prefs.telegramToken) }
    var chatIdText by remember { mutableStateOf(prefs.telegramChatId) }

    var captureMode by remember { mutableStateOf(prefs.captureMode) }
    var videoDuration by remember { mutableStateOf(prefs.videoDuration) }
    var isGeoEnabled by remember { mutableStateOf(prefs.isGeoEnabled) }
    var isWideEnabled by remember { mutableStateOf(prefs.isWideLensEnabled) }
    var photoCount by remember { mutableStateOf(prefs.photoCount) }

    var showTokenHelp by remember { mutableStateOf(false) }
    var showChatIdHelp by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 32.dp)
    ) {
        Text("Настройки защиты", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Text("Системные доступы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                StatusRow("Камера", cameraOk)
                StatusRow("Микрофон (звук в видео)", audioOk)
                StatusRow("Геолокация", locationOk)
                StatusRow("Уведомления", notifOk)
                StatusRow("Администратор устройства", adminOk)

                if (!cameraOk || !audioOk || !locationOk || !notifOk) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text("Выдать недостающие разрешения")
                    }
                }
                if (!adminOk) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenAdminSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Включить администратора устройства")
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Text("Параметры съемки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Режим фиксации", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(4.dp)
                ) {
                    val isPhoto = captureMode == "PHOTO"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isPhoto) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable {
                                captureMode = "PHOTO"
                                prefs.captureMode = "PHOTO"
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "📸 Фото",
                            fontWeight = if (isPhoto) FontWeight.Bold else FontWeight.Normal,
                            color = if (isPhoto) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isPhoto) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable {
                                captureMode = "VIDEO"
                                prefs.captureMode = "VIDEO"
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "📹 Видео",
                            fontWeight = if (!isPhoto) FontWeight.Bold else FontWeight.Normal,
                            color = if (!isPhoto) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Широкий угол", fontWeight = FontWeight.Medium)
                        Text("Максимальный физический охват датчика", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isWideEnabled,
                        onCheckedChange = {
                            isWideEnabled = it
                            prefs.isWideLensEnabled = it
                        }
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                if (captureMode == "VIDEO") {
                    Text("Длительность видео: $videoDuration сек", fontWeight = FontWeight.Medium)
                    Text(
                        text = "Таймер продлевается на $videoDuration сек при каждой новой попытке ввода",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = videoDuration.toFloat(),
                        onValueChange = {
                            videoDuration = it.toInt()
                            prefs.videoDuration = it.toInt()
                        },
                        valueRange = 5f..10f,
                        steps = 4
                    )
                } else {
                    Text("Количество фото при взломе: $photoCount", fontWeight = FontWeight.Medium)
                    Text(
                        text = "Задержка 1 сек между кадрами для разной мимики и ракурсов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = photoCount.toFloat(),
                        onValueChange = {
                            photoCount = it.toInt()
                            prefs.photoCount = it.toInt()
                        },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Определять геолокацию", fontWeight = FontWeight.Medium)
                        Text("Непрерывный поиск спутников во время съемки", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isGeoEnabled,
                        onCheckedChange = {
                            isGeoEnabled = it
                            prefs.isGeoEnabled = it
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Text("Telegram оповещения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Включить алерты в Telegram", fontWeight = FontWeight.Medium)
                    Switch(
                        checked = isTelegramEnabled,
                        onCheckedChange = {
                            isTelegramEnabled = it
                            prefs.isTelegramEnabled = it
                        }
                    )
                }

                if (isTelegramEnabled) {
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = {
                            tokenText = it
                            prefs.telegramToken = it
                        },
                        label = { Text("Токен бота") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showTokenHelp = true }) {
                                Icon(Icons.Default.Info, contentDescription = "Где взять токен?")
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = chatIdText,
                        onValueChange = {
                            chatIdText = it
                            prefs.telegramChatId = it
                        },
                        label = { Text("Ваш Chat ID") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showChatIdHelp = true }) {
                                Icon(Icons.Default.Info, contentDescription = "Где взять Chat ID?")
                            }
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Включите VPN, и добавьте это приложение в его пул (Split Tunneling), иначе алерт может не дойти.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    var isTestSent by remember { mutableStateOf(false) }

                    Button(
                        onClick = {
                            isTestSent = true
                            Thread {
                                val msgId = TelegramSender.sendTextMessage(
                                    token = tokenText,
                                    chatId = chatIdText,
                                    textHtml = "🔔 Тестовое оповещение от nedestroyme."
                                )
                                Handler(Looper.getMainLooper()).post {
                                    val msg = if (msgId != null) "Сообщение отправлено!" else "Ошибка отправки (проверьте VPN/token/id)"
                                    Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
                                }
                                Handler(Looper.getMainLooper()).postDelayed({
                                    isTestSent = false
                                }, 2000)
                            }.start()
                        },
                        enabled = !isTestSent,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isTestSent) {
                            Text("✅ Отправлено")
                        } else {
                            Text("Отправить тестовое сообщение")
                        }
                    }
                }
            }
        }
    }

    if (showTokenHelp) {
        AlertDialog(
            onDismissRequest = { showTokenHelp = false },
            title = { Text("Где взять токен бота?") },
            text = { Text("1. В Telegram найдите @BotFather.\n2. Отправьте /newbot и задайте имя.\n3. Скопируйте HTTP API Token и вставьте сюда.") },
            confirmButton = { TextButton(onClick = { showTokenHelp = false }) { Text("Понятно") } }
        )
    }

    if (showChatIdHelp) {
        AlertDialog(
            onDismissRequest = { showChatIdHelp = false },
            title = { Text("Где взять Chat ID?") },
            text = { Text("1. В Telegram перейдите к @userinfobot.\n2. Нажмите Start — бот пришлет ваш цифровой ID.\n3. Вставьте его в поле Chat ID.\n4. Не забудьте нажать Start и в вашем личном боте!") },
            confirmButton = { TextButton(onClick = { showChatIdHelp = false }) { Text("Понятно") } }
        )
    }
}

@Composable
fun StatusRow(title: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (isOk) "✓ Активно" else "⚠ Отсутствует",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

fun savePhotoToGallery(context: Context, file: File): Boolean {
    return try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "INTRUDER_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/nedestroyme")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (_: Exception) { false }
}

fun saveVideoToGallery(context: Context, file: File): Boolean {
    return try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "INTRUDER_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/nedestroyme")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (_: Exception) { false }
}

fun shareFile(context: Context, file: File, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "nedestroyme", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Поделиться"))
    } catch (e: Exception) {
        Toast.makeText(context, "Ошибка: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

fun loadSampledBitmap(file: File, sampleSize: Int): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        val rotated = if (!matrix.isIdentity) {
            val r = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (r != bitmap) bitmap.recycle()
            r
        } else bitmap

        rotated.asImageBitmap()
    } catch (_: Throwable) { null }
}

fun loadVideoThumbnail(file: File): androidx.compose.ui.graphics.ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val frame = retriever.getFrameAtTime(200_000)
        frame?.asImageBitmap()
    } catch (_: Throwable) {
        null
    } finally {
        try { retriever.release() } catch (_: Throwable) {}
    }
}