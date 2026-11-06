package com.qwen.tts.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qwen.tts.android.data.VoiceRecorder
import com.qwen.tts.android.data.db.GenerationEntity
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.android.data.db.VoiceProfileEntity
import com.qwen.tts.android.ui.theme.QwenTtsTheme
import com.qwen.tts.studio.engine.LlamaTtsEngine
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ModelFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
)

private data class LanguageOption(val label: String, val id: Int, val code: String)
private data class BackendOption(val id: String, val label: String, val vulkan: Boolean)
private data class GenerationTimeEstimate(val totalMillis: Long, val sampleSize: Int)

private val languageOptions = listOf(
    LanguageOption("English", 2050, "en"),
    LanguageOption("German", 2053, "de"),
    LanguageOption("Spanish", 2054, "es"),
    LanguageOption("French", 2061, "fr"),
    LanguageOption("Chinese", 2055, "zh"),
    LanguageOption("Japanese", 2058, "ja"),
    LanguageOption("Korean", 2064, "ko"),
    LanguageOption("Russian", 2069, "ru"),
)

private val backendOptions = listOf(
    BackendOption("cpu", "CPU only", vulkan = false),
    BackendOption("vulkan", "Vulkan vocoder", vulkan = true),
)

private object QwenModel {
    private const val baseUrl = "https://hf-mirror.com/mradermacher/Qwen3-TTS-12Hz-0.6B-Base-GGUF/resolve/main"
    const val displayName = "Qwen3-TTS 0.6B Base"
    const val talkerName = "Qwen3-TTS-12Hz-0.6B-Base.Q4_K_M.gguf"
    const val mmprojName = "Qwen3-TTS-12Hz-0.6B-Base.mmproj-Q8_0.gguf"

    val files = listOf(
        ModelFile(talkerName, "$baseUrl/$talkerName?download=true", 361_023_168L),
        ModelFile(mmprojName, "$baseUrl/$mmprojName?download=true", 401_129_632L),
    )
    val totalBytes = files.sumOf { it.sizeBytes }
}

