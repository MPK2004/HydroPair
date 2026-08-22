package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import io.ktor.client.statement.readBytes
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class GitHubAsset(
    val name: String,
    val browser_download_url: String
)

@Serializable
data class GitHubRelease(
    val tag_name: String,
    val body: String = "",
    val assets: List<GitHubAsset> = emptyList()
)

// Custom Water App Theme Colors
private val WaterPrimary = Color(0xFF0284C7)      // Ocean Blue
private val WaterSecondary = Color(0xFF06B6D4)    // Cyan Accent
private val WaterBackground = Color(0xFF0F172A)   // Deep Navy Slate
private val WaterSurface = Color(0xFF1E293B)      // Card Surface
private val WaterSurfaceVariant = Color(0xFF334155) // Card Variant
private val WaterOnSurface = Color(0xFFF8FAFC)    // White Text
private val WaterAccentMint = Color(0xFF10B981)   // Mint Green Success

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WaterAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HydroPairApp()
                }
            }
        }
    }
}

@Composable
fun WaterAppTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = WaterPrimary,
        secondary = WaterSecondary,
        tertiary = WaterAccentMint,
        background = WaterBackground,
        surface = WaterSurface,
        onSurface = WaterOnSurface,
        surfaceVariant = WaterSurfaceVariant
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            "water_reminders",
            "Partner Hydration Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Pushes notifications when partner sends a water reminder"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}

