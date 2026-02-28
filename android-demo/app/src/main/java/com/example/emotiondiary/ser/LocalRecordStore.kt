package com.example.emotiondiary.ser

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LocalDiaryRecord(
    val id: String,
    val createdAtMillis: Long,
    val audioPath: String,
    val emotionLabel: String,
    val transcript: String,
    val serverDiaryId: Int? = null
)

class LocalRecordStore(context: Context, scopeKey: String = "guest") {
    private val prefs = context.getSharedPreferences("local_ser_records_$scopeKey", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "records_json"
    private val syncedIdsKey = "synced_record_ids_json"
    private val type = object : TypeToken<MutableList<LocalDiaryRecord>>() {}.type
    private val syncedIdsType = object : TypeToken<MutableSet<String>>() {}.type

    fun add(record: LocalDiaryRecord) {
        val all = loadAll()
        all.add(0, record)
        saveAll(all)
    }

    fun replaceAll(records: List<LocalDiaryRecord>) {
        saveAll(records.sortedByDescending { it.createdAtMillis })
    }

    fun loadToday(): MutableList<LocalDiaryRecord> {
        val today = LocalDate.now()
        return loadAll()
            .filter { millisToDate(it.createdAtMillis) == today }
            .sortedByDescending { it.createdAtMillis }
            .toMutableList()
    }

    fun loadAll(): MutableList<LocalDiaryRecord> {
        val json = prefs.getString(key, null) ?: return mutableListOf()
        return runCatching { gson.fromJson<MutableList<LocalDiaryRecord>>(json, type) }.getOrDefault(mutableListOf())
    }

    fun loadUnsynced(): MutableList<LocalDiaryRecord> {
        return loadAll()
            .filter { it.serverDiaryId == null }
            .sortedByDescending { it.createdAtMillis }
            .toMutableList()
    }

    fun markSynced(recordId: String) {
        // Backward compatible fallback: legacy sync marker.
        val ids = loadSyncedIds()
        ids.add(recordId)
        prefs.edit().putString(syncedIdsKey, gson.toJson(ids)).apply()
    }

    fun bindServerDiaryId(recordId: String, serverDiaryId: Int) {
        val all = loadAll()
        val updated = all.map { rec ->
            if (rec.id == recordId) rec.copy(serverDiaryId = serverDiaryId) else rec
        }
        saveAll(updated)
    }

    fun remove(recordId: String) {
        val all = loadAll()
        all.removeAll { it.id == recordId }
        saveAll(all)

        val ids = loadSyncedIds()
        ids.remove(recordId)
        prefs.edit().putString(syncedIdsKey, gson.toJson(ids)).apply()
    }

    private fun saveAll(list: List<LocalDiaryRecord>) {
        prefs.edit().putString(key, gson.toJson(list)).apply()
    }

    private fun loadSyncedIds(): MutableSet<String> {
        val json = prefs.getString(syncedIdsKey, null) ?: return mutableSetOf()
        return runCatching { gson.fromJson<MutableSet<String>>(json, syncedIdsType) }.getOrDefault(mutableSetOf())
    }

    private fun millisToDate(ms: Long): LocalDate {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