data class QwenTtsUiState(
    val text: String = "你好，这是手机本地语音合成测试。",
    val selectedBackendId: String = backendOptions.first().id,
    val selectedVoiceId: String? = null,
    val activeBackendName: String? = null,
    val modelReady: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = QwenModel.totalBytes,
    val status: String = "Model not downloaded",
    val error: String? = null,
    val sampleRate: Int = 0,
    val sampleCount: Int = 0,
    val synthesisMillis: Long = 0,
    val playing: Boolean = false,
    val playingGenerationId: Long? = null,
    val operationElapsedMillis: Long = 0,
    val maxAudioTokens: Int = 512,
    val selectedCpuThreads: Int = 0,
    val selectedLanguageId: Int = 2055,
    val decodeFrames: Int = 0,
    val snackbarMessage: String? = null,
    val estimatedSynthesisMillis: Long? = null,
    val estimateSampleCount: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val runtimeSettings = application.getSharedPreferences("runtime-settings", Application.MODE_PRIVATE)
    private val modelDir = File(application.filesDir, "qwen3-tts-models")
    private val voiceDir = File(application.filesDir, "voices")
    private val previewWav = AtomicFile(File(application.cacheDir, "qwen3-tts-preview.wav"))
    private val dao = QwenDatabase.getDatabase(application).qwenDao()
    private val recorder = VoiceRecorder()

    val voices = dao.observeVoices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val generations = dao.observeGenerations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recorderState = recorder.state

    private val _uiState = MutableStateFlow(
        QwenTtsUiState(
            modelReady = isModelReady(),
            selectedBackendId = backendOptionById(runtimeSettings.getString("backend", null)).id,
        ),
    )
    val uiState = _uiState.asStateFlow()

    private val engine = LlamaTtsEngine()
    private var generatedAudio: FloatArray? = null
    private var playJob: Job? = null
    private var operationTickerJob: Job? = null
    private var activeTrack: AudioTrack? = null

    init {
        val threads = preferredCpuThreadCount()
        _uiState.update { it.copy(selectedCpuThreads = threads) }
        refreshModelState()
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
    }

    fun updateCpuThreads(value: Int) {
        val current = _uiState.value
        if (current.selectedCpuThreads != value) {
            changeEngineConfiguration(current.selectedBackendId, value)
        }
    }

    fun updateLanguage(languageId: Int) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(selectedLanguageId = languageId) }
    }

    fun selectVoice(voiceId: String?) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(selectedVoiceId = voiceId) }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun updateBackend(backendId: String) {
        val current = _uiState.value
        if (current.selectedBackendId != backendId) {
            changeEngineConfiguration(backendId, current.selectedCpuThreads)
        }
    }

    fun unloadModel() {
        val current = _uiState.value
        if (current.loaded) changeEngineConfiguration(current.selectedBackendId, current.selectedCpuThreads)
    }

    private fun changeEngineConfiguration(backendId: String, threads: Int) {
        val current = _uiState.value
        if (current.busy) return
        viewModelScope.launch {
            if (current.loaded) {
                startBusy(status = "Unloading model")
                withContext(Dispatchers.IO) { engine.unload() }
                stopBusyTicker()
            }
            runtimeSettings.edit().putString("backend", backendId).apply()
            _uiState.update {
                it.copy(
                    selectedBackendId = backendId,
                    selectedCpuThreads = threads,
                    activeBackendName = null,
                    busy = false,
                    loaded = false,
                    status = if (current.loaded) "Model unloaded" else it.status,
                    error = null,
                )
            }
        }
    }

    fun downloadModel() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            startBusy(status = "Preparing model download", downloading = true)
            _uiState.update {
                it.copy(downloadProgress = 0f, downloadBytes = 0L, downloadTotalBytes = QwenModel.totalBytes)
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    modelDir.mkdirs()
                    downloadFiles()
                }
            }

            result.fold(
                onSuccess = {
                    stopBusyTicker()
                    refreshModelState("Model ready")
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            downloading = false,
                            status = "Download failed",
                            error = throwable.message ?: "Download failed",
                        )
                    }
                },
            )
        }
    }

    fun loadModel() {
        if (!_uiState.value.modelReady || _uiState.value.loaded || _uiState.value.busy) return
        viewModelScope.launch {
            startBusy(status = "Loading native model")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ensureEngineLoaded()
                }
            }

            result.fold(
                onSuccess = {
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "Model loaded on ${backendOptionById(it.selectedBackendId).label}",
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = false,
                            activeBackendName = null,
                            status = "Load failed",
                            error = throwable.message ?: "Load failed",
                        )
                    }
                },
            )
        }
    }

    fun synthesize() {
        val current = _uiState.value
        val text = current.text.trim()
        if (text.isEmpty() || current.busy || !current.modelReady || recorderState.value.isRecording) return
        val voice = current.selectedVoiceId?.let { id -> voices.value.firstOrNull { it.voiceId == id } }
        val language = languageOptions.first { it.id == current.selectedLanguageId }.code
        viewModelScope.launch {
            val estimate = estimateSynthesisMillis(text)
            stopAudio()
            startBusy(status = "Synthesizing speech", synthesisEstimate = estimate)

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val reference = voice?.referenceWavPath
                    if (current.selectedVoiceId != null) {
                        check(reference != null && File(reference).isFile) { "The selected voice recording is missing." }
                    }
                    ensureEngineLoaded()
                    val start = SystemClock.elapsedRealtime()
                    val speech = engine.synthesize(text, reference, language, current.maxAudioTokens)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    check(speech.audio.isNotEmpty() && speech.sampleRate > 0) { "The model returned no audio." }
                    writePreviewWav(speech.audio, speech.sampleRate)
                    speech to elapsed
                }
            }

            result.fold(
                onSuccess = { (speech, elapsed) ->
                    stopBusyTicker()
                    generatedAudio = speech.audio
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "Speech ready",
                            sampleRate = speech.sampleRate,
                            sampleCount = speech.audio.size,
                            synthesisMillis = elapsed,
                            decodeFrames = speech.frames,
                            estimatedSynthesisMillis = null,
                            estimateSampleCount = 0,
                            error = null,
                        )
                    }
                    playAudio()
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "Synthesis failed",
                            estimatedSynthesisMillis = null,
                            estimateSampleCount = 0,
                            error = throwable.message ?: "Synthesis failed",
                        )
                    }
                },
            )
        }
    }

    fun startVoiceRecording() {
        if (_uiState.value.busy || recorderState.value.isRecording) return
        val file = File(voiceDir, "pending-${System.currentTimeMillis()}.wav")
        if (!recorder.start(file)) {
            _uiState.update { it.copy(error = "Could not start microphone recording") }
        }
    }

    fun stopVoiceRecordingAndCreate(name: String) {
        if (_uiState.value.busy) return
        val result = recorder.stop() ?: return
        saveVoiceProfile(name) { target ->
            result.file.copyTo(target)
            result.file.delete()
            result.durationMillis
        }
    }

    fun importVoice(name: String, uri: Uri) {
        if (_uiState.value.busy || recorderState.value.isRecording) return
        saveVoiceProfile(name) { target ->
            val resolver = getApplication<Application>().contentResolver
            val stream = resolver.openInputStream(uri) ?: error("Could not open the selected WAV.")
            stream.use { input -> target.outputStream().buffered().use { output -> input.copyTo(output) } }
            RandomAccessFile(target, "r").use { wav ->
                require(wav.length() > 44 && wav.readInt() == 0x52494646) { "Choose a WAV file." }
                wav.seek(8)
                require(wav.readInt() == 0x57415645) { "Choose a WAV file." }
            }
            MediaMetadataRetriever().use { media ->
                media.setDataSource(target.absolutePath)
                media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0L } ?: error("The WAV contains no readable audio.")
            }
        }
    }

    private fun saveVoiceProfile(name: String, saveReference: (File) -> Long) {
        val voiceName = name.trim().ifBlank { "Voice ${voices.value.size + 1}" }
        viewModelScope.launch {
            startBusy(status = "Saving voice recording")
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    val voiceId = "voice-${UUID.randomUUID()}"
                    val targetDir = File(voiceDir, voiceId).apply { mkdirs() }
                    val reference = File(targetDir, "reference.wav")
                    val duration = try {
                        saveReference(reference)
                    } catch (error: Exception) {
                        reference.delete()
                        targetDir.delete()
                        throw error
                    }
                    VoiceProfileEntity(
                        voiceId = voiceId,
                        name = makeUniqueVoiceName(voiceName),
                        referenceWavPath = reference.absolutePath,
                        speakerEmbeddingPath = "",
                        durationMillis = duration,
                    ).also { dao.insertVoice(it) }
                }
            }

            created.fold(
                onSuccess = { voice ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            selectedVoiceId = voice.voiceId,
                            status = "Voice created",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "Voice creation failed",
                            error = throwable.message ?: "Voice creation failed",
                        )
                    }
                },
            )
        }
    }

    fun cancelVoiceRecording() {
        recorder.cancel()
    }

    fun deleteVoice(voice: VoiceProfileEntity) {
        if (_uiState.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteVoice(voice)
            runCatching { File(voice.referenceWavPath).parentFile?.deleteRecursively() }
            if (_uiState.value.selectedVoiceId == voice.voiceId) {
                _uiState.update { it.copy(selectedVoiceId = null) }
            }
        }
    }

    fun playGeneration(generationId: Long) {
        if (_uiState.value.playing && _uiState.value.playingGenerationId == generationId) {
            stopAudio()
            return
        }
        viewModelScope.launch {
            val generation = withContext(Dispatchers.IO) { dao.getGeneration(generationId) } ?: return@launch
            val loaded = runCatching { withContext(Dispatchers.IO) { readWav(generation.wavPath) } }
            loaded.onSuccess { wav ->
                playSamples(wav.samples, wav.sampleRate, generationId)
            }.onFailure { throwable ->
                _uiState.update { it.copy(error = throwable.message ?: "Could not play history item") }
            }
        }
    }

    fun deleteGeneration(generation: GenerationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteGeneration(generation)
            runCatching { File(generation.wavPath).delete() }
        }
    }

    fun playAudio() {
        val samples = generatedAudio ?: return
        playSamples(samples, _uiState.value.sampleRate)
    }

    private fun playSamples(samples: FloatArray, sampleRate: Int, historyGenerationId: Long? = null) {
        stopAudio()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeTrack = track
            _uiState.update { it.copy(playing = true, playingGenerationId = historyGenerationId) }
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val durationMillis = (samples.size.toDouble() / sampleRate.toDouble() * 1000.0).toLong()
            kotlinx.coroutines.delay(durationMillis.coerceAtLeast(200L))
            track.release()
            if (activeTrack == track) activeTrack = null
            _uiState.update { it.copy(playing = false, playingGenerationId = null) }
        }
    }

    fun stopAudio() {
        playJob?.cancel()
        playJob = null
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
        _uiState.update { it.copy(playing = false, playingGenerationId = null) }
    }

    fun suggestedExportFileName(generation: GenerationEntity? = null): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        val timestamp = LocalDateTime.now().format(formatter)
        return if (generation == null) "qwen3-tts-$timestamp.wav"
        else "qwen3-tts-export-$timestamp-${generation.generationId}.wav"
    }

    fun savePreviewToUri(uri: Uri) {
        exportWavToUri(previewWav.baseFile, uri)
    }

    fun exportGenerationToUri(generation: GenerationEntity, uri: Uri) {
        exportWavToUri(File(generation.wavPath), uri)
    }

    private fun exportWavToUri(source: File, uri: Uri) {
        if (_uiState.value.busy) return
        startBusy(status = "Saving WAV")
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    source.inputStream().use { input ->
                        resolver.openOutputStream(uri, "wt")?.use { output ->
                            input.copyTo(output)
                        } ?: error("Could not open media output")
                    }
                }
            }
            stopBusyTicker()
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            snackbarMessage = "WAV saved",
                            status = "WAV saved",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "Save failed",
                            snackbarMessage = throwable.message ?: "Could not save WAV",
                            error = null,
                        )
                    }
                },
            )
        }
    }

    private fun ensureEngineLoaded() {
        val current = _uiState.value
        if (current.loaded) return
        val backend = backendOptionById(current.selectedBackendId)
        val threads = current.selectedCpuThreads
        engine.load(
            modelPath = File(modelDir, QwenModel.talkerName).absolutePath,
            mmprojPath = File(modelDir, QwenModel.mmprojName).absolutePath,
            nativeLibraryDir = getApplication<Application>().applicationInfo.nativeLibraryDir,
            vulkan = backend.vulkan,
            threads = threads,
        )
        _uiState.update {
            it.copy(loaded = true, activeBackendName = backend.label)
        }
    }

    private fun preferredCpuThreadCount(): Int {
        val available = Runtime.getRuntime().availableProcessors()
        return when {
            available >= 8 -> 6
            available >= 4 -> 4
            else -> available.coerceAtLeast(1)
        }
    }

    private fun startBusy(
        status: String,
        downloading: Boolean = false,
        synthesisEstimate: GenerationTimeEstimate? = null,
    ) {
        operationTickerJob?.cancel()
        val start = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                busy = true,
                downloading = downloading,
                error = null,
                status = status,
                operationElapsedMillis = 0L,
                estimatedSynthesisMillis = synthesisEstimate?.totalMillis,
                estimateSampleCount = synthesisEstimate?.sampleSize ?: 0,
            )
        }
        operationTickerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(250L)
                val elapsed = SystemClock.elapsedRealtime() - start
                _uiState.update { current ->
                    if (current.busy) current.copy(operationElapsedMillis = elapsed) else current
                }
            }
        }
    }

    private fun stopBusyTicker() {
        operationTickerJob?.cancel()
        operationTickerJob = null
    }

    private fun estimateSynthesisMillis(text: String): GenerationTimeEstimate? {
        val ratios = generations.value
            .asSequence()
            .filter { it.synthesisMillis > 0L && it.text.isNotBlank() }
            .take(20)
            .map { generation ->
                generation.synthesisMillis.toDouble() / generation.text.length.coerceAtLeast(1).toDouble()
            }
            .filter { it.isFinite() && it > 0.0 }
            .sorted()
            .toList()

        if (ratios.size < 3) return null

        val trimmed = if (ratios.size >= 5) {
            ratios.drop(1).dropLast(1)
        } else {
            ratios
        }
        val millisPerCharacter = trimmed.average()
        val estimate = (millisPerCharacter * text.length.coerceAtLeast(1)).toLong()
            .coerceAtLeast(1_000L)
        return GenerationTimeEstimate(estimate, ratios.size)
    }

    private fun refreshModelState(status: String? = null) {
        val ready = isModelReady()
        stopBusyTicker()
        _uiState.update {
            it.copy(
                modelReady = ready,
                busy = false,
                downloading = false,
                downloadTotalBytes = QwenModel.totalBytes,
                status = status ?: if (ready) "Model ready" else "Model not downloaded",
            )
        }
    }

    private fun isModelReady(): Boolean =
        QwenModel.files.all { file ->
            val local = File(modelDir, file.name)
            local.isFile && local.length() == file.sizeBytes
        }

    private fun downloadFiles() {
        var completedBytes = 0L
        QwenModel.files.forEachIndexed { index, file ->
            val target = File(modelDir, file.name)
            if (target.isFile && target.length() == file.sizeBytes) {
                completedBytes += file.sizeBytes
                return@forEachIndexed
            }
            val temp = File(modelDir, "${file.name}.download")
            val connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            try {
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileBytes = 0L
                        var lastUpdate = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            fileBytes += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 250L) {
                                lastUpdate = now
                                val total = completedBytes + fileBytes
                                _uiState.update {
                                    it.copy(
                                        downloadBytes = total,
                                        downloadProgress = total.toFloat() / QwenModel.totalBytes.toFloat(),
                                        status = "Downloading ${index + 1}/${QwenModel.files.size}: ${file.name}",
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (temp.length() != file.sizeBytes) {
                temp.delete()
                error("Downloaded ${file.name} has the wrong size")
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                error("Could not replace ${file.name}")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                error("Could not save ${file.name}")
            }
            completedBytes += file.sizeBytes
        }
    }

    private fun writePreviewWav(samples: FloatArray, sampleRate: Int) {
        val stream = previewWav.startWrite()
        try {
            val buffered = stream.buffered()
            writeWav(buffered, samples, sampleRate)
            buffered.flush()
            previewWav.finishWrite(stream)
        } catch (failure: Throwable) {
            previewWav.failWrite(stream)
            throw failure
        }
    }

    private fun makeUniqueVoiceName(name: String): String {
        val existing = voices.value.map { it.name }.toSet()
        if (name !in existing) return name
        var index = 2
        while ("$name $index" in existing) index += 1
        return "$name $index"
    }

    private fun writeWav(output: OutputStream, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        output.writeAscii("RIFF")
        output.writeIntLe(36 + dataBytes)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(sampleRate)
        output.writeIntLe(sampleRate * 2)
        output.writeShortLe(2)
        output.writeShortLe(16)
        output.writeAscii("data")
        output.writeIntLe(dataBytes)
        samples.forEach { sample ->
            val clamped = sample.coerceIn(-1f, 1f)
            output.writeShortLe((clamped * 32767f).toInt())
        }
    }

    override fun onCleared() {
        recorder.cancel()
        stopAudio()
        stopBusyTicker()
        // A native call can still be finishing after viewModelScope is cancelled.
        CoroutineScope(Dispatchers.IO).launch { engine.close() }
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            QwenTtsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    QwenTtsApp()
                }
            }
        }
    }
}

