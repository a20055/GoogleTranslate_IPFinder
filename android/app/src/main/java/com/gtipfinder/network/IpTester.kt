package com.gtipfinder.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.io.IOException
import kotlin.time.TimeSource

/**
 * IP 测试引擎
 * 
 * 移植自原 Python 项目的核心测速逻辑：
 * - 使用 TLS/SNI 伪装访问 translate.googleapis.com
 * - 并发测试多个 IP（Semaphore 控制并发数）
 * - 多次测试取平均延迟（repeat 次数）
 * 
 * 修复说明：
 * - 构造函数参数统一命名为 testHostname（与 OkHttpClientFactory 保持一致）
 * - 显式 import kotlinx.coroutines.sync.Semaphore（通配符 import 不覆盖子包）
 * - batchSpeedTest 重构：用 Channel 作为中间管道传递结果，
 *   launch 协程内 send()，flow builder 内 receive + emit()，
 *   避免嵌套协程中直接 emit 导致 IllegalStateException
 */
class IpTester(
    private val testHostname: String = "www.google.com",
    // 用 / 首页即可测速连通性，比 API 路径更稳定（任何 Google GWS IP 都能响应）
    private val requestTemplate: String = "https://{}/",
    private val defaultTimeout: Long = 1500,
    private val defaultRepeat: Int = 3,
    private val defaultConcurrency: Int = 32
) {
    companion object {
        private const val TAG = "IpTester"
    }

    // 测试进度状态
    private val _progress = MutableStateFlow(TestProgress(0, 0, 0))
    val progress = _progress.asStateFlow()

    private var testJob: Job? = null

    /**
     * 测试单个 IP 的延迟
     * 
     * @return 成功返回延迟毫秒数，失败返回错误信息
     */
    suspend fun testSingleIp(
        ip: String,
        timeoutMs: Long = defaultTimeout
    ): IpTestResult = withContext(Dispatchers.IO) {
        val client = OkHttpClientFactory.createForIpTest(
            targetIp = ip,
            serverHostname = testHostname,
            timeoutSec = timeoutMs / 1000L
        )

        // 关键：URL 里必须写 hostname，不能写 IP！
        // 这样 OkHttp 才会去解析 hostname → 触发自定义 DNS 钩子
        // → DNS 返回目标 IP → TLS SNI = hostname → 伪装访问成功
        // 如果 URL 里写 IP，DNS 钩子根本不触发 → 直接连 IP
        // → TLS SNI = IP（GWS 拒绝）→ 全部 Fail
        val url = requestTemplate.replace("{}", testHostname)

        val request = Request.Builder()
            .url(url)
            .get()
            // 注意：不要手动加 Host header！OkHttp 自动从 URL host 推导
            // 手动加可能干扰 OkHttp 的行为
            .build()

        val timeSource = TimeSource.Monotonic

        return@withContext try {
            val mark = timeSource.markNow()
            client.newCall(request).execute().use { response ->
                val latencyMs = mark.elapsedNow().inWholeMilliseconds
                if (response.isSuccessful || response.code in 300..399) {
                    IpTestResult.Success(ip, latencyMs)
                } else {
                    IpTestResult.Fail(ip, "HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            IpTestResult.Fail(ip, e.message ?: "Unknown error")
        }
    }

    /**
     * 批量测速 - 核心方法
     * 
     * 移植原 SpeedtestThread 逻辑：
     * 1. 随机打乱 IP 顺序（与 Python 版行为一致）
     * 2. 使用 Semaphore 控制并发
     * 3. 每个 IP 重复测试 N 次，取平均延迟
     * 4. 两次测试间隔 1 秒（避免触发频率限制）
     * 
     * 关键实现：用 Channel 作为 launch 协程与 flow builder 之间的结果管道。
     * launch 内部只做 Channel.send()（跨协程合法），
     * flow builder 内做 Channel.receive + emit()（保持在 Flow 的 collector 协程栈上，合法）。
     */
    fun batchSpeedTest(
        ips: List<String>,
        repeat: Int = defaultRepeat,
        concurrency: Int = defaultConcurrency,
        timeoutMs: Long = defaultTimeout,
        scope: CoroutineScope
    ): Flow<IpTestResult> = flow {
        // 重置进度
        _progress.value = TestProgress(0, ips.size, 0)
        
        // 随机打乱 IP 顺序（与 Python 版行为一致）
        val shuffled = ips.shuffled()
        
        // 使用信号量控制并发
        val semaphore = Semaphore(concurrency)

        // DEBUG log：验证并发设置是否真实传入
        Log.d(TAG, "batchSpeedTest: ips=${ips.size}, concurrency=$concurrency, " +
                "repeat=$repeat, timeoutMs=$timeoutMs, semaphorePermits=$concurrency")
        
        // Channel 作为中间管道：launch 里 send，flow builder 里 receive + emit
        val resultChannel = Channel<IpTestResult>(capacity = shuffled.size)
        
        // 在给定 scope 内启动测试协程（独立于 flow builder 协程）
        testJob = scope.launch(Dispatchers.IO) {
            var activeCount = 0
            var peakActive = 0
            val activeLock = Mutex()
            val runStart = System.currentTimeMillis()

            val testJobs = shuffled.map { ip ->
                async {
                    semaphore.acquire()
                    try {
                        // 记录活跃峰值：验证 Semaphore 并发控制是否生效
                        activeLock.withLock {
                            activeCount++
                            if (activeCount > peakActive) peakActive = activeCount
                        }

                        val latencies = mutableListOf<Long>()
                        var failed = false
                        
                        for (i in 0 until repeat) {
                            if (failed) break
                            
                            val result = testSingleIp(ip, timeoutMs)
                            when (result) {
                                is IpTestResult.Success -> {
                                    latencies.add(result.latencyMs)
                                    // 与 Python 版一致：非最后一次测试等待 1 秒
                                    if (i < repeat - 1) {
                                        delay(1000)
                                    }
                                }
                                is IpTestResult.Fail -> {
                                    failed = true
                                    resultChannel.send(result)
                                }
                            }
                        }
                        
                        if (!failed && latencies.isNotEmpty()) {
                            // 计算平均延迟（与 Python 版一致）
                            val avgLatency = latencies.average()
                            resultChannel.send(IpTestResult.Success(ip, avgLatency.toLong()))
                        }
                        
                        // 更新进度
                        _progress.value = TestProgress(
                            _progress.value.completed + 1,
                            ips.size,
                            _progress.value.available
                        )
                    } finally {
                        activeLock.withLock { activeCount-- }
                        semaphore.release()
                    }
                }
            }
            
            testJobs.forEach { it.join() }
            val totalMs = System.currentTimeMillis() - runStart
            Log.d(TAG, "batchSpeedTest 完成: concurrency=$concurrency, peakActive=$peakActive, " +
                    "totalIps=${ips.size}, 耗时=${totalMs}ms, " +
                    "并发控制${if (peakActive <= concurrency) "✅生效" else "⚠️超出！"} " +
                    "(峰值≤$concurrency)")
            resultChannel.close()
        }
        
        // 在 flow builder 内从 Channel 接收结果并 emit
        // 这样 emit 始终在 flow collector 协程栈上，合法且不会崩溃
        for (result in resultChannel) {
            emit(result)
        }
        
        // 确保测试协程完成后再退出 flow
        testJob?.join()
    }

    /**
     * 取消正在进行的测试
     */
    fun cancelTest() {
        testJob?.cancel()
        testJob = null
    }
}

/**
 * IP 测试结果密封类
 */
sealed class IpTestResult {
    abstract val ip: String
    
    /** 测试成功，包含延迟毫秒数 */
    data class Success(override val ip: String, val latencyMs: Long) : IpTestResult()
    
    /** 测试失败，包含原因 */
    data class Fail(override val ip: String, val reason: String) : IpTestResult()
}

/**
 * 测试进度数据类
 */
data class TestProgress(
    val completed: Int,
    val total: Int,
    val available: Int
)
