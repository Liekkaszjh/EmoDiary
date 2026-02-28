package com.example.emotiondiary.network

import com.example.emotiondiary.data.AgentSummaryRequest
import com.example.emotiondiary.data.AgentSummaryResponse
import com.example.emotiondiary.data.AgentReportListResponse
import com.example.emotiondiary.data.AgentChatRequest
import com.example.emotiondiary.data.AgentChatResponse
import com.example.emotiondiary.data.DiaryItem
import com.example.emotiondiary.data.DiaryListResponse
import com.example.emotiondiary.data.InsightResponse
import com.example.emotiondiary.data.LoginRequest
import com.example.emotiondiary.data.RegisterRequest
import com.example.emotiondiary.data.TokenResponse
import com.example.emotiondiary.data.UpdateProfileRequest
import com.example.emotiondiary.data.UserProfile
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("/auth/register")
    suspend fun register(@Body payload: RegisterRequest): TokenResponse

    @POST("/auth/login")
    suspend fun login(@Body payload: LoginRequest): TokenResponse

    @GET("/users/me")
    suspend fun me(): UserProfile

    @PUT("/users/me")
    suspend fun updateMe(@Body payload: UpdateProfileRequest): UserProfile

    @Multipart
    @POST("/diaries/upload")
    suspend fun uploadDiary(@Part audio: MultipartBody.Part): DiaryItem

    @Multipart
    @POST("/diaries/upload")
    suspend fun uploadDiaryWithMetadata(
        @Part audio: MultipartBody.Part,
        @Part("preserve_fields") preserveFields: RequestBody,
        @Part("transcript") transcript: RequestBody,
        @Part("emotion_label") emotionLabel: RequestBody,
        @Part("created_at_ms") createdAtMs: RequestBody
    ): DiaryItem

    @GET("/diaries")
    suspend fun listDiaries(): DiaryListResponse

    @DELETE("/diaries/{id}")
    suspend fun deleteDiary(@Path("id") id: Int): Map<String, String>

    @GET("/diaries/{id}/audio")
    suspend fun downloadDiaryAudio(@Path("id") id: Int): ResponseBody

    @GET("/analytics/insight")
    suspend fun insight(
        @Query("range_key") rangeKey: String,
        @Query("tz_offset_minutes") tzOffsetMinutes: Int = 0
    ): InsightResponse

    @POST("/analytics/agent-report")
    suspend fun agentReport(@Body payload: AgentSummaryRequest): AgentSummaryResponse

    @GET("/analytics/agent-reports")
    suspend fun agentReports(
        @Query("range_key") rangeKey: String? = null,
        @Query("limit") limit: Int = 20
    ): AgentReportListResponse

    @POST("/analytics/agent-chat")
    suspend fun agentChat(@Body payload: AgentChatRequest): AgentChatResponse
}