private sealed class AppDestination(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
) {
    object Studio : AppDestination("studio", "Studio", { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) })
    object Voices : AppDestination("voices", "Voices", { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) })
    object History : AppDestination("history", "History", { Icon(Icons.Default.History, contentDescription = null) })
    object Settings : AppDestination("settings", "Settings", { Icon(Icons.Default.Settings, contentDescription = null) })
}

private val appDestinations = listOf(
    AppDestination.Studio,
    AppDestination.Voices,
    AppDestination.History,
    AppDestination.Settings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QwenTtsApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.Studio.route
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val message = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearSnackbarMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                appDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(AppDestination.Studio.route) { saveState = true }
                            }
                        },
                        icon = destination.icon,
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Studio.route,
            modifier = Modifier.padding(padding),
            enterTransition = {
                val direction = if (destinationIndex(targetState.destination.route) >= destinationIndex(initialState.destination.route)) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                slideIntoContainer(direction, animationSpec = tween(260))
            },
            exitTransition = {
                val direction = if (destinationIndex(targetState.destination.route) >= destinationIndex(initialState.destination.route)) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                slideOutOfContainer(direction, animationSpec = tween(260))
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(260))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(260))
            },
        ) {
            composable(AppDestination.Studio.route) {
                StudioScreen(viewModel)
            }
            composable(AppDestination.Voices.route) {
                VoicesScreen(viewModel)
            }
            composable(AppDestination.History.route) {
                HistoryScreen(viewModel)
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(viewModel)
            }
        }
    }
}