fun triggerNotification(context: Context, title: String, body: String) {
    createNotificationChannel(context)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = NotificationCompat.Builder(context, "water_reminders")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    manager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HydroPairApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            triggerNotification(context, "Notifications Active! 💧", "Ready to receive partner reminders.")
        }
    }

    LaunchedEffect(Unit) {
        createNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val json = remember {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }

    val httpClient = remember {
        HttpClient(Android) {
            expectSuccess = false
        }
    }

    // Shared Preferences persistence
    val prefs = remember { context.getSharedPreferences("hydropair_prefs", Context.MODE_PRIVATE) }
    var pairCode by remember { mutableStateOf(prefs.getString("pair_code", "AQUA-101") ?: "AQUA-101") }
    var userName by remember { mutableStateOf(prefs.getString("user_name", "User") ?: "User") }
    var dailyGoalMl by remember { mutableIntStateOf(prefs.getInt("daily_goal_ml", 2000)) }

    // App state
    var reminders by remember { mutableStateOf<List<ReminderRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf("Local Mode") }
    var selectedTab by remember { mutableIntStateOf(0) }

    // In-App Auto Updater State
    val currentVersionName = "v1.0.0"
    var updateAvailableRelease by remember { mutableStateOf<GitHubRelease?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf("") }
    var gitHubRepoOwner by remember { mutableStateOf(prefs.getString("github_repo_owner", "") ?: "") }
    var gitHubRepoName by remember { mutableStateOf(prefs.getString("github_repo_name", "") ?: "") }

    fun checkGitHubForUpdates(owner: String = gitHubRepoOwner, repo: String = gitHubRepoName, showToastIfLatest: Boolean = false) {
        if (owner.isBlank() || repo.isBlank()) {
            if (showToastIfLatest) Toast.makeText(context, "Please set GitHub Owner and Repo in Profile settings first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            try {
                val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
                val response = withContext(Dispatchers.IO) {
                    httpClient.get(url) {
                        header("User-Agent", "HydroPair-App")
                    }
                }
                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val release = json.decodeFromString<GitHubRelease>(body)
                    val apkAsset = release.assets.find { it.name.endsWith(".apk") }
                    if (release.tag_name != currentVersionName && apkAsset != null) {
                        updateAvailableRelease = release
                    } else if (showToastIfLatest) {
                        Toast.makeText(context, "You are on the latest version ($currentVersionName)!", Toast.LENGTH_SHORT).show()
                    }
                } else if (showToastIfLatest) {
                    Toast.makeText(context, "No GitHub releases found for $owner/$repo.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (showToastIfLatest) {
                    Toast.makeText(context, "Could not check for updates: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun downloadAndInstallUpdate(release: GitHubRelease) {
        val apkAsset = release.assets.find { it.name.endsWith(".apk") } ?: return
        isDownloadingUpdate = true
        updateStatusText = "Downloading update ${release.tag_name}..."

        scope.launch {
            try {
                val apkFile = File(context.getExternalFilesDir(null), "HydroPair-Update.apk")
                if (apkFile.exists()) apkFile.delete()

                val bytes = withContext(Dispatchers.IO) {
                    val response = httpClient.get(apkAsset.browser_download_url)
                    response.readBytes()
                }

                withContext(Dispatchers.IO) {
                    apkFile.writeBytes(bytes)
                }

                updateStatusText = "Opening package installer..."
                isDownloadingUpdate = false

                val apkUri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(installIntent)

            } catch (e: Exception) {
                isDownloadingUpdate = false
                updateStatusText = "Update error: ${e.message}"
                Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun savePrefs() {
        prefs.edit()
            .putString("pair_code", pairCode)
            .putString("user_name", userName)
            .putInt("daily_goal_ml", dailyGoalMl)
            .putString("github_repo_owner", gitHubRepoOwner)
            .putString("github_repo_name", gitHubRepoName)
            .apply()
        WaterGlassWidgetProvider.updateAllWidgets(context)
        PartnerAvatarWidgetProvider.updateAllWidgets(context)
    }

    fun saveRemindersCache(list: List<ReminderRow>) {
        reminders = list
        try {
            val jsonString = json.encodeToString(list)
            prefs.edit().putString("cached_reminders", jsonString).apply()
        } catch (e: Exception) {
            // Ignore cache save errors
        }
        WaterGlassWidgetProvider.updateAllWidgets(context)
        PartnerAvatarWidgetProvider.updateAllWidgets(context)
    }

    // Delete single activity log (Local + Supabase)
    fun deleteReminder(reminderId: Int) {
        val updatedList = reminders.filterNot { it.id == reminderId }
        saveRemindersCache(updatedList)

        scope.launch {
            snackbarHostState.showSnackbar("Activity log deleted 🗑️")
            try {
                val url = "https://mwcwpkdntxkxgxhwtqin.supabase.co/rest/v1/reminders?id=eq.$reminderId"
                withContext(Dispatchers.IO) {
                    httpClient.delete(url) {
                        header(HttpHeaders.Authorization, "Bearer sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        header("apikey", "sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                    }
                }
            } catch (e: Exception) {
                // Kept local
            }
        }
    }

    // Purge/Reset all activity logs & water intake for the day (Local + Supabase)
    fun clearAllDailyData() {
        saveRemindersCache(emptyList())
        scope.launch {
            snackbarHostState.showSnackbar("Daily logs & water intake reset to 0 🔄")
            try {
                val url = "https://mwcwpkdntxkxgxhwtqin.supabase.co/rest/v1/reminders?pair_code=eq.$pairCode"
                withContext(Dispatchers.IO) {
                    httpClient.delete(url) {
                        header(HttpHeaders.Authorization, "Bearer sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        header("apikey", "sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                    }
                }
            } catch (e: Exception) {
                // Kept local
            }
        }
    }

    // Automatic midnight auto-reset check
    fun checkAndResetDailyData() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastReset = prefs.getString("last_reset_date", "")

        if (!lastReset.isNullOrBlank() && lastReset != todayStr) {
            clearAllDailyData()
        }
        prefs.edit().putString("last_reset_date", todayStr).apply()
    }

    // Load local cache on start, check daily midnight reset & check GitHub for OTA updates
    LaunchedEffect(Unit) {
        val cachedJson = prefs.getString("cached_reminders", "[]") ?: "[]"
        try {
            val cachedList = json.decodeFromString<List<ReminderRow>>(cachedJson)
            reminders = cachedList
        } catch (e: Exception) {
            reminders = emptyList()
        }
        checkAndResetDailyData()
        checkGitHubForUpdates()
    }

    // Fetch from Supabase with fallback to local
    fun syncWithCloud() {
        if (pairCode.isBlank()) return
        scope.launch {
            isLoading = true
            try {
                val url = "https://mwcwpkdntxkxgxhwtqin.supabase.co/rest/v1/reminders"
                val response = withContext(Dispatchers.IO) {
                    httpClient.get(url) {
                        header(HttpHeaders.Authorization, "Bearer sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        header("apikey", "sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        url { parameters.append("pair_code", "eq.$pairCode") }
                    }
                }

                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val remoteList = json.decodeFromString<List<ReminderRow>>(body)

                    // Trigger notification for new partner reminders
                    val newPartnerReminders = remoteList.filter { r ->
                        !r.sender.equals(userName.trim(), ignoreCase = true) && reminders.none { existing -> existing.id == r.id }
                    }
                    newPartnerReminders.forEach { r ->
                        triggerNotification(
                            context,
                            "💧 Water Reminder from ${r.sender}!",
                            "${r.reminder_text} (${r.sender_amount_ml} ml)"
                        )
                    }

                    // Store authoritative remote list from Supabase cleanly without keeping duplicate temp local IDs
                    saveRemindersCache(remoteList.sortedByDescending { it.id })
                    syncStatus = "Synced with Cloud"
                } else {
                    syncStatus = "Cloud Sync Unavailable (${response.status.value})"
                }
            } catch (e: Exception) {
                syncStatus = "Offline / Local Mode"
            } finally {
                isLoading = false
            }
        }
    }

    // Real-time automatic background sync loop (polls every 10 seconds automatically)
    LaunchedEffect(pairCode) {
        savePrefs()
        while (isActive) {
            syncWithCloud()
            delay(10000)
        }
    }

    // Quick add personal water log (Counts for THIS user's daily intake)
    fun logIntake(amountMl: Int, text: String = "Quick Log") {
        val tempId = (System.currentTimeMillis() % 10000000).toInt()
        val newRow = ReminderRow(
            id = tempId,
            pair_code = pairCode,
            sender = userName,
            reminder_text = text,
            sender_amount_ml = amountMl,
            reply_amount_ml = null,
            created_at = SimpleDateFormat("HH:mm, MMM dd", Locale.getDefault()).format(Date())
        )
        val updated = listOf(newRow) + reminders.filterNot { it.id == tempId }
        saveRemindersCache(updated)

        scope.launch {
            snackbarHostState.showSnackbar("Logged $amountMl ml water! 💧")
            try {
                val url = "https://mwcwpkdntxkxgxhwtqin.supabase.co/rest/v1/reminders"
                val payload = """{"pair_code":"$pairCode","sender":"$userName","reminder_text":"$text","sender_amount_ml":$amountMl,"reply_amount_ml":null}"""
                withContext(Dispatchers.IO) {
                    httpClient.post(url) {
                        header(HttpHeaders.Authorization, "Bearer sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        header("apikey", "sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        header("Prefer", "return=representation")
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                }
                syncWithCloud()
            } catch (e: Exception) {
                // Kept in local state
            }
        }
    }

    // Send reminder to partner (Does NOT count towards sender's intake, until partner replies)
    fun sendPartnerReminder(amountMl: Int, text: String) {
        val reminderMessage = "Reminder: $text"
        val tempId = (System.currentTimeMillis() % 10000000).toInt()
        val newRow = ReminderRow(
            id = tempId,
            pair_code = pairCode,
            sender = userName,
            reminder_text = reminderMessage,
            sender_amount_ml = amountMl,
            reply_amount_ml = null,
            created_at = SimpleDateFormat("HH:mm, MMM dd", Locale.getDefault()).format(Date())
        )
        val updated = listOf(newRow) + reminders.filterNot { it.id == tempId }
        saveRemindersCache(updated)

        scope.launch {
            snackbarHostState.showSnackbar("Reminder sent to partner! 🔔")
            try {
                val url = "https://mwcwpkdntxkxgxhwtqin.supabase.co/rest/v1/reminders"
                val payload = """{"pair_code":"$pairCode","sender":"$userName","reminder_text":"$reminderMessage","sender_amount_ml":$amountMl,"reply_amount_ml":null}"""
                withContext(Dispatchers.IO) {
                    httpClient.post(url) {
                        header(HttpHeaders.Authorization, "Bearer sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        header("apikey", "sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        header("Prefer", "return=representation")
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                }
                syncWithCloud()
            } catch (e: Exception) {
                // Kept in local state
            }
        }
    }

    // Reply to reminder
    fun replyToReminder(reminderId: Int, replyMl: Int) {
        val updatedList = reminders.map { r ->
            if (r.id == reminderId) r.copy(reply_amount_ml = replyMl) else r
        }
        saveRemindersCache(updatedList)

        scope.launch {
            snackbarHostState.showSnackbar("Replied with $replyMl ml! 🥤")
            try {
                val url = "https://mwcwpkdntxkxgxhwtqin.supabase.co/rest/v1/reminders?id=eq.$reminderId"
                val payload = """{"reply_amount_ml":$replyMl}"""
                withContext(Dispatchers.IO) {
                    httpClient.patch(url) {
                        header(HttpHeaders.Authorization, "Bearer sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        header("apikey", "sb_publishable_x9mi15kclW1siVg-oOuL4A_9uGVOu52")
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                }
                syncWithCloud()
            } catch (e: Exception) {
                // Kept local
            }
        }
    }

    // Calculate total consumed today FOR THIS USER ONLY
    val todayConsumedMl = remember(reminders, userName) {
        val cleanUser = userName.trim()
        reminders.sumOf { r ->
            var sum = 0
            val isFromMe = r.sender.equals(cleanUser, ignoreCase = true)

            if (isFromMe) {
                // Count direct personal logs where I logged drinking water for myself
                if (!r.reminder_text.startsWith("Reminder:", ignoreCase = true)) {
                    sum += r.sender_amount_ml
                }
            } else {
                // Partner sent me a reminder and I drank water & replied to it!
                if (r.reply_amount_ml != null) {
                    sum += r.reply_amount_ml
                }
            }
            sum
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = WaterSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "HydroPair",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                "Pair: $pairCode • $syncStatus",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { syncWithCloud() }) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = WaterSecondary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = WaterSecondary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WaterSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = WaterSurface,
                contentColor = WaterOnSurface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.WaterDrop, contentDescription = "Dashboard") },
                    label = { Text("Tracker") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Forum, contentDescription = "Pair Feed") },
                    label = { Text("Activity") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Reminder") },
                    label = { Text("Remind") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Profile") }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(WaterBackground)
        ) {
            when (selectedTab) {
                0 -> DashboardTab(
                    todayConsumedMl = todayConsumedMl,
                    dailyGoalMl = dailyGoalMl,
                    onLogWater = { amount -> logIntake(amount, "Quick Log") }
                )
                1 -> ActivityTab(
                    reminders = reminders,
                    userName = userName,
                    onReply = { id, ml -> replyToReminder(id, ml) },
                    onDelete = { id -> deleteReminder(id) },
                    onRefresh = { syncWithCloud() }
                )
                2 -> SendReminderTab(
                    pairCode = pairCode,
                    userName = userName,
                    onSendReminder = { amount, text -> sendPartnerReminder(amount, text) }
                )
                3 -> ProfileTab(
                    pairCode = pairCode,
                    userName = userName,
                    dailyGoalMl = dailyGoalMl,
                    gitHubRepoOwner = gitHubRepoOwner,
                    gitHubRepoName = gitHubRepoName,
                    currentVersion = currentVersionName,
                    onSave = { newPair, newName, newGoal, newOwner, newRepo ->
                        pairCode = newPair
                        userName = newName
                        dailyGoalMl = newGoal
                        gitHubRepoOwner = newOwner
                        gitHubRepoName = newRepo
                        savePrefs()
                        Toast.makeText(context, "Profile updated!", Toast.LENGTH_SHORT).show()
                    },
                    onCheckUpdates = { owner, repo ->
                        checkGitHubForUpdates(owner, repo, showToastIfLatest = true)
                    },
                    onResetAllData = { clearAllDailyData() }
                )
            }
        }
    }

    if (updateAvailableRelease != null) {
        val release = updateAvailableRelease!!
        AlertDialog(
            onDismissRequest = { if (!isDownloadingUpdate) updateAvailableRelease = null },
            title = { Text("🚀 Update Available (${release.tag_name})") },
            text = {
                Column {
                    Text("A new version of HydroPair is ready!", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(release.body.ifBlank { "Performance improvements and bug fixes." }, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Your existing logs, settings & pair data will be preserved.", style = MaterialTheme.typography.labelSmall, color = WaterSecondary)
                    if (isDownloadingUpdate) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(updateStatusText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDownloadingUpdate,
                    onClick = { downloadAndInstallUpdate(release) }
                ) {
                    Text(if (isDownloadingUpdate) "Downloading..." else "Update & Install Now")
                }
            },
            dismissButton = {
                if (!isDownloadingUpdate) {
                    TextButton(onClick = { updateAvailableRelease = null }) {
                        Text("Later")
                    }
                }
            }
        )
    }
}

@Composable
fun DashboardTab(
    todayConsumedMl: Int,
    dailyGoalMl: Int,
    onLogWater: (Int) -> Unit
) {
    val progress = (todayConsumedMl.toFloat() / dailyGoalMl.toFloat()).coerceIn(0f, 1f)
    var showCustomDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            // Main Hero Progress Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = WaterSurface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(WaterSurface, WaterSurfaceVariant)
                            )
                        )
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Today's Hydration",
                            style = MaterialTheme.typography.titleMedium,
                            color = WaterSecondary
                        )
                        Spacer(Modifier.height(16.dp))

                        // Circular / Progress Indicator Box
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.size(160.dp),
                                color = WaterSecondary,
                                strokeWidth = 12.dp,
                                trackColor = WaterBackground
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "$todayConsumedMl",
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 36.sp
                                    ),
                                    color = WaterOnSurface
                                )
                                Text(
                                    "/ $dailyGoalMl ml",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WaterOnSurface.copy(alpha = 0.7f)
                                )
                                Text(
                                    "${(progress * 100).toInt()}% Goal",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = WaterAccentMint,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        if (todayConsumedMl >= dailyGoalMl) {
                            Surface(
                                color = WaterAccentMint.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = WaterAccentMint, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Daily hydration goal reached! 🎉", style = MaterialTheme.typography.bodySmall, color = WaterAccentMint)
                                }
                            }
                        } else {
                            Text(
                                "${dailyGoalMl - todayConsumedMl} ml left to reach daily goal",
                                style = MaterialTheme.typography.bodySmall,
                                color = WaterOnSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Quick Add Water Section
            Text(
                "Quick Water Log",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = WaterOnSurface,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickLogButton("200 ml", "Glass", Icons.Default.LocalDrink, modifier = Modifier.weight(1f)) {
                    onLogWater(200)
                }
                QuickLogButton("250 ml", "Cup", Icons.Default.LocalDrink, modifier = Modifier.weight(1f)) {
                    onLogWater(250)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickLogButton("500 ml", "Bottle", Icons.Default.WaterDrop, modifier = Modifier.weight(1f)) {
                    onLogWater(500)
                }
                QuickLogButton("750 ml", "Flask", Icons.Default.WaterDrop, modifier = Modifier.weight(1f)) {
                    onLogWater(750)
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showCustomDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WaterSecondary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Log Custom Amount")
            }
        }
    }

    if (showCustomDialog) {
        var customAmountText by remember { mutableStateOf("300") }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Log Custom Water Intake") },
            text = {
                OutlinedTextField(
                    value = customAmountText,
                    onValueChange = { customAmountText = it },
                    label = { Text("Amount (ml)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ml = customAmountText.toIntOrNull() ?: 0
                        if (ml > 0) {
                            onLogWater(ml)
                        }
                        showCustomDialog = false
                    }
                ) { Text("Log") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun QuickLogButton(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = WaterSurface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(WaterPrimary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = WaterSecondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = WaterOnSurface)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = WaterOnSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
fun ActivityTab(
    reminders: List<ReminderRow>,
    userName: String,
    onReply: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    var replyingToId by remember { mutableStateOf<Int?>(null) }
    var replyMlText by remember { mutableStateOf("250") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Pair Activity Feed",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = WaterOnSurface
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = WaterSecondary)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (reminders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.WaterDrop,
                        contentDescription = null,
                        tint = WaterSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No hydration activity yet!", color = WaterOnSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    Text("Send a reminder to your partner to get started.", style = MaterialTheme.typography.bodySmall, color = WaterOnSurface.copy(alpha = 0.5f))
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(reminders) { r ->
                    val isFromMe = r.sender.equals(userName, ignoreCase = true)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = WaterSurface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = if (isFromMe) WaterPrimary.copy(alpha = 0.2f) else WaterSecondary.copy(alpha = 0.2f),
                                        shape = CircleShape,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isFromMe) Icons.Default.Person else Icons.Default.Favorite,
                                                contentDescription = null,
                                                tint = if (isFromMe) WaterPrimary else WaterSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (isFromMe) "You" else r.sender,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = WaterOnSurface
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (r.created_at.isNotBlank()) {
                                        Text(
                                            r.created_at,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = WaterOnSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { onDelete(r.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Log",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                r.reminder_text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = WaterOnSurface
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = WaterBackground,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.WaterDrop, contentDescription = null, tint = WaterSecondary, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("${r.sender_amount_ml} ml", style = MaterialTheme.typography.labelMedium, color = WaterSecondary)
                                    }
                                }

                                if (r.reply_amount_ml != null) {
                                    Surface(
                                        color = WaterAccentMint.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = WaterAccentMint, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Replied: ${r.reply_amount_ml} ml", style = MaterialTheme.typography.labelMedium, color = WaterAccentMint)
                                        }
                                    }
                                } else if (!isFromMe) {
                                    Button(
                                        onClick = { replyingToId = r.id },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(28.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = WaterSecondary)
                                    ) {
                                        Text("Drink & Reply", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (replyingToId != null) {
        AlertDialog(
            onDismissRequest = { replyingToId = null },
            title = { Text("Reply to Hydration Reminder") },
            text = {
                Column {
                    Text("Enter how much water you drank in response:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = replyMlText,
                        onValueChange = { replyMlText = it },
                        label = { Text("Your Amount (ml)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ml = replyMlText.toIntOrNull() ?: 250
                        replyingToId?.let { id -> onReply(id, ml) }
                        replyingToId = null
                    }
                ) { Text("Send Reply") }
            },
            dismissButton = {
                TextButton(onClick = { replyingToId = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SendReminderTab(
    pairCode: String,
    userName: String,
    onSendReminder: (Int, String) -> Unit
) {
    var amountText by remember { mutableStateOf("250") }
    var customMessage by remember { mutableStateOf("") }

    val presetMessages = listOf(
        "Time for a water break! 💧",
        "Stay hydrated, partner! 🌊",
        "Don't forget to drink water! 🥤",
        "Hit your daily goal with me! 🚀"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Text(
                "Send Hydration Reminder",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = WaterOnSurface
            )
            Text(
                "Remind your paired partner ($pairCode) to drink water!",
                style = MaterialTheme.typography.bodySmall,
                color = WaterOnSurface.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(20.dp))

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = WaterSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("1. Select Water Amount", style = MaterialTheme.typography.titleMedium, color = WaterSecondary)
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (ml)") },
                        leadingIcon = { Icon(Icons.Default.WaterDrop, contentDescription = null, tint = WaterSecondary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))

                    Text("2. Reminder Message", style = MaterialTheme.typography.titleMedium, color = WaterSecondary)
                    Spacer(Modifier.height(10.dp))

                    presetMessages.forEach { msg ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { customMessage = msg },
                            shape = RoundedCornerShape(10.dp),
                            color = if (customMessage == msg) WaterPrimary.copy(alpha = 0.3f) else WaterSurfaceVariant
                        ) {
                            Text(
                                msg,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = WaterOnSurface
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customMessage,
                        onValueChange = { customMessage = it },
                        label = { Text("Or Type Custom Message") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = {
                            val ml = amountText.toIntOrNull() ?: 250
                            val msg = customMessage.ifBlank { "Drink water! 💧" }
                            onSendReminder(ml, msg)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WaterPrimary)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send to Partner & Log", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileTab(
    pairCode: String,
    userName: String,
    dailyGoalMl: Int,
    gitHubRepoOwner: String,
    gitHubRepoName: String,
    currentVersion: String,
    onSave: (String, String, Int, String, String) -> Unit,
    onCheckUpdates: (String, String) -> Unit,
    onResetAllData: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var editPairCode by remember { mutableStateOf(pairCode) }
    var editUserName by remember { mutableStateOf(userName) }
    var editGoalText by remember { mutableStateOf(dailyGoalMl.toString()) }
    var editRepoOwner by remember { mutableStateOf(gitHubRepoOwner) }
    var editRepoName by remember { mutableStateOf(gitHubRepoName) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Text(
                "Profile & Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = WaterOnSurface
            )
            Spacer(Modifier.height(16.dp))

            // How Pairing Works Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = WaterSurfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = WaterSecondary)
                        Spacer(Modifier.width(8.dp))
                        Text("How Device Pairing Works", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = WaterOnSurface)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("1️⃣  Choose or generate a shared Pair Code (e.g. AQUA-7890).", style = MaterialTheme.typography.bodySmall, color = WaterOnSurface.copy(alpha = 0.8f))
                    Spacer(Modifier.height(4.dp))
                    Text("2️⃣  Share the exact Pair Code with your partner.", style = MaterialTheme.typography.bodySmall, color = WaterOnSurface.copy(alpha = 0.8f))
                    Spacer(Modifier.height(4.dp))
                    Text("3️⃣  Have your partner enter the same Pair Code in their app's Profile screen.", style = MaterialTheme.typography.bodySmall, color = WaterOnSurface.copy(alpha = 0.8f))
                    Spacer(Modifier.height(6.dp))
                    Text("🎉 You will now share live hydration reminders & updates!", style = MaterialTheme.typography.labelSmall, color = WaterAccentMint, fontWeight = FontWeight.Bold)
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = WaterSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = editUserName,
                        onValueChange = { editUserName = it },
                        label = { Text("Your Display Name") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = WaterSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = editPairCode,
                        onValueChange = { editPairCode = it },
                        label = { Text("Shared Pair Code") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = WaterSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val randomCode = "AQUA-" + (1000..9999).random()
                                editPairCode = randomCode
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Generate Code", style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(editPairCode))
                                Toast.makeText(context, "Pair Code copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy Code", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = editGoalText,
                        onValueChange = { editGoalText = it },
                        label = { Text("Daily Water Target Goal (ml)") },
                        leadingIcon = { Icon(Icons.Default.WaterDrop, contentDescription = null, tint = WaterSecondary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = WaterSurfaceVariant)
                    Spacer(Modifier.height(16.dp))

                    Text("🚀 GitHub In-App Auto-Updates", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = WaterSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text("App Version: $currentVersion", style = MaterialTheme.typography.bodySmall, color = WaterOnSurface.copy(alpha = 0.8f))
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editRepoOwner,
                            onValueChange = { editRepoOwner = it },
                            label = { Text("GitHub User / Org") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editRepoName,
                            onValueChange = { editRepoName = it },
                            label = { Text("Repository Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            val goal = editGoalText.toIntOrNull() ?: 2000
                            onSave(editPairCode, editUserName, goal, editRepoOwner, editRepoName)
                            onCheckUpdates(editRepoOwner, editRepoName)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp), tint = WaterPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Check for In-App Updates", style = MaterialTheme.typography.labelMedium, color = WaterPrimary)
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = {
                            val goal = editGoalText.toIntOrNull() ?: 2000
                            onSave(editPairCode, editUserName, goal, editRepoOwner, editRepoName)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WaterSecondary)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Profile Settings")
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            triggerNotification(
                                context,
                                "💧 Test Water Reminder!",
                                "This is how notifications will appear when your partner sends a reminder."
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Test Notification", style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onResetAllData,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Reset Today's Logs & Intake to 0", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
