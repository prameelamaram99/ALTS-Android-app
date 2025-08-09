package com.example.altsclient

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var recordButton: Button
    private lateinit var serverUrl: EditText
    private lateinit var responseTextView: TextView
    private var recorder: MediaRecorder? = null
    private lateinit var fileName: String
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val RECORD_REQUEST_CODE = 101
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.recordButton)
        serverUrl = findViewById(R.id.serverUrl)
        responseTextView = findViewById(R.id.responseTextView)

        fileName = "${externalCacheDir?.absolutePath ?: filesDir.absolutePath}/audio_record.m4a"

        requestPermissions()

        // Set initial button color (green for "Start Recording")
        recordButton.text = "Start Recording"
        recordButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))

        recordButton.setOnClickListener {
            if (!isRecording) {
                if (hasPermission()) {
                    startRecording()
                    recordButton.text = "Stop Recording"
                    recordButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                    isRecording = true
                } else {
                    Toast.makeText(this, "Microphone permission not granted", Toast.LENGTH_SHORT).show()
                }
            } else {
                stopRecording()
                recordButton.text = "Start Recording"
                recordButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                isRecording = false
            }
        }
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET), RECORD_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission granted
        } else {
            Toast.makeText(this, "Permissions required for recording and network", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRecording() {
        try {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fileName)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                prepare()
                start()
            }
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("RecordingError", "Failed to start recording: ${e.message}", e)
            Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            recorder?.apply {
                stop()
                reset()
            }
            recorder?.release()
            recorder = null
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
            val audioFile = File(fileName)
            Log.d("AudioFile", "Recorded file size: ${audioFile.length()} bytes")
            if (audioFile.exists() && audioFile.length() > 1024) {
                try {
                    val player = MediaPlayer().apply {
                        setDataSource(fileName)
                        prepare()
                        release()
                    }
                    sendAudioToServer()
                } catch (e: Exception) {
                    Log.e("AudioValidation", "Invalid audio file: ${e.message}", e)
                    Toast.makeText(this, "Invalid audio file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Recorded file is empty or too small", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("StopError", "Error stopping recorder: ${e.message}", e)
            Toast.makeText(this, "Failed to stop recording: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            recorder?.release()
            recorder = null
        }
    }

    private fun sendAudioToServer() {
        val file = File(fileName)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Recorded file not found or empty", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("AudioFile", "Sending audio file, size: ${file.length()} bytes")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio_record.m4a", file.asRequestBody("audio/mp4".toMediaType()))
            .build()

        val url = if (serverUrl.text.toString().startsWith("http")) {
            "${serverUrl.text}/process_audio"
        } else {
            "http://${serverUrl.text}/process_audio"
        }
        Log.d("Request", "Sending to $url")

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("NetworkError", "Failed to send: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d("Response", "Response code: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e("ServerError", "Error: ${response.code} - ${response.message}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Server error: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                try {
                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrEmpty()) {
                        Log.e("ResponseError", "Received empty response")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Empty response from server", Toast.LENGTH_LONG).show()
                        }
                        return
                    }

                    val json = JSONObject(responseBody)
                    val llmText = json.getString("text")
                    val audioBase64 = json.getString("audio")

                    // Update UI with LLM response
                    runOnUiThread {
                        responseTextView.text = llmText
                    }

                    // Decode and play audio
                    val audioData = Base64.decode(audioBase64, Base64.DEFAULT)
                    playResponse(audioData)
                } catch (e: Exception) {
                    Log.e("ResponseError", "Error handling response: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to process response: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun playResponse(audioData: ByteArray) {
        try {
            // Create a temporary file to store the audio
            val tempFile = File.createTempFile("response", ".wav", cacheDir)
            tempFile.writeBytes(audioData)

            val player = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                Log.d("MediaPlayer", "Playing response audio, duration: ${getDuration()} ms")
            }
            player.setOnCompletionListener {
                Log.d("MediaPlayer", "Response audio playback completed")
                it.release()
                tempFile.delete() // Clean up temporary file
            }
        } catch (e: Exception) {
            Log.e("PlayError", "Error playing response: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Failed to play response: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder?.release()
        recorder = null
    }
}