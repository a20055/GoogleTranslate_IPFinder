package com.gtipfinder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtipfinder.data.AppPrefs
import com.gtipfinder.data.BuiltinIpDatabase
import com.gtipfinder.data.OnlineSyncService
import com.gtipfinder.data.OnlineSyncService.SyncResult
import com.gtipfinder.network.IpTester
import com.gtipfinder.network.IpTestResult
import com.gtipfinder.network.TestProgress
import com.gtipfinder.scan.CidrUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主 ViewModel - 管理应用核心状态
 * 
 * 所有持久化数据通过 AppPrefs（SharedPreferences）读写：
 * - 用户设置：timeoutMs / repeatCount / concurrency（带 setter 自动回写）
 * - IP 库：在线同步合并后的完整列表（重启恢复）
 * - 测速结果：上次测速可用的 IP + 延迟（重启恢复）
 * 
 * Android 10+ 分区存储下，应用私有目录无需任何存储权限。
 */
class MainViewModel : ViewModel() {

    private val ipTester = IpTester()

    // IP 库：优先 AppPrefs 持久化，没有就内置
    private val _ipLibrary = MutableStateFlow<List<String>>(
        if (AppPrefs.hasIpLibrary()) AppPrefs.ipLibrary else BuiltinIpDatabase.ipv4List
    )
    val ipLibrary: StateFlow<List<String>> = _ipLibrary.asStateFlow()

    // 测速结果：从 AppPrefs 恢复
    private val _speedTestResults = MutableStateFlow<List<Pair<String, Long>>>(
        AppPrefs.speedTestResults
    )
    val speedTestResults: StateFlow<List<Pair<String, Long>>> = _speedTestResults.asStateFlow()

    private val _progress = MutableStateFlow(TestProgress(0, 0, 0))
    val progress: StateFlow<TestProgress> = _progress.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _debugResult = MutableStateFlow<DebugResult?>(null)
    val debugResult: StateFlow<DebugResult?> = _debugResult.asStateFlow()

    // —— 用户设置：从 AppPrefs 读，变更时自动写回 ——

    var timeoutMs: Long = AppPrefs.timeoutMs
        set(value) { field = value; AppPrefs.timeoutMs = value }

    var repeatCount: Int = AppPrefs.repeatCount
        set(value) { field = value; AppPrefs.repeatCount = value }

    var concurrency: Int = AppPrefs.concurrency
        set(value) { field = value; AppPrefs.concurrency = value }

    /** 启动批量测速 */
    fun startSpeedTest() {
        if (_isTesting.value) return

        _isTesting.value = true
        _speedTestResults.value = emptyList()
        _progress.value = TestProgress(0, 0, 0)
        val available = mutableListOf<Pair<String, Long>>()
        val seen = mutableSetOf<String>()
        var availableCount = 0

        viewModelScope.launch(Dispatchers.IO) {
            ipTester.batchSpeedTest(
                ips = _ipLibrary.value,
                repeat = repeatCount,
                concurrency = concurrency,
                timeoutMs = timeoutMs,
                scope = viewModelScope
            ).collect { result ->
                when (result) {
                    is IpTestResult.Success -> {
                        val latencyMs = result.latencyMs
                        if (!seen.contains(result.ip)) {
                            seen.add(result.ip)
                            available.add(result.ip to latencyMs)
                            val sorted = available.sortedBy { it.second }
                            _speedTestResults.value = sorted
                            // 实时持久化测速结果
                            AppPrefs.speedTestResults = sorted
                        }
                        availableCount++
                    }
                    is IpTestResult.Fail -> { /* 失败不影响可用计数 */ }
                }
                // 合并 IpTester 的 completed/total 和我们自己维护的 available
                val tester = ipTester.progress.value
                _progress.value = TestProgress(
                    completed = tester.completed,
                    total = tester.total,
                    available = availableCount
                )
            }
            _isTesting.value = false
        }
    }

    fun cancelSpeedTest() {
        ipTester.cancelTest()
        _isTesting.value = false
    }

