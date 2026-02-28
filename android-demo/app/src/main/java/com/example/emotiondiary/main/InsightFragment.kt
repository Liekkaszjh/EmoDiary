package com.example.emotiondiary.main

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.emotiondiary.R
import com.example.emotiondiary.data.AgentChatRequest
import com.example.emotiondiary.data.AgentReportItem
import com.example.emotiondiary.data.AgentSummaryRequest
import com.example.emotiondiary.data.InsightResponse
import com.example.emotiondiary.databinding.FragmentInsightBinding
import com.example.emotiondiary.network.ApiService
import com.example.emotiondiary.network.RetrofitClient
import com.example.emotiondiary.storage.SessionManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.launch
import kotlin.math.ceil
import java.util.TimeZone

class InsightFragment : Fragment() {
    private var _binding: FragmentInsightBinding? = null
    private val binding get() = _binding!!

    private lateinit var session: SessionManager
    private lateinit var api: ApiService

    private val rangeKeys = listOf("today", "3d", "7d", "1m", "6m", "1y")
    private val reportRangeKeys = listOf("day", "week", "month")
    private val emotionScores = mapOf(
        "Sad" to 1f,
        "Fear" to 2f,
        "Angry" to 3f,
        "Neutral" to 4f,
        "Surprise" to 5f,
        "Happy" to 6f,
    )

    private var selectedRangeKey = "today"
    private var selectedReportRangeKey = "week"
    private var chatSessionId: String? = null
    private val chatHistory: MutableList<ChatMessage> = mutableListOf()

    private enum class ChatRole {
        USER,
        ASSISTANT,
    }

