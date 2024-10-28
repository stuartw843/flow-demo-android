// File: app/src/main/java/com/example/flowassistant/SettingsActivity.kt
package com.example.flowassistant

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.flowassistant.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    // View Binding
    private lateinit var binding: ActivitySettingsBinding

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
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load existing preferences
        val existingApiKey = sharedPreferences.getString("API_KEY", "")
        val useSpeakerphone = sharedPreferences.getBoolean("USE_SPEAKERPHONE", true)

        // Set existing values in UI
        binding.apiKeyEditText.setText(existingApiKey)
        binding.speakerphoneSwitch.isChecked = useSpeakerphone

        binding.saveButton.setOnClickListener {
            val apiKey = binding.apiKeyEditText.text.toString().trim()
            val useSpeakerphoneChecked = binding.speakerphoneSwitch.isChecked

            if (apiKey.isNotEmpty()) {
                // Save preferences
                sharedPreferences.edit()
                    .putString("API_KEY", apiKey)
                    .putBoolean("USE_SPEAKERPHONE", useSpeakerphoneChecked)
                    .apply()

                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Please enter a valid API Key", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
