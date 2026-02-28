package com.example.emotiondiary.storage

import android.content.Context

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("emotion_diary", Context.MODE_PRIVATE)

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    fun getToken(): String = prefs.getString("token", "") ?: ""

    fun saveNickname(nickname: String) {
        prefs.edit().putString("nickname", nickname).apply()
    }

    fun getNickname(): String = prefs.getString("nickname", "昵称") ?: "昵称"

    fun saveAvatar(avatar: String) {
        prefs.edit().putString("avatar", avatar).apply()
    }

    fun getAvatar(): String = prefs.getString("avatar", "user1") ?: "user1"

    fun saveUserId(userId: Int) {
        prefs.edit().putInt("user_id", userId).apply()
    }

    fun getUserId(): Int = prefs.getInt("user_id", 0)

    fun getLocalScope(): String {
        val uid = getUserId()
        if (uid > 0) return "u$uid"
        val token = getToken()
        if (token.isNotBlank()) {
            return "t${token.hashCode().toUInt().toString()}"
        }
        return "guest"
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