    private data class ChatMessage(
        val role: ChatRole,
        val text: String,
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInsightBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        session = SessionManager(requireContext())
        api = RetrofitClient.api(session)

        binding.spinnerRange.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            rangeKeys.map { rangeLabel(it) },
        )
        binding.spinnerRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedRangeKey = rangeKeys[position]
                loadCloudInsight(selectedRangeKey)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spinnerReportRange.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            reportRangeKeys.map { reportRangeLabel(it) },
        )
        binding.spinnerReportRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedReportRangeKey = reportRangeKeys[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.btnGenerateReport.setOnClickListener { generateAgentReport() }
        binding.agentEntry.setOnClickListener { showAgentInputDialog() }
        binding.btnAgent.setOnClickListener { showAgentInputDialog() }
        enableAgentDrag()
        adjustAgentSizeByChart()
        binding.spinnerRange.setSelection(0)
        binding.spinnerReportRange.setSelection(1)
    }

    override fun onResume() {
        super.onResume()
        loadCloudInsight(selectedRangeKey)
        loadReportHistory()
    }

    private fun loadCloudInsight(rangeKey: String) {
        binding.tvInsightText.text = getString(R.string.insight_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.insight(rangeKey, currentTzOffsetMinutes()) }
                .onSuccess { renderInsight(it) }
                .onFailure {
                    binding.tvInsightText.text = getString(R.string.insight_load_failed, it.message ?: "unknown")
                    renderLineChart(emptyList(), rangeKey)
                    renderPieChart(emptyMap())
                }
        }
    }

    private fun renderInsight(response: InsightResponse) {
        binding.tvInsightText.text = response.text

        val trendPoints = response.trend.mapIndexed { idx, point ->
            val emotion = point["emotion"] ?: "Neutral"
            val label = point["date"] ?: idx.toString()
            label to (emotionScores[emotion] ?: 4f)
        }
        renderLineChart(trendPoints, response.range_key)
        renderPieChart(response.pie)
    }

    private fun generateAgentReport() {
        binding.btnGenerateReport.isEnabled = false
        binding.tvReportStatus.text = getString(R.string.insight_report_generating, reportRangeLabel(selectedReportRangeKey))
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.agentReport(
                    AgentSummaryRequest(
                        range_key = selectedReportRangeKey,
                        tz_offset_minutes = currentTzOffsetMinutes(),
                    )
                )
            }.onSuccess { resp ->
                binding.btnGenerateReport.isEnabled = true
                if (resp.status == "ok") {
                    val savedHint = if (resp.saved_report_id != null) {
                        getString(
                            R.string.insight_report_saved,
                            resp.saved_report_id,
                            formatDateTime(resp.saved_at ?: ""),
                        )
                    } else {
                        getString(R.string.insight_report_generated_not_saved)
                    }
                    binding.tvReportStatus.text = savedHint
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.insight_report_dialog_title)
                        .setMessage(resp.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    loadReportHistory()
                } else {
                    binding.tvReportStatus.text = getString(R.string.insight_report_generate_failed, resp.message)
                    Toast.makeText(requireContext(), resp.message, Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                binding.btnGenerateReport.isEnabled = true
                val error = it.message ?: "unknown"
                binding.tvReportStatus.text = getString(R.string.insight_report_generate_failed, error)
                Toast.makeText(requireContext(), getString(R.string.insight_report_generate_failed, error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadReportHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.agentReports(limit = 20) }
                .onSuccess { renderReports(it.items) }
                .onFailure {
                    if (binding.reportContainer.childCount == 0) {
                        binding.tvReportStatus.text = getString(
                            R.string.insight_report_list_failed,
                            it.message ?: "unknown",
                        )
                    }
                }
        }
    }

    private fun renderReports(items: List<AgentReportItem>) {
        binding.reportContainer.removeAllViews()
        if (items.isEmpty()) {
            binding.reportContainer.addView(TextView(requireContext()).apply {
                text = getString(R.string.insight_report_empty)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            })
            return
        }

        items.forEach { item ->
            val card = TextView(requireContext()).apply {
                val padding = (12 * resources.displayMetrics.density).toInt()
                setPadding(padding)
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_card)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                text = buildString {
                    append(getString(R.string.insight_report_card_title, reportRangeLabel(item.range_key)))
                    append("\n")
                    append(getString(R.string.insight_report_card_created, formatDateTime(item.created_at)))
                    append("\n")
                    append(getString(R.string.insight_report_card_period, formatPeriod(item.period_start, item.period_end)))
                    append("\n")
                    append(getString(R.string.insight_report_card_samples, item.source_diary_count))
                    append("\n\n")
                    append(item.summary_text)
                }
                val params = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.bottomMargin = (8 * resources.displayMetrics.density).toInt()
                layoutParams = params
            }
            binding.reportContainer.addView(card)
        }
    }

    private fun showAgentInputDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_agent_chat, null)
        val input = dialogView.findViewById<EditText>(R.id.etAgentInput)
        val historyScroll = dialogView.findViewById<ScrollView>(R.id.svAgentDialogHistory)
        val messagesContainer = dialogView.findViewById<LinearLayout>(R.id.llAgentDialogMessages)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnAgentDialogCancel)
        val btnSend = dialogView.findViewById<TextView>(R.id.btnAgentDialogSend)
        renderChatMessages(messagesContainer, historyScroll)
        input.hint = getString(R.string.insight_assistant_hint)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSend.setOnClickListener {
            val userText = input.text.toString().trim()
            sendAgentMessage(
                userText = userText,
                input = input,
                messagesContainer = messagesContainer,
                historyScroll = historyScroll,
                btnSend = btnSend,
            )
        }
        dialog.show()
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendAgentMessage(
        userText: String,
        input: EditText,
        messagesContainer: LinearLayout,
        historyScroll: ScrollView,
        btnSend: TextView,
    ) {
        if (userText.isBlank()) {
            Toast.makeText(requireContext(), R.string.insight_assistant_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        val waitingLine = getString(R.string.insight_assistant_agent_thinking)
        chatHistory.add(ChatMessage(ChatRole.USER, userText))
        chatHistory.add(ChatMessage(ChatRole.ASSISTANT, waitingLine))
        renderChatMessages(messagesContainer, historyScroll)
        input.setText("")
        btnSend.isEnabled = false
        btnSend.alpha = 0.6f
        binding.tvReportStatus.text = getString(R.string.insight_assistant_waiting)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.agentChat(
                    AgentChatRequest(
                        message = userText,
                        range_key = selectedRangeKey,
                        session_id = chatSessionId,
                        tz_offset_minutes = currentTzOffsetMinutes(),
                    ),
                )
            }.onSuccess { resp ->
                chatSessionId = resp.session_id ?: chatSessionId
                if (chatHistory.isNotEmpty() && chatHistory.last().text == waitingLine) {
                    chatHistory.removeAt(chatHistory.lastIndex)
                }
                chatHistory.add(ChatMessage(ChatRole.ASSISTANT, resp.message))
                renderChatMessages(messagesContainer, historyScroll)
                binding.tvReportStatus.text = getString(R.string.insight_assistant_done)
            }.onFailure {
                val error = it.message ?: "unknown"
                if (chatHistory.isNotEmpty() && chatHistory.last().text == waitingLine) {
                    chatHistory.removeAt(chatHistory.lastIndex)
                }
                chatHistory.add(ChatMessage(ChatRole.ASSISTANT, getString(R.string.insight_assistant_failed_bubble, error)))
                renderChatMessages(messagesContainer, historyScroll)
                binding.tvReportStatus.text = getString(R.string.insight_assistant_failed, error)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.insight_assistant_failed, error),
                    Toast.LENGTH_LONG,
                ).show()
            }.also {
                btnSend.isEnabled = true
                btnSend.alpha = 1f
            }
        }
    }

    private fun renderChatMessages(container: LinearLayout, historyScroll: ScrollView) {
        container.removeAllViews()
        val displayMessages = if (chatHistory.isEmpty()) {
            listOf(ChatMessage(ChatRole.ASSISTANT, getString(R.string.insight_assistant_message)))
        } else {
            chatHistory.takeLast(20)
        }

        displayMessages.forEach { message ->
            val itemView = LayoutInflater.from(requireContext()).inflate(R.layout.item_agent_message, container, false)
            val avatar = itemView.findViewById<ImageView>(R.id.ivAgentMessageAvatar)
            val textView = itemView.findViewById<TextView>(R.id.tvAgentMessageText)
            if (message.role == ChatRole.USER) {
                avatar.setImageResource(currentUserAvatarRes())
                textView.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_agent_bubble_user)
                textView.text = getString(R.string.insight_assistant_me_prefix, message.text)
            } else {
                avatar.setImageResource(R.drawable.emoagent_avatar)
                textView.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_agent_bubble_assistant)
                textView.text = getString(R.string.insight_assistant_agent_prefix, message.text)
            }
            container.addView(itemView)
        }
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun currentUserAvatarRes(): Int {
        return when (session.getAvatar()) {
            "user2" -> R.drawable.avatar_user2
            "user3" -> R.drawable.avatar_user3
            "user4" -> R.drawable.avatar_user4
            else -> R.drawable.avatar_user1
        }
    }

    private fun renderLineChart(points: List<Pair<String, Float>>, rangeKey: String) {
        if (points.isEmpty()) {
            binding.lineChart.clear()
            binding.lineChart.setNoDataText(getString(R.string.insight_local_no_trend))
            binding.lineChart.invalidate()
            return
        }

        val entries = points.mapIndexed { idx, pair -> Entry(idx.toFloat(), pair.second) }
        val labels = points.map { it.first }
        val visibleLabelCount = labels.size.coerceAtMost(5).coerceAtLeast(2)
        val labelStep = if (labels.size <= visibleLabelCount) 1
        else ceil((labels.size - 1).toDouble() / (visibleLabelCount - 1).toDouble()).toInt()
        val lineSet = LineDataSet(entries, getString(R.string.insight_local_trend_label)).apply {
            color = ContextCompat.getColor(requireContext(), R.color.brand_primary)
            lineWidth = 2.2f
            setDrawCircles(true)
            circleRadius = 3.4f
            setCircleColor(ContextCompat.getColor(requireContext(), R.color.brand_accent))
            setDrawValues(false)
        }

        binding.lineChart.apply {
            data = LineData(lineSet)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 1f
            axisLeft.axisMaximum = 6f
            axisLeft.granularity = 1f
            axisLeft.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return when (value.toInt()) {
                        1 -> emotionLabelCn("Sad")
                        2 -> emotionLabelCn("Fear")
                        3 -> emotionLabelCn("Angry")
                        4 -> emotionLabelCn("Neutral")
                        5 -> emotionLabelCn("Surprise")
                        6 -> emotionLabelCn("Happy")
                        else -> ""
                    }
                }
            }
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.labelRotationAngle = if (rangeKey == "today") 0f else -35f
            xAxis.labelCount = visibleLabelCount
            xAxis.setAvoidFirstLastClipping(true)
            xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val idx = value.toInt()
                    if (idx < 0 || idx >= labels.size) return ""
                    val isLast = idx == labels.lastIndex
                    val isStepTick = idx % labelStep == 0
                    return if (isStepTick || isLast) labels[idx] else ""
                }
            }
            legend.isEnabled = false
            description.isEnabled = false
            invalidate()
        }
    }

    private fun renderPieChart(distribution: Map<String, Float>) {
        if (distribution.isEmpty()) {
            binding.pieChart.clear()
            binding.pieChart.setNoDataText(getString(R.string.insight_local_no_distribution))
            binding.pieChart.invalidate()
            return
        }

        val sorted = distribution
            .filter { it.value > 0f }
            .entries
            .sortedByDescending { it.value }
        val entries = sorted.map { PieEntry(it.value * 100f, emotionLabelCn(it.key)) }
        val colors = sorted.map { entry ->
            when (entry.key) {
                "Sad" -> ContextCompat.getColor(requireContext(), R.color.sad)
                "Fear" -> ContextCompat.getColor(requireContext(), R.color.fear)
                "Angry" -> ContextCompat.getColor(requireContext(), R.color.angry)
                "Happy" -> ContextCompat.getColor(requireContext(), R.color.happy)
                "Surprise" -> ContextCompat.getColor(requireContext(), R.color.surprise)
                else -> ContextCompat.getColor(requireContext(), R.color.neutral)
            }
        }
        val set = PieDataSet(entries, "").apply {
            this.colors = colors
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.white)
            valueTextSize = 10f
        }
        binding.pieChart.apply {
            setUsePercentValues(true)
            data = PieData(set).apply { setValueFormatter(PercentFormatter(binding.pieChart)) }
            centerText = getString(R.string.insight_local_center_text)
            description.isEnabled = false
            invalidate()
        }
    }

    private fun adjustAgentSizeByChart() {
        binding.insightChartCard.post {
            val chartHeight = binding.insightChartCard.height
            if (chartHeight <= 0) return@post
            val targetHeight = (chartHeight / 2f).toInt().coerceAtLeast(96)
            val lp = binding.btnAgent.layoutParams
            lp.height = targetHeight
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.btnAgent.layoutParams = lp
        }
    }

    private fun enableAgentDrag() {
        val entry = binding.agentEntry
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var dX = 0f
        var dY = 0f
        var downX = 0f
        var downY = 0f
        var moved = false

        val dragListener = View.OnTouchListener { _, event ->
            val target = entry
            val parent = target.parent as? ViewGroup ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    dX = target.x - event.rawX
                    dY = target.y - event.rawY
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    val nextX = (event.rawX + dX).coerceIn(0f, (parent.width - target.width).toFloat())
                    val nextY = (event.rawY + dY).coerceIn(0f, (parent.height - target.height).toFloat())
                    target.x = nextX
                    target.y = nextY
                    if (!moved) {
                        val movedDx = event.rawX - downX
                        val movedDy = event.rawY - downY
                        moved = (movedDx * movedDx + movedDy * movedDy) > (touchSlop * touchSlop)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    parent.requestDisallowInterceptTouchEvent(false)
                    if (!moved) {
                        target.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    parent.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
        // Bind to both parent and image to avoid child view swallowing drag events.
        binding.agentEntry.setOnTouchListener(dragListener)
        binding.btnAgent.setOnTouchListener(dragListener)
    }

    private fun rangeLabel(key: String): String {
        return when (key) {
            "today" -> getString(R.string.range_today)
            "3d" -> getString(R.string.range_3d)
            "7d" -> getString(R.string.range_7d)
            "1m" -> getString(R.string.range_1m)
            "6m" -> getString(R.string.range_6m)
            "1y" -> getString(R.string.range_1y)
            else -> key
        }
    }

    private fun reportRangeLabel(key: String): String {
        return when (key) {
            "day" -> getString(R.string.report_range_day)
            "week" -> getString(R.string.report_range_week)
            "month" -> getString(R.string.report_range_month)
            else -> key
        }
    }

    private fun formatDateTime(raw: String): String {
        if (raw.isBlank()) return "-"
        return raw.replace("T", " ").substringBefore(".")
    }

    private fun formatPeriod(startRaw: String, endRaw: String): String {
        val start = formatDateTime(startRaw)
        val end = formatDateTime(endRaw)
        return "$start ~ $end"
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

    private fun currentTzOffsetMinutes(): Int {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
