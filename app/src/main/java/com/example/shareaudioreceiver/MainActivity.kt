package com.example.shareaudioreceiver

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONObject

class AudioReceiverService : Service() {
    private val binder = LocalBinder()
    private var webSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var audioEnabled = false

    var onStatusChange: ((String) -> Unit)? = null
    var onRoomUpdate: ((List<String>) -> Unit)? = null
    var onShowEnableButton: ((Boolean) -> Unit)? = null
    var onConnectionChange: ((Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioReceiverService = this@AudioReceiverService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Receiver",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Receiving audio stream"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun startReceiving() {
        val notification = createNotification("Waiting for audio stream...")
        startForeground(NOTIFICATION_ID, notification)

        connectToRoom()
    }

    fun stopReceiving() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onStatusChange?.invoke("Stopped receiving")
        onConnectionChange?.invoke(false)
    }

    fun enableAudio() {
        audioEnabled = true
        onShowEnableButton?.invoke(false)
        onStatusChange?.invoke("Audio enabled! 🔊 Waiting for stream...")
        updateNotification("Audio enabled - Waiting for stream...")
        Log.d(TAG, "Audio enabled by user")
    }

    private fun connectToRoom() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("wss://socketbe.onrender.com")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                onConnectionChange?.invoke(true)

                val joinMsg = JSONObject().apply {
                    put("type", "join")
                    put("roomId", "r1")
                    put("userId", "Receiver")
                }
                webSocket.send(joinMsg.toString())

                onStatusChange?.invoke("Connected - Waiting for audio stream...")
                updateNotification("Connected to room")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text: $text")
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                Log.d(TAG, "Received audio: ${bytes.size()} bytes")
                handleAudioData(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                onStatusChange?.invoke("Connection error: ${t.message}")
                onConnectionChange?.invoke(false)
                updateNotification("Connection error")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $reason")
                onStatusChange?.invoke("Connection closing...")
                onConnectionChange?.invoke(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                onStatusChange?.invoke("Disconnected")
                onConnectionChange?.invoke(false)
                updateNotification("Disconnected")
            }
        })
    }

    private fun handleTextMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.getString("type")) {
                "roomUpdate" -> {
                    val usersArray = json.getJSONArray("users")
                    val users = mutableListOf<String>()
                    for (i in 0 until usersArray.length()) {
                        users.add(usersArray.getString(i))
                    }
                    onRoomUpdate?.invoke(users)
                }
                "offer" -> {
                    Log.d(TAG, "Received WebRTC offer (not supported in this receiver)")
                    onStatusChange?.invoke("⚠️ WebRTC stream detected - use PCM sender instead")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    private fun handleAudioData(audioData: ByteArray) {
        if (!audioEnabled) {
            onShowEnableButton?.invoke(true)
            onStatusChange?.invoke("🔴 Audio received! Click button to enable playback")
            updateNotification("Audio received - Enable playback")
            return
        }

        if (audioTrack == null) {
            initAudioTrack()
        }

        try {
            audioTrack?.write(audioData, 0, audioData.size)

            if (!isPlaying) {
                audioTrack?.play()
                isPlaying = true
                onStatusChange?.invoke("🎵 Playing audio stream...")
                updateNotification("Playing audio stream")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio", e)
            onStatusChange?.invoke("Error playing audio: ${e.message}")
        }
    }

    private fun initAudioTrack() {
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.d(TAG, "AudioTrack initialized: bufferSize=$bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioTrack", e)
            onStatusChange?.invoke("Error initializing audio: ${e.message}")
        }
    }

    private fun cleanup() {
        // Stop audio
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        isPlaying = false
        audioEnabled = false

        // Close websocket
        webSocket?.close(1000, "User quit room")
        webSocket = null

        Log.d(TAG, "Cleaned up resources")
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Receiver")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    companion object {
        private const val TAG = "AudioReceiverService"
        private const val CHANNEL_ID = "audio_receiver_channel"
        private const val NOTIFICATION_ID = 2
    }
}

class ReceiverActivity : ComponentActivity() {
    private var audioReceiverService: AudioReceiverService? = null
    private var isBound = false

    private val statusMessage = mutableStateOf("Ready to receive")
    private val roomUsers = mutableStateOf(listOf<String>())
    private val showEnableButton = mutableStateOf(false)
    private val isConnected = mutableStateOf(false)
    private val isReceiving = mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioReceiverService.LocalBinder
            audioReceiverService = binder.getService()
            isBound = true

            audioReceiverService?.onStatusChange = { status ->
                runOnUiThread {
                    statusMessage.value = status
                }
            }

            audioReceiverService?.onRoomUpdate = { users ->
                runOnUiThread {
                    roomUsers.value = users
                }
            }

            audioReceiverService?.onShowEnableButton = { show ->
                runOnUiThread {
                    showEnableButton.value = show
                }
            }

            audioReceiverService?.onConnectionChange = { connected ->
                runOnUiThread {
                    isConnected.value = connected
                }
            }

            audioReceiverService?.startReceiving()
            isReceiving.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioReceiverService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startReceiverService()
        } else {
            statusMessage.value = "Audio permission required"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReceiverScreen()
                }
            }
        }
    }

    @Composable
    fun ReceiverScreen() {
        val status by remember { statusMessage }
        val users by remember { roomUsers }
        val showButton by remember { showEnableButton }
        val connected by remember { isConnected }
        val receiving by remember { isReceiving }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🎧 Receiver",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Room: r1",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = if (users.isEmpty()) "No users in room"
                        else "Users (${users.size}): ${users.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        status.contains("playing", ignoreCase = true) -> 
                            MaterialTheme.colorScheme.primaryContainer
                        status.contains("error", ignoreCase = true) -> 
                            MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (showButton) {
                Button(
                    onClick = { audioReceiverService?.enableAudio() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(bottom = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "🔊 Click to Enable Audio",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Button(
                onClick = {
                    if (receiving) {
                        stopReceiver()
                    } else {
                        checkPermissionAndStart()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (receiving)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (receiving) "❌ Stop Receiving" else "▶️ Start Receiving",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "💡 Info",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "• Runs in background with notification\n• Accepts raw PCM audio (44.1kHz, 16-bit, Stereo)\n• You can lock your phone and it will keep playing",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startReceiverService()
        }
    }

    private fun startReceiverService() {
        val serviceIntent = Intent(this, AudioReceiverService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopReceiver() {
        audioReceiverService?.stopReceiving()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        isReceiving.value = false
        isConnected.value = false
        statusMessage.value = "Ready to receive"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiving.value) {
            stopReceiver()
        }
    }

    companion object {
        private const val TAG = "ReceiverActivity"
    }
}