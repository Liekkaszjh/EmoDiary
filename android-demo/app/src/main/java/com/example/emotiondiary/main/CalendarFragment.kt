package com.example.emotiondiary.main

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.emotiondiary.R
import com.example.emotiondiary.data.DiaryItem
import com.example.emotiondiary.databinding.FragmentCalendarBinding
import com.example.emotiondiary.network.ApiService
import com.example.emotiondiary.network.RetrofitClient
import com.example.emotiondiary.ser.LocalDiaryRecord
import com.example.emotiondiary.ser.LocalRecordStore
import com.example.emotiondiary.ser.WavPcmPlayer
import com.example.emotiondiary.storage.SessionManager
import com.prolificinteractive.materialcalendarview.CalendarDay
import com.prolificinteractive.materialcalendarview.DayViewDecorator
import com.prolificinteractive.materialcalendarview.DayViewFacade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

class CalendarFragment : Fragment() {
    private var _binding: FragmentCalendarBinding? = null
    private val binding get() = _binding!!

    private lateinit var recordStore: LocalRecordStore
    private lateinit var session: SessionManager
    private lateinit var api: ApiService

    private var diaries: MutableList<LocalDiaryRecord> = mutableListOf()
    private var selectedDateKey: String = LocalDate.now().toString()
    private var selectedCalendarDay: CalendarDay = CalendarDay.today()
    private var selectedDayDecorator: SelectedDayCircleDecorator? = null

    private val wavPlayer = WavPcmPlayer(16000)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var currentPlayingId: String? = null
    private var currentPlayButton: ImageButton? = null
    private var currentProgressBar: ProgressBar? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        session = SessionManager(requireContext())
        recordStore = LocalRecordStore(requireContext(), session.getLocalScope())
        api = RetrofitClient.api(session)

        val today = LocalDate.now()
        selectedCalendarDay = CalendarDay.from(today.year, today.monthValue, today.dayOfMonth)
        binding.calendarView.selectedDate = selectedCalendarDay
        binding.calendarView.setSelectionColor(Color.TRANSPARENT)
        updateSelectedDayDecorator()

