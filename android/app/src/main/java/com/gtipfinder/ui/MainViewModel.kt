package com.gtipfinder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 */
class MainViewModel : ViewModel() {

    // IP 测试引擎实例
    private val ipTester = IpTester()

    // 当前 IP 库（内置 + 在线同步）
    private val _ipLibrary = MutableStateFlow<List<String>>(BuiltinIpDatabase.ipv4List)
    val ipLibrary: StateFlow<List<String>> = _ipLibrary.asStateFlow()

    // 测速结果：可用 IP -> 平均延迟毫秒数
    private val _speedTestResults = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val speedTestResults: StateFlow<List<Pair<String, Long>>> = _speedTestResults.asStateFlow()

    // 当前测试进度
    private val _progress = MutableStateFlow(TestProgress(0, 0, 0))
    val progress: StateFlow<TestProgress> = _progress.asStateFlow()

    // 测试是否正在运行
    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    // 在线同步状态
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    // 调试结果（单个 IP 测试）
    private val _debugResult = MutableStateFlow<DebugResult?>(null)
    val debugResult: StateFlow<DebugResult?> = _debugResult.asStateFlow()

    // 用户设置
    var timeoutMs: Long = 1500
    var repeatCount: Int = 3
    var concurrency: Int = 32

    /**
     * 启动批量测速
     */
    fun startSpeedTest() {
        if (_isTesting.value) return
        
        _isTesting.value = true
        _speedTestResults.value = emptyList()
        val available = mutableListOf<Pair<String, Long>>()
        val seen = mutableSetOf<String>()
        
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
                        // 计算平均延迟并更新
                        val latencyMs = result.latencyMs
                        if (!seen.contains(result.ip)) {
                            seen.add(result.ip)
                            available.add(result.ip to latencyMs)
                            // 按延迟排序（延迟越低越优）
                            _speedTestResults.value = available.sortedBy { it.second }
                        }
                        // 更新可用数量
                        _progress.value = _progress.value.copy(
                            available = _progress.value.available + 1
                        )
                    }
                    is IpTestResult.Fail -> {
                        // 测试失败，仅更新进度
                    }
                }
                // 同步进度
                _progress.value = ipTester.progress.value
            }
            _isTesting.value = false
        }
    }

    /**
     * 取消测速
     */
    fun cancelSpeedTest() {
        ipTester.cancelTest()
        _isTesting.value = false
    }

    /**
     * 测试单个 IP（调试模式）
     */
    fun testSingleIp(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _debugResult.value = DebugResult.Testing(ip)
            
            val latencies = mutableListOf<Long>()
            var successCount = 0
            var failCount = 0
            var lastError: String? = null

            repeat(repeatCount) { index ->
                val result = ipTester.testSingleIp(ip, timeoutMs)
                when (result) {
                    is IpTestResult.Success -> {
                        latencies.add(result.latencyMs)
                        successCount++
                    }
                    is IpTestResult.Fail -> {
                        failCount++
                        lastError = result.reason
                    }
                }
            }

            if (latencies.isNotEmpty()) {
                val avgLatency = latencies.average().toLong()
                _debugResult.value = DebugResult.Success(
                    ip = ip,
                    avgLatencyMs = avgLatency,
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

    /**
     * 从在线服务同步 IP 库
     * 
     * v2: OnlineSyncService.syncAllServices() 现在返回 Pair<Set<String>, List<SyncResult>>，
     *     第一个元素是合并去重后的 IP 集合，第二个是每个 OnlineService 的详细状态。
     */
    fun syncFromOnline() {
        viewModelScope.launch(Dispatchers.IO) {
            _syncStatus.value = SyncStatus.Loading("正在并行同步所有 IP 库...")
            
            try {
                // 并行请求所有 OnlineService，拿到合并 IP + 每个服务的详细状态
                val (syncedIps, results) = OnlineSyncService.syncAllServices()
                
                // 统计成败
                val successCount = results.count { it.ips != null }
                val failCount = results.size - successCount
                
                if (syncedIps.isNotEmpty()) {
                    // 合并内置 IP 和在线 IP，去重
                    val oldSize = _ipLibrary.value.size
                    val merged = (_ipLibrary.value.toSet() + syncedIps).toList()
                    _ipLibrary.value = merged
                    
                    val newCount = merged.size - oldSize
                    val detail = buildServiceDetail(results)
                    _syncStatus.value = SyncStatus.Success(
                        "同步成功: ${successCount}/${results.size} 服务可用，新增 ${newCount} 个 IP，总计 ${merged.size} 个\n$detail"
                    )
                } else {
                    // 所有服务都失败了
                    val detail = buildServiceDetail(results)
                    _syncStatus.value = SyncStatus.Error(
                        "所有在线服务均不可用 (${failCount}/${results.size})\n$detail"
                    )
                }
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus.Error("同步失败: ${e.message}")
            }
        }
    }

    /**
     * 根据 SyncResult 列表生成可读的成功/失败详情文本
     */
    private fun buildServiceDetail(results: List<SyncResult>): String {
        return results.joinToString("\n") { r ->
            if (r.ips != null) {
                "  ✓ ${r.serviceName}: ${r.ips.size} 个 IP"
            } else {
                "  ✗ ${r.serviceName}: ${r.errorMessage ?: "未知错误"}"
            }
        }
    }

    /**
     * 执行 CIDR 扫描
     */
    fun startScan(cidr: String, maxFound: Int = 50) {
        // 简化版：生成 IP 并逐个测试
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
                    _ipLibrary.value = (_ipLibrary.value + ip).distinct()
                    _progress.value = _progress.value.copy(
                        available = found.size
                    )
                }
            }
            
            _isTesting.value = false
        }
    }

    /**
     * 重置为内置 IP 库
     */
    fun resetToBuiltin() {
        _ipLibrary.value = BuiltinIpDatabase.ipv4List
        _speedTestResults.value = emptyList()
        _progress.value = TestProgress(0, 0, 0)
    }

    /**
     * 获取最佳 IP（延迟最低）
     */
    fun getBestIp(): String? = _speedTestResults.value.firstOrNull()?.first
}

/**
 * 在线同步状态密封类
 */
sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class Loading(val message: String) : SyncStatus()
    data class Success(val message: String) : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

/**
 * 单 IP 调试结果密封类
 */
sealed class DebugResult {
    data class Testing(val ip: String) : DebugResult()
    data class Success(
        val ip: String,
        val avgLatencyMs: Long,
        val minLatencyMs: Long,
        val maxLatencyMs: Long,
        val successCount: Int,
        val failCount: Int
    ) : DebugResult()
    data class Fail(val ip: String, val reason: String, val failCount: Int) : DebugResult()
}
