package com.gtipfinder.data

import com.gtipfinder.network.OkHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 在线 IP 库同步服务
 * 
 * 移植原 Python 版的 read_url / read_urls_parallel 逻辑。
 * 
 * v2 修复说明：
 * - syncAllServices 改为真正并行请求所有 OnlineService（async/awaitAll），
 *   避免串行 for 循环中单个慢/被墙的服务阻塞整体同步
 * - IP 解析更健壮：按行分割 + trim + 过滤 # 注释行 + 过滤空行
 * - syncFromService 返回 SyncResult，包含成功/失败详情供 UI 展示
 */
class OnlineSyncService {

    /**
     * 单个 OnlineService 的同步结果
     */
    data class SyncResult(
        val serviceName: String,
        val ips: Set<String>? = null,   // 成功时非 null
        val errorUrl: String? = null,    // 所有 URL 都失败时记录最后一个
        val errorMessage: String? = null
    )

    /**
     * 从单个 OnlineService 同步 IP 列表
     * 
     * @return SyncResult 总是返回（成功或失败都有结果对象）
     */
    suspend fun syncFromService(service: OnlineService): SyncResult =
        withContext(Dispatchers.IO) {
            val client = OkHttpClientFactory.createForSync()
            var lastErrorUrl: String? = null
            var lastErrorMessage: String? = null

            for (url in service.urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept-Encoding", "gzip, deflate, br")
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (!body.isNullOrBlank()) {
                                // 健壮解析：按行分割 → trim → 过滤空行和注释行 → 验证 IP
                                val ips = body.lineSequence()
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() && !it.startsWith('#') }
                                    .flatMap { line ->
                                        // 每行可能含逗号/空格分隔的多个 IP
                                        line.split(Regex("[\\s,;]+"))
                                    }
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() && isValidIp(it) }
                                    .toSet()

                                if (ips.isNotEmpty()) {
                                    return@withContext SyncResult(
                                        serviceName = service.name,
                                        ips = ips
                                    )
                                } else {
                                    lastErrorUrl = url
                                    lastErrorMessage = "响应中未找到有效 IP"
                                }
                            } else {
                                lastErrorUrl = url
                                lastErrorMessage = "响应体为空"
                            }
                        } else {
                            lastErrorUrl = url
                            lastErrorMessage = "HTTP ${response.code}"
                        }
                    }
                } catch (e: Exception) {
                    lastErrorUrl = url
                    lastErrorMessage = e.message ?: "未知错误"
                }
                // 继续尝试下一个 URL（for 循环自动前进）
            }

            // 所有备用 URL 都失败
            SyncResult(
                serviceName = service.name,
                errorUrl = lastErrorUrl,
                errorMessage = lastErrorMessage
            )
        }

    /**
     * 验证字符串是否为有效的 IP 地址（IPv4 或 IPv6）
     * 
     * 用正则而非简单的 contains(':') 来过滤 IPv6，更安全。
     */
    private fun isValidIp(ip: String): Boolean {
        // IPv4: x.x.x.x 格式
        if (IPv4_REGEX.matches(ip)) return true
        // IPv6: 含冒号且长度在合理范围
        if (':' in ip && ip.length in 3..45) return true
        return false
    }

    companion object {
        private val IPv4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

        /**
         * 并行从所有 OnlineService 获取 IP 列表
         * 
         * 用 async/awaitAll 真正并行，所有服务同时发起请求，
         * 不互相阻塞，整体耗时 = 最慢那个服务的耗时。
         * 
         * @return 合并去重后的 IP 集合 + 每个服务的详细状态
         */
        suspend fun syncAllServices(): Pair<Set<String>, List<SyncResult>> {
            val syncService = OnlineSyncService()

            // coroutineScope 给 async/awaitAll 提供 CoroutineScope receiver
            val results: List<SyncResult> = coroutineScope {
                BuiltinIpDatabase.onlineServices
                    .map { service ->
                        async { syncService.syncFromService(service) }
                    }
                    .awaitAll()
            }

            // 合并成功的结果
            val merged = mutableSetOf<String>()
            for (result in results) {
                result.ips?.let { merged.addAll(it) }
            }

            return merged to results
        }
    }
}
