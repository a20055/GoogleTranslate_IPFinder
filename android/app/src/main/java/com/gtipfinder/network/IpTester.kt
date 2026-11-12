package com.gtipfinder.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * IP 测试引擎
 * 
 * 移植自原 Python 项目的核心测速逻辑：
 * - 使用 TLS/SNI 伪装访问 translate.googleapis.com
 * - 并发测试多个 IP（Semaphore 控制并发数）
 * - 多次测试取平均延迟（repeat 次数）
 */
class IpTester(
    private val testHost: String = "translate.googleapis.com",
    private val requestTemplate: String = "https://{}/translate_a/element.js",
    private val defaultTimeout: Long = 1500,
    private val defaultRepeat: Int = 3,
    private val defaultConcurrency: Int = 32
) {
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

        val url = requestTemplate.format(
            if (ip.contains(':')) "[$ip]" else ip
        )

        val request = Request.Builder()
            .url(url)
            .header("Host", testHostname)
            .get()
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
     * 1. 随机打乱 IP 顺序
     * 2. 使用 Semaphore 控制并发
     * 3. 每个 IP 重复测试 N 次，取平均延迟
     * 4. 两次测试间隔 1 秒（避免触发频率限制）
     * 
     * @param ips 待测试的 IP 列表
     * @param repeat 每个 IP 的测试次数
     * @param concurrency 并发数
     * @param timeoutMs 单次请求超时
     * @return 按延迟排序的可用 IP 列表
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
        
        val results = Channel<Pair<String, Double>>(capacity = shuffled.size)
        
        testJob = scope.launch(Dispatchers.IO) {
            val testJobs = shuffled.map { ip ->
                async {
                    semaphore.acquire()
                    try {
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
                                    emit(result)
                                }
                            }
                        }
                        
                        if (!failed && latencies.isNotEmpty()) {
                            // 计算平均延迟（与 Python 版一致）
                            val avgLatency = latencies.average()
                            results.send(ip to avgLatency)
                            emit(IpTestResult.Success(ip, avgLatency.toLong()))
                        }
                        
                        // 更新进度
                        _progress.value = TestProgress(
                            _progress.value.completed + 1,
                            ips.size,
                            _progress.value.available
                        )
                    } finally {
                        semaphore.release()
                    }
                }
            }
            
            testJobs.forEach { it.join() }
            results.close()
        }
        
        // 等待完成
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
