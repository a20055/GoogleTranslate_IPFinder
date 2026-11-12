package com.gtipfinder.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 应用设置持久化（单例，基于 SharedPreferences）
 * 
 * 只存轻量数据（设置参数、IP 库、测速结果）到应用私有存储，
 * 不需要任何存储权限（Android 10+ 分区存储下应用私有目录完全隔离）。
 * 
 * 初始化时机：Application.onCreate 或 MainActivity.onCreate（两者都行）。
 */
object AppPrefs {

    private lateinit var prefs: SharedPreferences

    private const val PREFS_NAME = "gtipfinder_prefs"

    // Key 常量
    private const val KEY_TIMEOUT_MS = "timeout_ms"
    private const val KEY_REPEAT_COUNT = "repeat_count"
    private const val KEY_CONCURRENCY = "concurrency"
    private const val KEY_IP_LIBRARY = "ip_library"        // JSON 数组
    private const val KEY_SPEED_RESULTS = "speed_results"  // JSON 数组 [ip, latency]

    // 默认值
    private const val DEFAULT_TIMEOUT_MS = 1500L
    private const val DEFAULT_REPEAT_COUNT = 3
    private const val DEFAULT_CONCURRENCY = 32

    val isInitialized: Boolean get() = this::prefs.isInitialized

    fun init(context: Context) {
        if (!isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
        }
    }

    // —— 用户设置 ——

    var timeoutMs: Long
        get() = prefs.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
        set(value) = prefs.edit().putLong(KEY_TIMEOUT_MS, value).apply()

    var repeatCount: Int
        get() = prefs.getInt(KEY_REPEAT_COUNT, DEFAULT_REPEAT_COUNT)
        set(value) = prefs.edit().putInt(KEY_REPEAT_COUNT, value).apply()

    var concurrency: Int
        get() = prefs.getInt(KEY_CONCURRENCY, DEFAULT_CONCURRENCY)
        set(value) = prefs.edit().putInt(KEY_CONCURRENCY, value).apply()

    // —— IP 库（在线同步合并后的完整列表） ——

    var ipLibrary: List<String>
        get() {
            val json = prefs.getString(KEY_IP_LIBRARY, null) ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(it) }
            prefs.edit().putString(KEY_IP_LIBRARY, arr.toString()).apply()
        }

    fun hasIpLibrary(): Boolean = prefs.contains(KEY_IP_LIBRARY)
    fun clearIpLibrary() = prefs.edit().remove(KEY_IP_LIBRARY).apply()

    // —— 测速结果 ——

    var speedTestResults: List<Pair<String, Long>>
        get() {
            val json = prefs.getString(KEY_SPEED_RESULTS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val pair = arr.getJSONArray(i)
                    pair.getString(0) to pair.getLong(1)
                }
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { (ip, latency) ->
                val pair = JSONArray()
                pair.put(ip)
                pair.put(latency)
                arr.put(pair)
            }
            prefs.edit().putString(KEY_SPEED_RESULTS, arr.toString()).apply()
        }
}
