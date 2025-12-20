package com.example.shareaudioreceiver

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import okhttp3.*
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var webSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var isReceiving = false

    private val statusMessage = mutableStateOf("Ready to receive")
    private val roomUsers = mutableStateOf(listOf<String>())
    private val isAudioEnabled = mutableStateOf(false)

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
        val audioEnabled by remember { isAudioEnabled }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "🎧 PCM Audio Receiver",
                style = MaterialTheme.typography.headlineMedium
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Room: r1",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = if (users.isEmpty()) "No users in room"
                        else "Users (${users.size}): ${users.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Status: $status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isReceiving) {
                Button(
                    onClick = { startReceiving() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Start Receiving", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = { stopReceiving() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Stop Receiving", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (!audioEnabled && isReceiving) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "⚠️ Audio Not Playing",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = "Click the button below to enable audio playback",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Button(
                            onClick = { enableAudio() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("🔊 Enable Audio")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ℹ️ Information:",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "• Receives raw PCM audio from Android sender",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Audio format: 44100 Hz, 16-bit, Stereo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Works even when app is in background",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Low latency real-time streaming",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    private fun startReceiving() {
        if (isReceiving) return

        isReceiving = true
        statusMessage.value = "Connecting..."

        // Initialize AudioTrack
        initAudioTrack()

        // Connect to WebSocket
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("wss://socketbe.onrender.com")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                val joinMsg = JSONObject().apply {
                    put("type", "join")
                    put("roomId", "r1")
                    put("userId", "AndroidReceiver")
                }
                webSocket.send(joinMsg.toString())
                runOnUiThread {
                    statusMessage.value = "Connected - Waiting for audio..."
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Received binary PCM data
                playPCMData(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Received JSON message
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                runOnUiThread {
                    statusMessage.value = "Connection error: ${t.message}"
                    isReceiving = false
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    statusMessage.value = "Disconnected"
                    isReceiving = false
                }
            }
        })
    }

    private fun stopReceiving() {
        isReceiving = false
        webSocket?.close(1000, "User stopped receiving")
        webSocket = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        isAudioEnabled.value = false
        statusMessage.value = "Ready to receive"
    }

    private fun initAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 4) // Larger buffer for smoother playback
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Request audio focus to ensure playback even in background
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioManager.AUDIOFOCUS_GAIN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioFocusRequest = android.media.AudioFocusRequest.Builder(focusRequest)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .build()
            audioManager.requestAudioFocus(audioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                focusRequest
            )
        }

        audioTrack?.play()
        isAudioEnabled.value = true
        Log.d(TAG, "AudioTrack initialized and playing")
    }

    private fun enableAudio() {
        audioTrack?.play()
        isAudioEnabled.value = true
        statusMessage.value = "🔊 Audio enabled - Listening..."
    }

    private fun playPCMData(pcmData: ByteArray) {
        if (audioTrack == null || !isReceiving) return

        try {
            // Write PCM data directly to AudioTrack
            val written = audioTrack?.write(pcmData, 0, pcmData.size) ?: 0

            if (written > 0 && statusMessage.value.contains("Waiting")) {
                runOnUiThread {
                    statusMessage.value = "🔊 Receiving audio!"
                }
            }

            if (written < 0) {
                Log.e(TAG, "AudioTrack write error: $written")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing PCM data", e)
        }
    }

    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            if (json.getString("type") == "roomUpdate") {
                val usersArray = json.getJSONArray("users")
                val users = mutableListOf<String>()
                for (i in 0 until usersArray.length()) {
                    users.add(usersArray.getString(i))
                }
                runOnUiThread {
                    roomUsers.value = users
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiving()
    }

    companion object {
        private const val TAG = "AudioReceiver"
    }
}