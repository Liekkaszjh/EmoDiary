package com.example.emotiondiary.data

data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class TokenResponse(
    val access_token: String,
    val token_type: String
)

data class UserProfile(
    val id: Int,
    val username: String,
    val nickname: String,
    val avatar_url: String
)

data class UpdateProfileRequest(
    val nickname: String,
    val avatar_url: String = ""
)

data class DiaryItem(
    val id: Int,
    val created_at: String,
    val transcript: String,
    val emotion_label: String,
    val audio_path: String
)

data class DiaryListResponse(val items: List<DiaryItem>)

data class InsightResponse(
    val range_key: String,
    val text: String,
    val trend: List<Map<String, String>>,
    val pie: Map<String, Float>,
    val calendar: List<Map<String, String>>
)

data class AgentSummaryRequest(
    val range_key: String,
    val tz_offset_minutes: Int = 0
)

data class AgentSummaryResponse(
    val range_key: String,
    val status: String,
    val message: String,
    val session_id: String? = null,
    val saved_report_id: Int? = null,
    val saved_at: String? = null
)

data class AgentReportItem(
    val id: Int,
    val range_key: String,
    val period_start: String,
    val period_end: String,
    val source_diary_count: Int,
    val summary_text: String,
    val created_at: String
)

data class AgentReportListResponse(val items: List<AgentReportItem>)

data class AgentChatRequest(
    val message: String,
    val range_key: String,
    val session_id: String? = null,
    val tz_offset_minutes: Int = 0
)

data class AgentChatResponse(
    val range_key: String,
    val status: String,
    val message: String,
    val session_id: String? = null
)