    fun testSingleIp(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _debugResult.value = DebugResult.Testing(ip)

            val latencies = mutableListOf<Long>()
            var successCount = 0
            var failCount = 0
            var lastError: String? = null

            repeat(repeatCount) {
                val result = ipTester.testSingleIp(ip, timeoutMs)
                when (result) {
                    is IpTestResult.Success -> {
                        latencies.add(result.latencyMs); successCount++
                    }
                    is IpTestResult.Fail -> {
                        failCount++; lastError = result.reason
                    }
                }
            }

            if (latencies.isNotEmpty()) {
                _debugResult.value = DebugResult.Success(
                    ip = ip,
                    avgLatencyMs = latencies.average().toLong(),
                    minLatencyMs = latencies.min(),
                    maxLatencyMs = latencies.max(),
                    successCount = successCount,
                    failCount = failCount
                )
            } else {
                _debugResult.value = DebugResult.Fail(
                    ip = ip,
                    reason = lastError ?: "所有测试均失败",
                    failCount = failCount
                )
            }
        }
    }

    /** 在线同步 IP 库（合并后写入 AppPrefs） */
    fun syncFromOnline() {
        viewModelScope.launch(Dispatchers.IO) {
            _syncStatus.value = SyncStatus.Loading("正在并行同步所有 IP 库...")

            try {
                val (syncedIps, results) = OnlineSyncService.syncAllServices()
                val successCount = results.count { it.ips != null }

                if (syncedIps.isNotEmpty()) {
                    val oldSize = _ipLibrary.value.size
                    val merged = (_ipLibrary.value.toSet() + syncedIps).toList()
                    _ipLibrary.value = merged
                    AppPrefs.ipLibrary = merged  // 持久化 IP 库

                    val detail = buildServiceDetail(results)
                    _syncStatus.value = SyncStatus.Success(
                        "同步成功: ${successCount}/${results.size} 服务可用，" +
                        "新增 ${merged.size - oldSize} 个 IP，总计 ${merged.size} 个\n$detail"
                    )
                } else {
                    _syncStatus.value = SyncStatus.Error(
                        "所有在线服务均不可用\n${buildServiceDetail(results)}"
                    )
                }
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus.Error("同步失败: ${e.message}")
            }
        }
    }

    private fun buildServiceDetail(results: List<SyncResult>): String =
        results.joinToString("\n") { r ->
            if (r.ips != null) "  ✓ ${r.serviceName}: ${r.ips.size} 个 IP"
            else "  ✗ ${r.serviceName}: ${r.errorMessage ?: "未知错误"}"
        }

    fun startScan(cidr: String, maxFound: Int = 50) {
        val ips = CidrUtil.generateIps(cidr, randomized = true).take(5000).toList()

        viewModelScope.launch {
            val ipTester = IpTester()
            val found = mutableSetOf<String>()
            _progress.value = TestProgress(0, ips.size, 0)
            _isTesting.value = true

            for (ip in ips) {
                if (found.size >= maxFound) break
                val result = ipTester.testSingleIp(ip, timeoutMs)
                _progress.value = _progress.value.copy(
                    completed = _progress.value.completed + 1
                )
                if (result is IpTestResult.Success) {
                    found.add(result.ip)
                    val updated = (_ipLibrary.value + ip).distinct()
                    _ipLibrary.value = updated
                    AppPrefs.ipLibrary = updated  // 持久化
                    _progress.value = _progress.value.copy(available = found.size)
                }
            }
            _isTesting.value = false
        }
    }

    /** 重置为内置 IP 库（清理 AppPrefs 持久化） */
    fun resetToBuiltin() {
        _ipLibrary.value = BuiltinIpDatabase.ipv4List
        _speedTestResults.value = emptyList()
        _progress.value = TestProgress(0, 0, 0)
        AppPrefs.clearIpLibrary()
        AppPrefs.speedTestResults = emptyList()
    }

    fun getBestIp(): String? = _speedTestResults.value.firstOrNull()?.first
}

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class Loading(val message: String) : SyncStatus()
    data class Success(val message: String) : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

sealed class DebugResult {
    data class Testing(val ip: String) : DebugResult()
    data class Success(
        val ip: String, val avgLatencyMs: Long,
        val minLatencyMs: Long, val maxLatencyMs: Long,
        val successCount: Int, val failCount: Int
    ) : DebugResult()
    data class Fail(val ip: String, val reason: String, val failCount: Int) : DebugResult()
}