        binding.calendarView.setOnDateChangedListener { _, date, _ ->
            selectedCalendarDay = date
            selectedDateKey = "%04d-%02d-%02d".format(date.year, date.month, date.day)
            updateSelectedDayDecorator()
            renderSelectedDay()
        }
        binding.btnSyncCloud.setOnClickListener { confirmSyncToCloud() }
    }

    override fun onResume() {
        super.onResume()
        loadCalendar()
    }

    private fun loadCalendar() {
        diaries = recordStore.loadAll()
        refreshCalendarDecorators()
        renderSelectedDay()
    }

    private fun refreshCalendarDecorators() {
        binding.calendarView.removeDecorators()
        val dailyMood = buildDailyMoodMap(diaries)
        val moodDates = mutableMapOf<String, MutableSet<CalendarDay>>()
        dailyMood.forEach { (dateKey, mood) ->
            runCatching {
                val d = LocalDate.parse(dateKey)
                moodDates.getOrPut(mood) { mutableSetOf() }.add(CalendarDay.from(d.year, d.monthValue, d.dayOfMonth))
            }
        }
        moodDates.forEach { (mood, dates) ->
            binding.calendarView.addDecorator(MoodBlockDecorator(dates, emotionColor(mood)))
        }
        updateSelectedDayDecorator()
    }

    private fun buildDailyMoodMap(items: List<LocalDiaryRecord>): Map<String, String> {
        val grouped = items.groupBy { dateKey(it.createdAtMillis) }
        val out = linkedMapOf<String, String>()
        grouped.forEach { (date, dayItems) ->
            val counts = dayItems.groupingBy { it.emotionLabel }.eachCount()
            val mood = if (counts.size <= 1) {
                dayItems.maxByOrNull { it.createdAtMillis }?.emotionLabel ?: "Neutral"
            } else {
                val maxCount = counts.values.maxOrNull() ?: 0
                val candidates = counts.filterValues { it == maxCount }.keys
                if (candidates.size == 1) candidates.first() else {
                    dayItems.sortedByDescending { it.createdAtMillis }
                        .firstOrNull { it.emotionLabel in candidates }?.emotionLabel ?: "Neutral"
                }
            }
            out[date] = mood
        }
        return out
    }

    private fun renderSelectedDay() {
        val container = binding.dayDiaryContainer
        container.removeAllViews()

        val dayDiaries = diaries.filter { dateKey(it.createdAtMillis) == selectedDateKey }.sortedByDescending { it.createdAtMillis }
        binding.tvDayDiary.text = getString(R.string.calendar_local_entries_count, selectedDateKey, dayDiaries.size)
        if (dayDiaries.isEmpty()) {
            container.addView(TextView(requireContext()).apply { text = getString(R.string.calendar_no_local_entries_day) })
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        dayDiaries.forEach { item ->
            val row = inflater.inflate(R.layout.item_calendar_diary, container, false)
            row.background = createDiaryCardBackground(item.emotionLabel)
            val emotionColorView = row.findViewById<View>(R.id.viewEmotionColor)
            val tvEmotion = row.findViewById<TextView>(R.id.tvEmotion)
            val tvTime = row.findViewById<TextView>(R.id.tvTime)
            val tvTranscript = row.findViewById<TextView>(R.id.tvTranscript)
            val btnDelete = row.findViewById<Button>(R.id.btnDelete)
            val btnPlay = row.findViewById<ImageButton>(R.id.btnPlayItem)
            val progress = row.findViewById<ProgressBar>(R.id.progressAudio)

            emotionColorView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(emotionColor(item.emotionLabel))
            }
            tvEmotion.text = getString(R.string.calendar_emotion_label, emotionLabelCn(item.emotionLabel))
            tvTime.text = getString(R.string.calendar_time_label, timePart(item.createdAtMillis))
            tvTranscript.text = getString(R.string.calendar_diary_label, item.transcript)
            progress.progress = 0
            setPlayButtonState(btnPlay, currentPlayingId == item.id && wavPlayer.isPlaying)

            btnPlay.setOnClickListener {
                if (currentPlayingId == item.id && wavPlayer.isPlaying) {
                    stopPlayback(true)
                } else {
                    stopPlayback(false)
                    startDiaryPlayback(item, btnPlay, progress)
                }
            }
            btnDelete.setOnClickListener { confirmDelete(item) }
            container.addView(row)
        }
    }

    private fun updateSelectedDayDecorator() {
        selectedDayDecorator?.let { binding.calendarView.removeDecorator(it) }
        selectedDayDecorator = SelectedDayCircleDecorator(
            targetDate = selectedCalendarDay,
            circleColor = ContextCompat.getColor(requireContext(), R.color.brand_accent),
            radiusScale = 0.33f
        )
        binding.calendarView.addDecorator(selectedDayDecorator!!)
    }

    private fun startDiaryPlayback(item: LocalDiaryRecord, playBtn: ImageButton, progress: ProgressBar) {
        val file = File(item.audioPath)
        if (!file.exists()) {
            binding.tvDayDiary.text = getString(R.string.calendar_audio_missing, item.audioPath)
            return
        }
        wavPlayer.play(file, object : WavPcmPlayer.Listener {
            override fun onStart(durationMs: Int) {
                uiHandler.post {
                    if (!isAdded || _binding == null) return@post
                    currentPlayingId = item.id
                    currentPlayButton = playBtn
                    currentProgressBar = progress
                    setPlayButtonState(playBtn, true)
                    progress.progress = 0
                }
            }

            override fun onProgress(positionMs: Int, durationMs: Int) {
                uiHandler.post {
                    if (!isAdded || _binding == null) return@post
                    if (durationMs > 0) {
                        currentProgressBar?.progress = (positionMs * 1000L / durationMs).toInt().coerceIn(0, 1000)
                    }
                }
            }

            override fun onComplete() {
                uiHandler.post {
                    if (!isAdded || _binding == null) return@post
                    stopPlayback(true)
                }
            }

            override fun onError(message: String) {
                uiHandler.post {
                    if (!isAdded || _binding == null) return@post
                    binding.tvDayDiary.text = getString(R.string.calendar_playback_failed, message)
                    stopPlayback(true)
                }
            }
        })
    }

    private fun stopPlayback(shouldRenderList: Boolean) {
        wavPlayer.stop()
        currentPlayButton?.let { setPlayButtonState(it, false) }
        currentProgressBar?.progress = 0
        currentPlayButton = null
        currentProgressBar = null
        currentPlayingId = null
        if (shouldRenderList) renderSelectedDay()
    }

    private fun confirmDelete(item: LocalDiaryRecord) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.calendar_delete_local_title)
            .setMessage(R.string.calendar_delete_local_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (currentPlayingId == item.id) stopPlayback(false)
                recordStore.remove(item.id)
                File(item.audioPath).delete()
                loadCalendar()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmSyncToCloud() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.calendar_privacy_title)
            .setMessage(R.string.calendar_privacy_message)
            .setPositiveButton(R.string.calendar_agree_and_sync) { _, _ -> syncLocalAndCloud() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun syncLocalAndCloud() {
        binding.btnSyncCloud.isEnabled = false
        val unsynced = recordStore.loadUnsynced()
        binding.tvDayDiary.text = getString(R.string.calendar_syncing, unsynced.size)
        viewLifecycleOwner.lifecycleScope.launch {
            var success = 0
            var failed = 0
            unsynced.forEach { rec ->
                val file = File(rec.audioPath)
                if (!file.exists()) {
                    failed += 1
                    return@forEach
                }

                val requestBody = file.asRequestBody("audio/wav".toMediaTypeOrNull())
                val audioPart = MultipartBody.Part.createFormData("audio", file.name, requestBody)
                val textMediaType = "text/plain".toMediaTypeOrNull()
                runCatching {
                    api.uploadDiaryWithMetadata(
                        audio = audioPart,
                        preserveFields = "true".toRequestBody(textMediaType),
                        transcript = rec.transcript.toRequestBody(textMediaType),
                        emotionLabel = rec.emotionLabel.toRequestBody(textMediaType),
                        createdAtMs = rec.createdAtMillis.toString().toRequestBody(textMediaType),
                    )
                }
                    .onSuccess {
                        recordStore.bindServerDiaryId(rec.id, it.id)
                        success += 1
                    }
                    .onFailure { failed += 1 }
            }

            if (failed > 0) {
                binding.btnSyncCloud.isEnabled = true
                val msg = getString(R.string.calendar_sync_done, success, failed)
                binding.tvDayDiary.text = "$msg\n${getString(R.string.calendar_sync_partial_warning)}"
                Toast.makeText(requireContext(), binding.tvDayDiary.text, Toast.LENGTH_LONG).show()
                loadCalendar()
                return@launch
            }

            val rebuildResult = runCatching {
                val cloudItems = api.listDiaries().items
                rebuildLocalRecordsFromCloud(cloudItems)
            }
            rebuildResult.onSuccess { cloudRecords ->
                recordStore.replaceAll(cloudRecords)
                loadCalendar()
                binding.btnSyncCloud.isEnabled = true
                val done = getString(R.string.calendar_sync_full_done, cloudRecords.size)
                binding.tvDayDiary.text = done
                Toast.makeText(requireContext(), done, Toast.LENGTH_LONG).show()
            }.onFailure {
                binding.btnSyncCloud.isEnabled = true
                binding.tvDayDiary.text = getString(R.string.calendar_sync_full_failed, it.message ?: "unknown")
                Toast.makeText(requireContext(), binding.tvDayDiary.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun rebuildLocalRecordsFromCloud(items: List<DiaryItem>): List<LocalDiaryRecord> {
        return withContext(Dispatchers.IO) {
            val localDir = File(requireContext().filesDir, "local_records_${session.getLocalScope()}").apply { mkdirs() }
            localDir.listFiles()?.forEach { if (it.isFile) it.delete() }

            val records = mutableListOf<LocalDiaryRecord>()
            items.sortedByDescending { parseServerMillis(it.created_at) }.forEach { item ->
                val ext = extractExt(item.audio_path)
                val localAudio = File(localDir, "cloud_${item.id}.$ext")
                val body = api.downloadDiaryAudio(item.id)
                body.byteStream().use { input ->
                    localAudio.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                records.add(
                    LocalDiaryRecord(
                        id = "cloud_${item.id}",
                        createdAtMillis = parseServerMillis(item.created_at),
                        audioPath = localAudio.absolutePath,
                        emotionLabel = item.emotion_label,
                        transcript = item.transcript,
                        serverDiaryId = item.id,
                    )
                )
            }
            records
        }
    }

    private fun parseServerMillis(raw: String): Long {
        val text = raw.trim()
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(text).atZone(ZoneOffset.UTC).toInstant().toEpochMilli() }
        return System.currentTimeMillis()
    }

    private fun extractExt(audioPath: String): String {
        val fileName = audioPath.substringAfterLast('/', audioPath).substringAfterLast('\\', audioPath)
        val ext = fileName.substringAfterLast('.', "")
        return if (ext.isBlank()) "wav" else ext
    }

    private fun dateKey(ms: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
    }

    private fun timePart(ms: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
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

    private fun emotionLabelCn(label: String): String {
        return when (label) {
            "Neutral" -> getString(R.string.emotion_neutral_cn)
            "Sad" -> getString(R.string.emotion_sad_cn)
            "Angry" -> getString(R.string.emotion_angry_cn)
            "Happy" -> getString(R.string.emotion_happy_cn)
            "Fear" -> getString(R.string.emotion_fear_cn)
            "Surprise" -> getString(R.string.emotion_surprise_cn)
            else -> label
        }
    }

    private fun createDiaryCardBackground(label: String): GradientDrawable {
        val base = emotionColor(label)
        val fill = adjustAlphaColor(base, 0.16f)
        val stroke = adjustAlphaColor(base, 0.45f)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f
            setColor(fill)
            setStroke(dp(1), stroke)
        }
    }

    private fun adjustAlphaColor(color: Int, factor: Float): Int {
        val alpha = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        val red = android.graphics.Color.red(color)
        val green = android.graphics.Color.green(color)
        val blue = android.graphics.Color.blue(color)
        return android.graphics.Color.argb(alpha, red, green, blue)
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
        stopPlayback(false)
        _binding = null
    }
}

class MoodBlockDecorator(
    private val dates: Set<CalendarDay>,
    private val blockColor: Int,
) : DayViewDecorator {
    override fun shouldDecorate(day: CalendarDay): Boolean = dates.contains(day)

    override fun decorate(view: DayViewFacade) {
        view.setBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f
            setColor(adjustAlphaInt(blockColor, 0.25f))
        })
    }

    private fun adjustAlphaInt(inputColor: Int, factor: Float): Int {
        val alpha = (android.graphics.Color.alpha(inputColor) * factor).toInt().coerceIn(0, 255)
        val red = android.graphics.Color.red(inputColor)
        val green = android.graphics.Color.green(inputColor)
        val blue = android.graphics.Color.blue(inputColor)
        return android.graphics.Color.argb(alpha, red, green, blue)
    }
}

class SelectedDayCircleDecorator(
    private val targetDate: CalendarDay,
    private val circleColor: Int,
    private val radiusScale: Float,
) : DayViewDecorator {
    override fun shouldDecorate(day: CalendarDay): Boolean = day == targetDate

    override fun decorate(view: DayViewFacade) {
        view.addSpan(SmallCircleSpan(circleColor, radiusScale))
        view.addSpan(ForegroundColorSpan(Color.WHITE))
    }
}

class SmallCircleSpan(
    private val color: Int,
    private val radiusScale: Float,
) : LineBackgroundSpan {
    override fun drawBackground(
        c: android.graphics.Canvas,
        p: android.graphics.Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lnum: Int
    ) {
        val originalColor = p.color
        val cellRadius = ((bottom - top) / 2f).coerceAtLeast(1f)
        val drawRadius = (cellRadius * radiusScale).coerceAtLeast(2f)
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        p.color = color
        c.drawCircle(cx, cy, drawRadius, p)
        p.color = originalColor
    }
}