private fun destinationIndex(route: String?): Int =
    appDestinations.indexOfFirst { it.route == route }.takeIf { it >= 0 } ?: 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val voices by viewModel.voices.collectAsState()
    var showVoicePicker by remember { mutableStateOf(false) }
    val selectedVoiceName = voices.firstOrNull { it.voiceId == state.selectedVoiceId }?.name ?: "Default Voice"
    val saveWavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) viewModel.savePreviewToUri(uri)
    }

    if (showVoicePicker) {
        VoicePickerSheet(
            voices = voices,
            selectedVoiceId = state.selectedVoiceId,
            onDismiss = { showVoicePicker = false },
            onVoiceSelected = { voiceId ->
                viewModel.selectVoice(voiceId)
                showVoicePicker = false
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Text to speech", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            ComposerPanel(
                state = state,
                selectedVoiceName = selectedVoiceName,
                onTextChange = viewModel::updateText,
                onVoicePickerClick = { if (!state.busy) showVoicePicker = true },
                onLanguageChange = viewModel::updateLanguage,
            )
            if ((state.busy && !state.downloading) || state.sampleCount > 0) {
                ResultPanel(
                    state = state,
                    onPlay = viewModel::playAudio,
                    onStop = viewModel::stopAudio,
                    onSave = { saveWavLauncher.launch(viewModel.suggestedExportFileName()) },
                )
            }
        }

        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Button(
                onClick = viewModel::synthesize,
                enabled = state.modelReady && state.text.isNotBlank() && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.busy && !state.downloading) state.status else "Generate")
            }
        }
    }
}

