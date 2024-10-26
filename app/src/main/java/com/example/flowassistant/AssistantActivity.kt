// File: app/src/main/java/com/example/flowassistant/AssistantActivity.kt
package com.example.flowassistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.flowassistant.databinding.ActivityAssistantBinding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class AssistantActivity : AppCompatActivity() {

    // Constants
    private val REQUEST_CODE_PERMISSIONS = 1001
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val SAMPLE_RATE = 16000
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

    // Retry and Delay Constants
    private val MAX_RETRIES = 3
    private val RETRY_DELAY_MS = 2000L
    private val START_DELAY_MS = 1000L

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
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Lock screen and wake management
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var keyguardManager: KeyguardManager

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

    // Variables for handling audio playback
    private val audioChunkQueue: Queue<ByteArray> = LinkedList()
    @Volatile
    private var isPlayingAudio = false

    // Variables for handling audio sequence numbers
    private var audioSequence = 0

    // Flag to indicate if the conversation has started
    @Volatile
    private var isConversationStarted = false

    // Queue to buffer audio data until conversation starts
    private val audioDataQueue = LinkedBlockingQueue<ByteArray>()

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up window flags to work over lock screen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Initialize wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FlowAssistant::AssistantWakeLock"
        )
        wakeLock.acquire(10 * 60 * 1000L) // 10 minutes timeout

        // Initialize keyguard manager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        binding = ActivityAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = binding.assistantToolbar
        setSupportActionBar(toolbar)

        // Initialize AudioManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        setupAudioSystem()

        checkAndRequestPermissions()
    }

    @SuppressLint("NewApi")
    private fun setupAudioSystem() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.setSpeakerphoneOn(false)

        val focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            stopAssistant()
                        }
                    }
                }
                .build()

        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
    }

    override fun onResume() {
        super.onResume()
        if (!isAssistantRunning) {
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
                Log.d("AssistantActivity", "Assistant already running.")
                return
            }
            isAssistantRunning = true
            isConversationStarted = false // Reset the flag
            audioSequence = 0 // Reset audio sequence number
            audioDataQueue.clear() // Clear any buffered audio data
        }

        fetchJwtToken { jwtToken ->
            if (jwtToken != null) {
                startWebSocket(jwtToken)
                updateStatusText("Connecting...")
                // Start recording, but don't send data yet
                startRecording()
            } else {
                updateStatusText("Error fetching token")
                isAssistantRunning = false
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

        val requestBody = "{\"ttl\":500}".toRequestBody("application/json".toMediaTypeOrNull())

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
                Log.d("AssistantActivity", "WebSocket connected.")
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
                    Log.d(
                        "AssistantActivity",
                        "Retrying WebSocket connection (${retryCount + 1}/$MAX_RETRIES)"
                    )
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
                put("template_id", "default") // You can change this to use a selected persona if needed
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

        if (AcousticEchoCanceler.isAvailable()) {
            val echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)
            if (echoCanceler != null) {
                echoCanceler.enabled = true
                Log.d("AssistantActivity", "Acoustic Echo Canceler enabled.")
            }
        }

        if (NoiseSuppressor.isAvailable()) {
            val noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)
            if (noiseSuppressor != null) {
                noiseSuppressor.enabled = true
                Log.d("AssistantActivity", "Noise Suppressor enabled.")
            }
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
            val buffer = ByteArray(1024)
            try {
                while (isRecording && isAssistantRunning) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val data = buffer.copyOf(read)
                        if (isConversationStarted) {
                            sendAudioData(data)
                        } else {
                            // Buffer the data until conversation starts
                            audioDataQueue.offer(data)
                        }
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
            if (isAssistantRunning && isConversationStarted && webSocket != null) {
                audioSequence++
                val sent = webSocket?.send(data.toByteString()) ?: false
                if (!sent) {
                    Log.e("AssistantActivity", "Failed to send data over WebSocket.")
                    updateStatusText("Failed to send audio data.")
                    stopAssistant()
                }
            } else {
                // Buffer the data if conversation hasn't started yet
                audioDataQueue.offer(data)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("AssistantActivity", "Exception in sendAudioData: ${e.message}")
            updateStatusText("Error sending data: ${e.message}")
            stopAssistant()
        }
    }

    private fun startSendingBufferedAudioData() {
        executorService.execute {
            try {
                while (isAssistantRunning && isConversationStarted && audioDataQueue.isNotEmpty()) {
                    val data = audioDataQueue.poll()
                    sendAudioData(data)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("AssistantActivity", "Error sending buffered audio data: ${e.message}")
                updateStatusText("Error sending audio data.")
                stopAssistant()
            }
        }
    }

    private fun handleAudioMessage(bytes: ByteString) {
        val audioData = bytes.toByteArray()
        audioChunkQueue.add(audioData)
        if (!isPlayingAudio) {
            playAudioFromQueue()
        }
    }

    private fun playAudioFromQueue() {
        executorService.execute {
            isPlayingAudio = true
            while (audioChunkQueue.isNotEmpty()) {
                val data = audioChunkQueue.poll()
                playAudio(data)
            }
            isPlayingAudio = false
        }
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
            audioManager.generateAudioSessionId()
        ).apply {
            setVolume(0.8f)
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
                isConversationStarted = true // Set the flag to true
                // Start sending any buffered audio data
                startSendingBufferedAudioData()
            }
            "Info" -> {
                val status = json.optJSONObject("event")?.optString("status")
                status?.let {
                    updateStatusText(it)
                    Log.d("AssistantActivity", "Info status: $it")
                }
            }
            "Warning", "Error" -> {
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
            else -> {
                // Handle other messages
            }
        }

        // Handle "prompt" messages
        if (json.has("prompt")) {
            val promptJson = json.optJSONObject("prompt")
            promptJson?.let {
                val prompt = it.optString("prompt")
                val response = it.optString("response")
                Log.d("AssistantActivity", "Prompt received: $prompt, Response: $response")
                // You can update the UI or store prompts here
            }
        }

        // Handle "passive" state
        if (json.has("passive")) {
            val passive = json.optBoolean("passive")
            Log.d("AssistantActivity", "Passive state: $passive")
            // You can update the UI to reflect the passive state
        }

        // Handle "audio" messages containing base64-encoded audio
        if (json.has("audio")) {
            val audioArray = json.optJSONArray("audio")
            audioArray?.let {
                for (i in 0 until it.length()) {
                    val base64String = it.getString(i)
                    val audioData = Base64.decode(base64String, Base64.DEFAULT)
                    handleAudioMessage(audioData.toByteString())
                }
            }
        }
    }

    private fun updateStatusText(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }

    @SuppressLint("NewApi")
    private fun stopAssistant() {
        synchronized(this) {
            if (!isAssistantRunning) {
                Log.d("AssistantActivity", "Assistant already stopped.")
                return
            }
            isAssistantRunning = false
        }
        stopRecording()
        sendAudioEndedMessage()
        webSocket?.close(1000, null)
        webSocket = null

        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.setSpeakerphoneOn(true)

        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
        }

        // Release wake lock if held
        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        Log.d("AssistantActivity", "Assistant stopped.")
    }

    private fun sendAudioEndedMessage() {
        val message = JSONObject().apply {
            put("message", "AudioEnded")
            put("last_seq_no", audioSequence)
        }
        val messageString = message.toString()
        Log.d("AssistantActivity", "Sending AudioEnded message: $messageString")
        webSocket?.send(messageString)
    }

    override fun onPause() {
        super.onPause()
        stopAssistant()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAssistant()
        executorService.shutdownNow()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
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
