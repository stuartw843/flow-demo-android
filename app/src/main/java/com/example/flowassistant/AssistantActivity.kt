// File: app/src/main/java/com/example/flowassistant/AssistantActivity.kt
package com.example.flowassistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.flowassistant.databinding.ActivityAssistantBinding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors

class AssistantActivity : AppCompatActivity() {

    // Constants
    private val REQUEST_CODE_PERMISSIONS = 1001
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val SAMPLE_RATE = 16000
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

    // Retry and Delay Constants
    private val MAX_RETRIES = 3
    private val RETRY_DELAY_MS = 2000L // 2 seconds delay between retries
    private val START_DELAY_MS = 500L  // 0.5 seconds delay at the start of the app

    // View Binding
    private lateinit var binding: ActivityAssistantBinding

    // Audio and Networking
    private lateinit var audioRecord: AudioRecord
    @Volatile
    private var isRecording = false
    @Volatile
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    private val executorService = Executors.newFixedThreadPool(2)

    // Assistant State
    @Volatile
    private var isAssistantRunning = false

    // Shared Preferences
    private val sharedPreferences by lazy {
        val masterKeyAlias = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this,
            "AppPrefs",
            masterKeyAlias,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = binding.assistantToolbar
        setSupportActionBar(toolbar)

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Only start the assistant if it's not already running
        if (!isAssistantRunning) {
            // Add a wait before starting the assistant
            Handler(Looper.getMainLooper()).postDelayed({
                checkApiKey()
            }, START_DELAY_MS)
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        } else {
            checkApiKey()
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                checkApiKey()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun checkApiKey() {
        val apiKey = sharedPreferences.getString("API_KEY", null)
        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, "Please set your API Key in settings.", Toast.LENGTH_LONG).show()
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        } else {
            if (!isAssistantRunning) {
                startAssistant()
            }
        }
    }

    private fun startAssistant() {
        synchronized(this) {
            if (isAssistantRunning) {
                // Assistant is already running, do not start a new session
                Log.d("AssistantActivity", "Assistant already running.")
                return
            }
            isAssistantRunning = true
        }

        // Fetch a new JWT token before each connection attempt
        fetchJwtToken { jwtToken ->
            if (jwtToken != null) {
                startWebSocket(jwtToken)
                // Start recording after conversation starts
                startRecording()
                updateStatusText("Listening...")
            } else {
                updateStatusText("Error fetching token")
                isAssistantRunning = false // Reset the flag if there's an error
            }
        }
    }

