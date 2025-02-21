package com.example.voicenotes

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val savedNotes = mutableStateListOf<String>()
    private lateinit var sharedPreferences: SharedPreferences
    private var apiKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        apiKey = sharedPreferences.getString("openai_api_key", null)
        requestPermissions()

        setContent {
            if (apiKey.isNullOrEmpty()) {
                ApiKeyScreen { key ->
                    apiKey = key
                    sharedPreferences.edit().putString("openai_api_key", key).apply()
                    setContent {
                        VoiceRecorderUI(
                            onRecordStart = { startRecording() },
                            onRecordStop = { stopRecording() },
                            savedNotes = savedNotes
                        )
                    }
                }
            } else {
                VoiceRecorderUI(
                    onRecordStart = { startRecording() },
                    onRecordStop = { stopRecording() },
                    savedNotes = savedNotes
                )
            }
        }
    }

    private fun requestPermissions() {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        audioFile = File(cacheDir, "recorded_audio.mp3")
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(audioFile?.absolutePath)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
            start()
        }
    }

    private fun stopRecording() {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        audioFile?.let { transcribeAudio(it) }
    }

    private fun transcribeAudio(audioFile: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name,
                    RequestBody.create(MediaType.parse("audio/mpeg"), audioFile))
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val transcript = response.body()?.string() ?: ""
                    summarizeText(transcript)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun summarizeText(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val json = """{"model": "gpt-4o-mini", "messages": [{"role": "system", "content": "Summarize the following text concisely."}, {"role": "user", "content": "$text"}]}"""
            val requestBody = RequestBody.create(MediaType.parse("application/json"), json)

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val summary = response.body()?.string() ?: ""
                    savedNotes.add(summary)
                    saveToGoogleNotes(summary)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun saveToGoogleNotes(note: String) {
        println("Saving note to Google Keep: $note")
    }
}

@Composable
fun ApiKeyScreen(onApiKeyEntered: (String) -> Unit) {
    var apiKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Enter OpenAI API Key:")
        TextField(value = apiKey, onValueChange = { apiKey = it })
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { onApiKeyEntered(apiKey) }) {
            Text("Save API Key")
        }
    }
}

@Composable
fun VoiceRecorderUI(onRecordStart: () -> Unit, onRecordStop: () -> Unit, savedNotes: List<String>) {
    var isRecording by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = {
            if (isRecording) {
                onRecordStop()
            } else {
                onRecordStart()
            }
            isRecording = !isRecording
        }) {
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Saved Notes:", style = MaterialTheme.typography.headlineSmall)

        LazyColumn {
            items(savedNotes) { note ->
                Card(modifier = Modifier.padding(8.dp)) {
                    Text(note, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