@Composable
private fun VoicesScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val voices by viewModel.voices.collectAsState()
    val recorderState by viewModel.recorderState.collectAsState()
    val context = LocalContext.current
    var voiceName by remember { mutableStateOf("") }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) viewModel.startVoiceRecording()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importVoice(voiceName, uri)
            voiceName = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text("New Voice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = voiceName,
                    onValueChange = { voiceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                )
                if (recorderState.isRecording) {
                    LinearProgressIndicator(
                        progress = { recorderState.level.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Recording ${formatDuration(recorderState.elapsedMillis)}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (recorderState.isRecording) {
                                viewModel.stopVoiceRecordingAndCreate(voiceName)
                                voiceName = ""
                            } else if (hasPermission) {
                                viewModel.startVoiceRecording()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !state.busy || recorderState.isRecording,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (recorderState.isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (recorderState.isRecording) "Stop & Create" else "Record")
                    }
                    OutlinedButton(
                        onClick = viewModel::cancelVoiceRecording,
                        enabled = recorderState.isRecording,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("audio/*")) },
                    enabled = !state.busy && !recorderState.isRecording,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import WAV")
                }
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Voices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (voices.isEmpty()) {
                    Text("No saved voices yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    voices.forEach { voice ->
                        VoiceRow(
                            voice = voice,
                            selected = state.selectedVoiceId == voice.voiceId,
                            onSelect = { viewModel.selectVoice(voice.voiceId) },
                            onDelete = { viewModel.deleteVoice(voice) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val generations by viewModel.generations.collectAsState()
    var pendingExport by remember { mutableStateOf<GenerationEntity?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        val generation = pendingExport
        pendingExport = null
        if (uri != null && generation != null) {
            viewModel.exportGenerationToUri(generation, uri)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (generations.isEmpty()) {
            item {
                Text("No history items.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(generations, key = { it.generationId }) { generation ->
            val isPlayingThis = state.playing && state.playingGenerationId == generation.generationId
            var menuExpanded by remember(generation.generationId) { mutableStateOf(false) }
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(generation.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${generation.voiceName} · ${formatSeconds(generation.sampleCount.toDouble() / generation.sampleRate.coerceAtLeast(1))} · ${formatDate(generation.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            if (isPlayingThis) {
                                viewModel.stopAudio()
                            } else {
                                viewModel.playGeneration(generation.generationId)
                            }
                        },
                    ) {
                        Icon(
                            if (isPlayingThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPlayingThis) "Stop" else "Play")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Export") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    pendingExport = generation
                                    exportLauncher.launch(viewModel.suggestedExportFileName(generation))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.deleteGeneration(generation)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        ModelPanel(
            state = state,
            onDownload = viewModel::downloadModel,
            onLoad = viewModel::loadModel,
            onUnload = viewModel::unloadModel,
        )
        RuntimePanel(
            state = state,
            onBackendChange = viewModel::updateBackend,
            onCpuThreadsChange = viewModel::updateCpuThreads,
        )
    }
}

@Composable
private fun ComposerPanel(
    state: QwenTtsUiState,
    selectedVoiceName: String,
    onTextChange: (String) -> Unit,
    onVoicePickerClick: () -> Unit,
    onLanguageChange: (Int) -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 10,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                label = { Text("Text") },
            )
            OutlinedButton(
                onClick = onVoicePickerClick,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text("Voice", style = MaterialTheme.typography.labelMedium)
                    Text(selectedVoiceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LanguageDropdown(
                selectedLanguageId = state.selectedLanguageId,
                enabled = !state.busy,
                onLanguageChange = onLanguageChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerSheet(
    voices: List<VoiceProfileEntity>,
    selectedVoiceId: String?,
    onDismiss: () -> Unit,
    onVoiceSelected: (String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Choose voice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            VoicePickerItem(
                title = "Default Voice",
                subtitle = "Use the built-in speaker",
                selected = selectedVoiceId == null,
                onClick = { onVoiceSelected(null) },
            )
            voices.forEach { voice ->
                VoicePickerItem(
                    title = voice.name,
                    subtitle = "${formatDuration(voice.durationMillis)} reference",
                    selected = selectedVoiceId == voice.voiceId,
                    onClick = { onVoiceSelected(voice.voiceId) },
                )
            }
        }
    }
}

@Composable
private fun VoicePickerItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Icon(Icons.Default.RecordVoiceOver, contentDescription = "Selected", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceProfileEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilterChip(selected = selected, onClick = onSelect, label = { Text(if (selected) "Active" else "Use") })
        Column(Modifier.weight(1f)) {
            Text(voice.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDuration(voice.durationMillis)} reference · ${formatDate(voice.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selectedLanguageId: Int,
    enabled: Boolean,
    onLanguageChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = languageOptions.firstOrNull { it.id == selectedLanguageId } ?: languageOptions.first()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            label = { Text("Language") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languageOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onLanguageChange(option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelPanel(
    state: QwenTtsUiState,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(QwenModel.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Q4_K_M + Q8_0 · ${formatBytes(QwenModel.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(onClick = {}, label = { Text(if (state.modelReady) "Ready" else "Missing") })
            }

            if (state.downloading) {
                LinearProgressIndicator(progress = { state.downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(state.downloadBytes)} / ${formatBytes(state.downloadTotalBytes)}", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDownload,
                    enabled = !state.busy && !state.modelReady,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.modelReady) "Model ready" else "Download model")
                }
                OutlinedButton(
                    onClick = if (state.loaded) onUnload else onLoad,
                    enabled = state.modelReady && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.loaded) "Unload" else "Load")
                }
            }
        }
    }
}

@Composable
private fun RuntimePanel(
    state: QwenTtsUiState,
    onBackendChange: (String) -> Unit,
    onCpuThreadsChange: (Int) -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Text("Performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                state.activeBackendName?.let { "Loaded: $it" } ?: "Model unloaded",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                backendOptions.forEach { backend ->
                    FilterChip(
                        selected = state.selectedBackendId == backend.id,
                        onClick = { onBackendChange(backend.id) },
                        enabled = !state.busy,
                        label = { Text(backend.label) },
                    )
                }
            }
            Text(
                "Vulkan runs the vocoder on the GPU. Voice and speech encoding run on the CPU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6).forEach { threads ->
                    FilterChip(
                        selected = state.selectedCpuThreads == threads,
                        onClick = { onCpuThreadsChange(threads) },
                        enabled = !state.busy,
                        label = { Text("$threads threads") },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(state: QwenTtsUiState, onPlay: () -> Unit, onStop: () -> Unit, onSave: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.busy && !state.downloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                GenerationStats(state)
            } else {
                if (state.sampleCount > 0 && state.sampleRate > 0) {
                    Text(
                        "Audio ${formatSeconds(state.sampleCount.toDouble() / state.sampleRate.toDouble())} · generated in ${formatDuration(state.synthesisMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { if (state.playing) onStop() else onPlay() },
                    enabled = state.sampleCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(
                        if (state.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.playing) "Stop" else "Play")
                }
                OutlinedButton(
                    onClick = onSave,
                    enabled = state.sampleCount > 0 && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save WAV")
                }
            }
        }
    }
}

@Composable
private fun GenerationStats(state: QwenTtsUiState) {
    val remainingMillis = state.estimatedSynthesisMillis
        ?.minus(state.operationElapsedMillis)
        ?.coerceAtLeast(0L)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Elapsed ${formatDuration(state.operationElapsedMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (remainingMillis != null && state.estimateSampleCount >= 3) {
            Text(
                "Estimated remaining ${formatDuration(remainingMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class WavData(val samples: FloatArray, val sampleRate: Int)

private fun readWav(path: String): WavData {
    val bytes = File(path).readBytes()
    require(bytes.size > 44) { "WAV file is empty" }
    fun intLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    fun shortLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    var cursor = 12
    var sampleRate = 24_000
    var channels = 1
    var bits = 16
    var dataOffset = -1
    var dataSize = 0
    while (cursor + 8 <= bytes.size) {
        val id = String(bytes, cursor, 4, Charsets.US_ASCII)
        val size = intLe(cursor + 4)
        val payload = cursor + 8
        when (id) {
            "fmt " -> {
                channels = shortLe(payload + 2).coerceAtLeast(1)
                sampleRate = intLe(payload + 4)
                bits = shortLe(payload + 14)
            }
            "data" -> {
                dataOffset = payload
                dataSize = size
                break
            }
        }
        cursor = payload + size
    }
    require(dataOffset >= 0 && bits == 16) { "Only PCM16 WAV history items are supported" }
    val frameBytes = channels * 2
    val frameCount = dataSize / frameBytes
    val samples = FloatArray(frameCount)
    var offset = dataOffset
    for (i in 0 until frameCount) {
        var sum = 0f
        for (channel in 0 until channels) {
            val value = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort()
            sum += value / 32768f
            offset += 2
        }
        samples[i] = sum / channels
    }
    return WavData(samples, sampleRate)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return "%.1f %s".format(value, units[unitIndex])
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "%d:%02d".format(Locale.US, minutes, seconds) else "%ds".format(Locale.US, seconds)
}

private fun formatSeconds(seconds: Double): String =
    "%.1fs".format(Locale.US, seconds.coerceAtLeast(0.0))

private fun formatDate(epochMillis: Long): String {
    val date = java.text.SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault())
    return date.format(java.util.Date(epochMillis))
}

private fun backendOptionById(id: String?): BackendOption =
    backendOptions.firstOrNull { it.id == id } ?: backendOptions.first()

private fun OutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun OutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
    write((value shr 16) and 0xff)
    write((value shr 24) and 0xff)
}

private fun OutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
}
