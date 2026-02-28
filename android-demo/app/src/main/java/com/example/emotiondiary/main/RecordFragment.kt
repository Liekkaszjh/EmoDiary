package com.example.emotiondiary.main

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.emotiondiary.R
import com.example.emotiondiary.databinding.FragmentRecordBinding
import com.example.emotiondiary.ser.LocalAsrEngine
import com.example.emotiondiary.ser.LocalDiaryRecord
import com.example.emotiondiary.ser.LocalRecordStore
import com.example.emotiondiary.ser.LocalSerEngine
import com.example.emotiondiary.ser.WavPcmPlayer
import com.example.emotiondiary.ser.WavRecorder
import com.example.emotiondiary.storage.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RecordFragment : Fragment() {
    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private lateinit var recordStore: LocalRecordStore
    private lateinit var session: SessionManager
    private lateinit var serEngine: LocalSerEngine
    private lateinit var asrEngine: LocalAsrEngine
    private val wavPlayer = WavPcmPlayer(16000)

    private var wavRecorder: WavRecorder? = null
    private var currentFile: File? = null
    private var recording = false
    private var peakAmplitude = 0

    private val todayRecords = mutableListOf<LocalDiaryRecord>()

    private var currentPlayingId: String? = null
    private var currentPlayingBtn: ImageButton? = null
    private var currentPlayingBar: ProgressBar? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleRecord() else binding.tvRecordStatus.text = getString(R.string.no_mic_permission)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        session = SessionManager(requireContext())
        recordStore = LocalRecordStore(requireContext(), session.getLocalScope())
        serEngine = LocalSerEngine(requireContext())
        asrEngine = LocalAsrEngine(requireContext())

        binding.btnMic.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) requestPermission.launch(Manifest.permission.RECORD_AUDIO) else toggleRecord()
        }
        loadTodayRecords()
    }

    private fun toggleRecord() {
        if (recording) stopRecord() else startRecord()
    }

    private fun startRecord() {
        stopPlayback(shouldRenderList = false)
        val dir = File(requireContext().filesDir, "local_records_${session.getLocalScope()}").apply { mkdirs() }
        val file = File(dir, "rec_${System.currentTimeMillis()}.wav")
        val recorder = WavRecorder(sampleRate = 16000)
        val ok = recorder.start(file)
        if (!ok) {
            binding.tvLatestResult.text = getString(R.string.record_start_failed)
            return
        }
        wavRecorder = recorder
        currentFile = file
        recording = true
        binding.tvRecordStatus.text = getString(R.string.record_status_recording)
        binding.waveProgress.visibility = View.VISIBLE
    }

    private fun stopRecord() {
        val recorder = wavRecorder ?: return
        val file = recorder.stop()
        peakAmplitude = recorder.peakAmplitude
        wavRecorder = null
        recording = false

        binding.tvRecordStatus.text = getString(R.string.record_status_not_recording_peak, peakAmplitude)
        binding.waveProgress.visibility = View.GONE

        lifecycleScope.launch {
            if (file == null || !file.exists()) {
                binding.tvLatestResult.text = getString(R.string.no_audio_generated)
                return@launch
            }
            val transcript = withContext(Dispatchers.Default) { asrEngine.transcribe(file) }

            if (peakAmplitude < 300) {
                binding.tvLatestResult.text = getString(R.string.audio_silent, transcript)
                return@launch
            }
            binding.tvLatestResult.text = getString(R.string.running_ser_asr)
            val out = withContext(Dispatchers.Default) { serEngine.infer(file) }
            val probsText = out.probs.entries.joinToString(", ") { "${it.key}:${"%.2f".format(it.value)}" }
            binding.tvLatestResult.text = getString(
                R.string.record_result,
                emotionLabelCn(out.label),
                transcript,
                probsText
            )

            val rec = LocalDiaryRecord(
                id = UUID.randomUUID().toString(),
                createdAtMillis = System.currentTimeMillis(),
                audioPath = file.absolutePath,
                emotionLabel = out.label,
                transcript = transcript
            )
            recordStore.add(rec)
            loadTodayRecords()
        }
    }

    private fun loadTodayRecords() {
        todayRecords.clear()
        todayRecords.addAll(recordStore.loadToday())
        renderTodayRecords()
    }

    private fun renderTodayRecords() {
        val container = binding.todayRecordsContainer
        container.removeAllViews()

        if (todayRecords.isEmpty()) {
            val tv = TextView(requireContext()).apply { text = getString(R.string.no_records_today) }
            container.addView(tv)
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        todayRecords.forEach { rec ->
            val row = inflater.inflate(R.layout.item_today_record, container, false)
            row.background = createDiaryCardBackground(rec.emotionLabel)
            val tvEmotion = row.findViewById<TextView>(R.id.tvEmotion)
            val tvTime = row.findViewById<TextView>(R.id.tvTime)
            val tvTranscript = row.findViewById<TextView>(R.id.tvTranscript)
            val btnPlayItem = row.findViewById<ImageButton>(R.id.btnPlayItem)
            val progress = row.findViewById<ProgressBar>(R.id.progressAudio)

            tvEmotion.text = getString(R.string.emotion_label, emotionLabelCn(rec.emotionLabel))
            tvTime.text = getString(R.string.time_label, formatTime(rec.createdAtMillis))
            tvTranscript.text = getString(R.string.transcript_label, rec.transcript)
            progress.progress = 0
            setPlayButtonState(btnPlayItem, currentPlayingId == rec.id && wavPlayer.isPlaying)

            btnPlayItem.setOnClickListener {
                if (!File(rec.audioPath).exists()) {
                    binding.tvLatestResult.text = getString(R.string.audio_missing, rec.audioPath)
                    return@setOnClickListener
                }
                if (currentPlayingId == rec.id && wavPlayer.isPlaying) {
                    stopPlayback(shouldRenderList = true)
                } else {
                    stopPlayback(shouldRenderList = false)
                    startPlayback(rec.audioPath, rec.id, btnPlayItem, progress)
                }
            }
            container.addView(row)
        }
    }

    private fun startPlayback(
        filePath: String,
        recordId: String,
        playBtn: ImageButton,
        progress: ProgressBar
    ) {
        val file = File(filePath)
        if (!file.exists()) {
            binding.tvLatestResult.text = getString(R.string.audio_missing, filePath)
            return
        }
        wavPlayer.play(file, object : WavPcmPlayer.Listener {
            override fun onStart(durationMs: Int) {
                uiHandler.post {
                    currentPlayingId = recordId
                    currentPlayingBtn = playBtn
                    currentPlayingBar = progress
                    currentPlayingBtn?.let { setPlayButtonState(it, true) }
                    currentPlayingBar?.progress = 0
                }
            }

            override fun onProgress(positionMs: Int, durationMs: Int) {
                uiHandler.post {
                    if (durationMs > 0) {
                        currentPlayingBar?.progress =
                            (positionMs * 1000L / durationMs).toInt().coerceIn(0, 1000)
                    }
                }
            }

            override fun onComplete() {
                uiHandler.post {
                    clearPlaybackUi(shouldRenderList = true)
                    if (!recording) {
                        binding.tvRecordStatus.text = getString(R.string.record_status_not_recording_peak, peakAmplitude)
                    }
                }
            }

            override fun onError(message: String) {
                uiHandler.post {
                    binding.tvLatestResult.text = getString(R.string.playback_failed, message)
                    clearPlaybackUi(shouldRenderList = true)
                }
            }
        })
    }

    private fun stopPlayback(shouldRenderList: Boolean) {
        wavPlayer.stop()
        clearPlaybackUi(shouldRenderList)
    }

    private fun clearPlaybackUi(shouldRenderList: Boolean) {
        currentPlayingBtn?.let { setPlayButtonState(it, false) }
        currentPlayingBar?.progress = 0
        currentPlayingBtn = null
        currentPlayingBar = null
        currentPlayingId = null
        if (shouldRenderList) {
            renderTodayRecords()
        }
    }

    private fun formatTime(ms: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    private fun emotionLabelCn(label: String): String {
        return when (label) {
            "Neutral" -> getString(R.string.emotion_neutral)
            "Sad" -> getString(R.string.emotion_sad)
            "Angry" -> getString(R.string.emotion_angry)
            "Happy" -> getString(R.string.emotion_happy)
            "Fear" -> getString(R.string.emotion_fear)
            "Surprise" -> getString(R.string.emotion_surprise)
            else -> label
        }
    }

    private fun emotionColor(label: String): Int {
        return when (label) {
            "Neutral" -> ContextCompat.getColor(requireContext(), R.color.neutral)
            "Sad" -> ContextCompat.getColor(requireContext(), R.color.sad)
            "Angry" -> ContextCompat.getColor(requireContext(), R.color.angry)
            "Happy" -> ContextCompat.getColor(requireContext(), R.color.happy)
            "Fear" -> ContextCompat.getColor(requireContext(), R.color.fear)
            "Surprise" -> ContextCompat.getColor(requireContext(), R.color.surprise)
            else -> ContextCompat.getColor(requireContext(), R.color.neutral)
        }
    }

    private fun createDiaryCardBackground(label: String): GradientDrawable {
        val base = emotionColor(label)
        val fill = adjustAlpha(base, 0.16f)
        val stroke = adjustAlpha(base, 0.45f)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f
            setColor(fill)
            setStroke(dp(1), stroke)
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private fun setPlayButtonState(button: ImageButton, isPlaying: Boolean) {
        button.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        button.contentDescription = getString(if (isPlaying) R.string.stop else R.string.play)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        wavRecorder?.stop()
        wavRecorder = null
        stopPlayback(shouldRenderList = false)
        asrEngine.release()
        _binding = null
    }
}
