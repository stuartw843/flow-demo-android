package com.example.flowassistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
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
import android.os.SystemClock
import android.provider.AlarmClock
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val executorService = Executors.newFixedThreadPool(2)
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Lock screen and wake management
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var keyguardManager: KeyguardManager

    // Assistant State
    @Volatile
    private var isAssistantRunning = false
    @Volatile
    private var isHandlingTimerAction = false

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

    // Flags for buffering and conversation state
    @Volatile
    private var isConversationStarted = false
    @Volatile
    private var isBufferingAudioData = true

    // Queue to buffer audio data until conversation starts
    private val audioDataQueue = LinkedBlockingQueue<ByteArray>()

    // Variable to store user's audio output preference
    private var useSpeakerphone: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up window flags to work over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
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

        // Read user's audio output preference
        useSpeakerphone = sharedPreferences.getBoolean("USE_SPEAKERPHONE", true)

        checkAndRequestPermissions()
    }

    @SuppressLint("NewApi")
    private fun setupAudioSystem() {
        // First set the audio mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Request audio focus before setting up audio routing
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
                            Handler(Looper.getMainLooper()).post {
                                stopAssistant()
                            }
                        }
                    }
                }
                .build()

        audioFocusRequest = focusRequest
        val result = audioManager.requestAudioFocus(focusRequest)

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val targetDevice = if (useSpeakerphone) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                } ?: devices.firstOrNull()

                targetDevice?.let { device ->
                    audioManager.setCommunicationDevice(device)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = useSpeakerphone
            }

            // Set the volume control stream
            volumeControlStream = AudioManager.STREAM_VOICE_CALL
        }
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
            isBufferingAudioData = true   // Start buffering audio data
            audioSequence = 0 // Reset audio sequence number
            audioDataQueue.clear() // Clear any buffered audio data
        }

        // Keep the screen on while the assistant is running
        runOnUiThread {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Initialize audio system
        setupAudioSystem()

        // Start recording immediately
        startRecording()

        fetchJwtToken { jwtToken ->
            if (jwtToken != null) {
                startWebSocket(jwtToken)
                updateStatusText("Connecting...")
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
                    val jsonObject = JSONObject(responseBody ?: "{}")
                    val jwtToken = jsonObject.optString("key_value", null.toString())
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
                    Handler(Looper.getMainLooper()).post {
                        stopAssistant()
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AssistantActivity", "WebSocket is closing: $reason")
                isAssistantRunning = false
                webSocket.close(1000, null)
                Handler(Looper.getMainLooper()).post {
                    stopAssistant()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AssistantActivity", "WebSocket closed: $reason")
                isAssistantRunning = false
                Handler(Looper.getMainLooper()).post {
                    stopAssistant()
                }
            }
        })
    }

    private fun sendStartConversationMessage() {
        val tools = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "set_timer")
                    put("description", "Set a timer for a specified duration")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("duration", JSONObject().apply {
                                put("type", "integer")
                                put("description", "Duration value")
                            })
                            put("unit", JSONObject().apply {
                                put("type", "string")
                                put("description", "Time unit (seconds, minutes, hours)")
                                put("enum", JSONArray().apply {
                                    put("seconds")
                                    put("minutes")
                                    put("hours")
                                })
                            })
                        })
                        put("required", JSONArray().apply {
                            put("duration")
                            put("unit")
                        })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "cancel_timer")
                    put("description", "Cancel and delete all running timers")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })
        }

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
            put("tools", tools)
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
                            if (isBufferingAudioData) {
                                // Buffer new data until we catch up
                                audioDataQueue.offer(data)
                            } else {
                                // Send data immediately
                                sendAudioData(data)
                            }
                        } else {
                            // Buffer data
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
                Handler(Looper.getMainLooper()).post {
                    stopAssistant()
                }
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
                    Handler(Looper.getMainLooper()).post {
                        stopAssistant()
                    }
                }
            } else {
                // Buffer the data if conversation hasn't started yet or if we're still buffering
                audioDataQueue.offer(data)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("AssistantActivity", "Exception in sendAudioData: ${e.message}")
            updateStatusText("Error sending data: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                stopAssistant()
            }
        }
    }

    private fun startSendingBufferedAudioData() {
        executorService.execute {
            try {
                while (isAssistantRunning && isConversationStarted && audioDataQueue.isNotEmpty()) {
                    val data = audioDataQueue.poll()
                    if (data != null) {
                        sendAudioData(data)
                        // Send data as fast as possible
                    } else {
                        break
                    }
                }
                // After sending all buffered data
                isBufferingAudioData = false
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("AssistantActivity", "Error sending buffered audio data: ${e.message}")
                updateStatusText("Error sending audio data.")
                Handler(Looper.getMainLooper()).post {
                    stopAssistant()
                }
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
            try {
                while (audioChunkQueue.isNotEmpty()) {
                    val data = audioChunkQueue.poll()
                    if (data != null) {
                        playAudio(data)
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)  // Add this flag
                .build(),
            AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            audioManager.generateAudioSessionId()  // Use a new session ID
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
                Handler(Looper.getMainLooper()).post {
                    stopAssistant()
                }
            }
            "ConversationEnded" -> {
                updateStatusText("Conversation ended")
                Log.d("AssistantActivity", "Conversation ended.")
                Handler(Looper.getMainLooper()).post {
                    stopAssistant()
                }
            }
            "ToolInvoke" -> {
                val id = json.optString("id")
                val function = json.optJSONObject("function")
                when (function?.optString("name")) {
                    "set_timer" -> {
                        val args = function.optJSONObject("arguments")
                        val duration = args?.optInt("duration", 0) ?: 0
                        val unit = args?.optString("unit", "") ?: ""
                        handleSetTimer(id, duration, unit)
                    }
                    "cancel_timer" -> {
                        handleCancelTimer(id)
                    }
                }
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

    private fun handleSetTimer(id: String, duration: Int, unit: String) {
        isHandlingTimerAction = true
        val seconds = when (unit) {
            "seconds" -> duration
            "minutes" -> duration * 60
            "hours" -> duration * 3600
            else -> 0
        }

        if (seconds > 0) {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)  // Skip UI to keep voice session in front
                putExtra(AlarmClock.EXTRA_MESSAGE, "Timer for $duration $unit")
            }

            try {
                startActivity(intent)
                // Bring our activity back to front immediately
                val bringToFrontIntent = Intent(this, AssistantActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                startActivity(bringToFrontIntent)

                // Send success response
                val response = JSONObject().apply {
                    put("message", "ToolResult")
                    put("id", id)
                    put("status", "ok")
                    put("content", "Timer set for $duration $unit")
                }
                webSocket?.send(response.toString())
            } catch (e: Exception) {
                // Send error response if the clock app is not available
                val response = JSONObject().apply {
                    put("message", "ToolResult")
                    put("id", id)
                    put("status", "failed")
                    put("content", "Could not launch timer: ${e.message}")
                }
                webSocket?.send(response.toString())
            }
        } else {
            // Send error response for invalid duration
            val response = JSONObject().apply {
                put("message", "ToolResult")
                put("id", id)
                put("status", "failed")
                put("content", "Invalid timer duration or unit")
            }
            webSocket?.send(response.toString())
        }
        isHandlingTimerAction = false
    }

    private fun handleCancelTimer(id: String) {
        isHandlingTimerAction = true
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Create an intent similar to what's used for setting timers
            val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            // Cancel any pending intents that match this timer intent
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                timerIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }

            // Send success response
            val response = JSONObject().apply {
                put("message", "ToolResult")
                put("id", id)
                put("status", "ok")
                put("content", "Cancelled all timers")
            }
            webSocket?.send(response.toString())
        } catch (e: Exception) {
            // Send error response if cancellation fails
            val response = JSONObject().apply {
                put("message", "ToolResult")
                put("id", id)
                put("status", "failed")
                put("content", "Could not cancel timers: ${e.message}")
            }
            webSocket?.send(response.toString())
        }
        isHandlingTimerAction = false
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

        // Non-UI operations first
        stopRecording()
        sendAudioEndedMessage()
        webSocket?.close(1000, null)
        webSocket = null

        // UI operations wrapped in runOnUiThread
        runOnUiThread {
            // Clear the keep screen on flag
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Audio operations
        audioManager.mode = AudioManager.MODE_NORMAL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }

        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
        }

        // Release wake lock if held
        if (wakeLock.isHeld) {
            try {
                wakeLock.release()
            } catch (e: Exception) {
                Log.e("AssistantActivity", "Error releasing wake lock: ${e.message}")
            }
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
        // Only stop assistant if we're not handling a timer action
        if (!isHandlingTimerAction) {
            stopAssistant()
        }
    }

    @SuppressLint("Wakelock")
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