    private fun fetchJwtToken(callback: (String?) -> Unit) {
        val apiKey = sharedPreferences.getString("API_KEY", null)
        if (apiKey.isNullOrEmpty()) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "API Key not found. Please set it in settings.",
                    Toast.LENGTH_SHORT
                ).show()
                callback(null)
            }
            isAssistantRunning = false
            return
        }

        val requestBody = RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            "{\"ttl\":500}"
        )

        val request = Request.Builder()
            .url("https://mp.speechmatics.com/v1/api_keys?type=flow")
            .post(requestBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                callback(null)
                isAssistantRunning = false
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val jsonObject = JSONObject(responseBody)
                    val jwtToken = jsonObject.getString("key_value")
                    callback(jwtToken)
                } else {
                    callback(null)
                    isAssistantRunning = false
                }
            }
        })
    }

    private fun startWebSocket(jwtToken: String, retryCount: Int = 0) {
        val request = Request.Builder()
            .url("wss://flow.api.speechmatics.com/v1/flow?jwt=$jwtToken")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("AssistantActivity", "WebSocket opened.")
                sendStartConversationMessage()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleJsonMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleAudioMessage(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                t.printStackTrace()
                Log.e("AssistantActivity", "WebSocket error: ${t.message}")
                updateStatusText("WebSocket error: ${t.message}")
                if (retryCount < MAX_RETRIES) {
                    Log.d("AssistantActivity", "Retrying WebSocket connection (${retryCount + 1}/$MAX_RETRIES)")
                    Handler(Looper.getMainLooper()).postDelayed({
                        startWebSocket(jwtToken, retryCount + 1)
                    }, RETRY_DELAY_MS)
                } else {
                    Log.e("AssistantActivity", "Max retries reached. Stopping assistant.")
                    isAssistantRunning = false
                    stopAssistant()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AssistantActivity", "WebSocket is closing: $reason")
                isAssistantRunning = false
                webSocket.close(1000, null)
                stopAssistant()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AssistantActivity", "WebSocket closed: $reason")
                isAssistantRunning = false
                stopAssistant()
            }
        })
    }

    private fun sendStartConversationMessage() {
        val message = JSONObject().apply {
            put("message", "StartConversation")
            put("conversation_config", JSONObject().apply {
                put("template_id", "default")
                put("template_variables", JSONObject().apply {
                    put("timezone", TimeZone.getDefault().id)
                })
            })
            put("audio_format", JSONObject().apply {
                put("type", "raw")
                put("encoding", "pcm_s16le")
                put("sample_rate", SAMPLE_RATE)
            })
        }
        val messageString = message.toString()
        Log.d("AssistantActivity", "Sending StartConversation message: $messageString")
        webSocket?.send(messageString)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) {
            // Already recording
            Log.d("AssistantActivity", "Already recording audio.")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            updateStatusText("Error initializing microphone")
            Log.e("AssistantActivity", "Error initializing microphone.")
            isAssistantRunning = false
            return
        }

        // Enable Acoustic Echo Canceler
        if (AcousticEchoCanceler.isAvailable()) {
            val echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)
            if (echoCanceler != null) {
                echoCanceler.enabled = true
                Log.d("AssistantActivity", "Acoustic Echo Canceler enabled.")
            } else {
                Log.e("AssistantActivity", "Failed to create Acoustic Echo Canceler.")
            }
        } else {
            Log.e("AssistantActivity", "Acoustic Echo Canceler not available.")
        }

        // Enable Noise Suppressor
        if (NoiseSuppressor.isAvailable()) {
            val noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)
            if (noiseSuppressor != null) {
                noiseSuppressor.enabled = true
                Log.d("AssistantActivity", "Noise Suppressor enabled.")
            } else {
                Log.e("AssistantActivity", "Failed to create Noise Suppressor.")
            }
        } else {
            Log.e("AssistantActivity", "Noise Suppressor not available.")
        }

        audioRecord.startRecording()
        isRecording = true
        readAudioData()
    }

    private fun stopRecording() {
        if (!isRecording) {
            return
        }
        isRecording = false
        try {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
            audioRecord.release()
            Log.d("AssistantActivity", "Recording stopped.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readAudioData() {
        executorService.execute {
            val buffer = ByteArray(2048)
            try {
                while (isRecording && isAssistantRunning) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        sendAudioData(buffer.copyOf(read))
                    }
                }
                Log.d("AssistantActivity", "Stopped reading audio data.")
            } catch (e: Exception) {
                Log.e("AssistantActivity", "Error reading audio data: ${e.message}")
                e.printStackTrace()
                isRecording = false
                isAssistantRunning = false
                updateStatusText("Error reading audio data: ${e.message}")
                stopAssistant()
            }
        }
    }

    private fun sendAudioData(data: ByteArray) {
        try {
            if (isAssistantRunning && webSocket != null) {
                val sent = webSocket?.send(data.toByteString(0, data.size)) ?: false
                if (!sent) {
                    Log.e("AssistantActivity", "Failed to send data over WebSocket.")
                    updateStatusText("Failed to send audio data.")
                    stopAssistant()
                }
            } else {
                Log.e("AssistantActivity", "Cannot send data: Assistant is not running or WebSocket is null.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("AssistantActivity", "Exception in sendAudioData: ${e.message}")
            updateStatusText("Error sending data: ${e.message}")
            stopAssistant()
        }
    }

    private fun handleAudioMessage(bytes: ByteString) {
        val audioData = bytes.toByteArray()
        playAudio(audioData)
    }

    private fun playAudio(data: ByteArray) {
        audioTrack.write(data, 0, data.size)
    }

    private val audioTrack: AudioTrack by lazy {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AUDIO_FORMAT
        )

        AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).apply {
            play()
        }
    }

    private fun handleJsonMessage(text: String) {
        Log.d("AssistantActivity", "Received JSON message: $text")
        val json = JSONObject(text)
        when (json.optString("message")) {
            "ConversationStarted" -> {
                val sessionId = json.optString("id")
                updateStatusText("Session ID: $sessionId")
                Log.d("AssistantActivity", "Conversation started with session ID: $sessionId")

            }
            "Info" -> {
                val status = json.optJSONObject("event")?.optString("status")
                status?.let {
                    updateStatusText(it)
                    Log.d("AssistantActivity", "Info status: $it")
                }
            }
            "Error", "Warning" -> {
                val errorMsg = json.optString("reason")
                updateStatusText("Error: $errorMsg")
                Log.e("AssistantActivity", "Received error message: $errorMsg")
                stopAssistant()
            }
            "ConversationEnded" -> {
                updateStatusText("Conversation ended")
                Log.d("AssistantActivity", "Conversation ended.")
                stopAssistant()
            }
        }
    }

    private fun updateStatusText(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }

    private fun stopAssistant() {
        synchronized(this) {
            if (!isAssistantRunning) {
                // Assistant is already stopped
                Log.d("AssistantActivity", "Assistant already stopped.")
                return
            }
            isAssistantRunning = false
        }
        stopRecording()
        webSocket?.close(1000, null)
        webSocket = null
        Log.d("AssistantActivity", "Assistant stopped.")
    }

    override fun onPause() {
        super.onPause()
        stopAssistant()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAssistant()
        executorService.shutdownNow()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_assistant, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
