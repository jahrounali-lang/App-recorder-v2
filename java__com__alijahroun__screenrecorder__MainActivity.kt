package com.alijahroun.screenrecorder

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var settings: RecorderSettings
    private var caps: DeviceCaps? = null

    private var resOptions: List<ResolutionChoice> = listOf(ResolutionChoice.AUTO)
    private var fpsOptions: List<Int> = listOf(0)

    private lateinit var swAuto: MaterialSwitch
    private lateinit var tilRes: TextInputLayout
    private lateinit var tilFps: TextInputLayout
    private lateinit var acRes: MaterialAutoCompleteTextView
    private lateinit var acFps: MaterialAutoCompleteTextView
    private lateinit var acAudio: MaterialAutoCompleteTextView
    private lateinit var btnRecord: MaterialButton
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: RecordingsAdapter

    private var pendingNoAudio = false
    private var minimizeOnStart = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                minimizeOnStart = true
                val intent = Intent(this, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra(RecorderService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(RecorderService.EXTRA_RESULT_DATA, data)
                    .putExtra(RecorderService.EXTRA_NO_AUDIO, pendingNoAudio)
                ContextCompat.startForegroundService(this, intent)
            } else {
                toast("Screen capture permission was not granted")
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == false) {
                pendingNoAudio = true
                toast("Microphone permission denied - recording without audio")
            }
            launchProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        store = SettingsStore(this)
        settings = store.load()
        caps = try {
            CaptureProfile.probe(this)
        } catch (e: Exception) {
            null
        }

        swAuto = findViewById(R.id.swAuto)
        tilRes = findViewById(R.id.tilRes)
        tilFps = findViewById(R.id.tilFps)
        acRes = findViewById(R.id.acRes)
        acFps = findViewById(R.id.acFps)
        acAudio = findViewById(R.id.acAudio)
        btnRecord = findViewById(R.id.btnRecord)
        tvEmpty = findViewById(R.id.tvEmpty)

        setupSettingsUi()
        setupList()

        btnRecord.setOnClickListener { onRecordClicked() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var previous = RecorderStatus.Phase.IDLE
                    RecorderStatus.phase.collect { phase ->
                        renderPhase(phase)
                        if (phase == RecorderStatus.Phase.RECORDING &&
                            previous == RecorderStatus.Phase.IDLE && minimizeOnStart
                        ) {
                            minimizeOnStart = false
                            moveTaskToBack(true)
                        }
                        if (phase == RecorderStatus.Phase.IDLE &&
                            previous != RecorderStatus.Phase.IDLE
                        ) {
                            refreshRecordings()
                        }
                        previous = phase
                    }
                }
                launch { RecorderStatus.events.collect { toast(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecordings()
    }

    // ---------------------------------------------------------------- settings UI

    private fun setupSettingsUi() {
        val c = caps
        val info = findViewById<TextView>(R.id.tvInfo)
        if (c == null) {
            info.text = "No H.264 encoder found - recording is not available on this device."
            btnRecord.isEnabled = false
        } else {
            val kind = if (c.isHardware) "Hardware" else "Software"
            info.text = "Screen ${c.screenWidth}×${c.screenHeight} • up to " +
                "${c.refreshHz.roundToInt()} Hz • $kind H.264 encoder"
            resOptions = CaptureProfile.resolutionOptions(c)
            fpsOptions = CaptureProfile.fpsOptions(c)
        }

        // Resolution
        if (settings.resolution !in resOptions) settings = settings.copy(resolution = ResolutionChoice.AUTO)
        acRes.setSimpleItems(resOptions.map { label(it) }.toTypedArray())
        acRes.setText(label(settings.resolution), false)
        acRes.setOnItemClickListener { _, _, pos, _ ->
            settings = settings.copy(resolution = resOptions[pos])
            store.save(settings)
        }

        // FPS
        if (settings.fps !in fpsOptions) settings = settings.copy(fps = 0)
        acFps.setSimpleItems(fpsOptions.map { fpsLabel(it) }.toTypedArray())
        acFps.setText(fpsLabel(settings.fps), false)
        acFps.setOnItemClickListener { _, _, pos, _ ->
            settings = settings.copy(fps = fpsOptions[pos])
            store.save(settings)
        }

        // Audio
        val audioModes = AudioMode.values().toList()
        acAudio.setSimpleItems(audioModes.map { it.label }.toTypedArray())
        acAudio.setText(settings.audio.label, false)
        acAudio.setOnItemClickListener { _, _, pos, _ ->
            settings = settings.copy(audio = audioModes[pos])
            store.save(settings)
        }

        // Automatic mode
        swAuto.isChecked = settings.automatic
        updateManualEnabled()
        swAuto.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(automatic = checked)
            store.save(settings)
            updateManualEnabled()
        }
    }

    private fun updateManualEnabled() {
        tilRes.isEnabled = !settings.automatic
        tilFps.isEnabled = !settings.automatic
        acRes.isEnabled = !settings.automatic
        acFps.isEnabled = !settings.automatic
    }

    private fun label(choice: ResolutionChoice): String {
        val c = caps ?: return choice.label
        return CaptureProfile.resolutionLabel(choice, c)
    }

    private fun fpsLabel(fps: Int) = if (fps == 0) "Automatic" else "$fps FPS"

    private fun renderPhase(phase: RecorderStatus.Phase) {
        when (phase) {
            RecorderStatus.Phase.IDLE -> {
                btnRecord.isEnabled = caps != null
                btnRecord.setText(R.string.start_recording)
                btnRecord.setIconResource(R.drawable.ic_record)
            }
            RecorderStatus.Phase.RECORDING, RecorderStatus.Phase.PAUSED -> {
                btnRecord.isEnabled = true
                btnRecord.setText(R.string.stop_recording)
                btnRecord.setIconResource(R.drawable.ic_stop)
            }
            RecorderStatus.Phase.SAVING -> {
                btnRecord.isEnabled = false
                btnRecord.setText(R.string.saving)
            }
        }
    }

    // ---------------------------------------------------------------- recording flow

    private fun onRecordClicked() {
        when (RecorderStatus.phase.value) {
            RecorderStatus.Phase.RECORDING, RecorderStatus.Phase.PAUSED ->
                startService(
                    Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
                )

            RecorderStatus.Phase.IDLE -> {
                pendingNoAudio = false
                val missing = ArrayList<String>()
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    missing += Manifest.permission.POST_NOTIFICATIONS
                }
                if (settings.audio != AudioMode.NONE && !granted(Manifest.permission.RECORD_AUDIO)) {
                    missing += Manifest.permission.RECORD_AUDIO
                }
                if (missing.isEmpty()) launchProjection() else permissionLauncher.launch(missing.toTypedArray())
            }

            RecorderStatus.Phase.SAVING -> Unit
        }
    }

    private fun launchProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Ask for the full screen (not a single app window).
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- recordings list

    private fun setupList() {
        adapter = RecordingsAdapter(
            onOpen = { r ->
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(r.uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(i)
                } catch (e: Exception) {
                    toast("No app can play this video")
                }
            },
            onShare = { r ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, r.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, r.name))
            },
            onDelete = { r ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_title)
                    .setMessage(r.name)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        try {
                            contentResolver.delete(r.uri, null, null)
                        } catch (e: Exception) {
                            toast("Could not delete: ${e.message}")
                        }
                        refreshRecordings()
                    }
                    .show()
            }
        )
        val rv = findViewById<RecyclerView>(R.id.rvRecordings)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    private fun refreshRecordings() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    queryRecordings()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            adapter.submit(list)
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun queryRecordings(): List<Recording> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("Movies/ScreenRecordings%")
        val out = ArrayList<Recording>()
        contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext() && out.size < 100) {
                val id = c.getLong(idCol)
                out += Recording(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "recording.mp4",
                    dateMs = c.getLong(dateCol) * 1000,
                    durationMs = c.getLong(durCol),
                    size = c.getLong(sizeCol)
                )
            }
        }
        return out
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
