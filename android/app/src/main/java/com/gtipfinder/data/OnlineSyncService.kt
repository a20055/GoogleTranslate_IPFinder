package com.gtipfinder.data

import com.gtipfinder.network.OkHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 在线 IP 库同步服务
 * 
 * 移植原 Python 版的 read_url / read_urls_parallel 逻辑：
 * - 从多个备用 URL 中获取 IP 列表
 * - 并行请求，使用首先成功的结果
 */
class OnlineSyncService {

    /**
     * 从在线服务同步 IP 列表
     * 
     * @param service 在线服务配置
     * @return IP 集合，失败返回 null
     */
    suspend fun syncFromService(service: OnlineService): Set<String>? =
        withContext(Dispatchers.IO) {
            val client = OkHttpClientFactory.createForSync()
            
            for (url in service.urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept-Encoding", "gzip, deflate, br")
                        .get()
                        .build()
                    
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return@use null
                            // 解析 IP 列表（按空白字符分割）
                            val ips = body.split(Regex("\\s+"))
                                .filter { it.isNotBlank() && isValidIp(it) }
                                .toSet()
                            // 显式加 else Unit 避免 Kotlin 把外层块当作表达式
                            if (ips.isNotEmpty()) return@withContext ips else Unit
                        } else Unit
                    }
                } catch (_: Exception) {
                    // 当前 URL 失败，尝试下一个
                    continue
                }
            }
            null
        }

    /**
     * 验证字符串是否为有效的 IP 地址（IPv4 或 IPv6）
     */
    private fun isValidIp(ip: String): Boolean {
        // 简单的 IPv4 验证
        if (ip.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) {
            return true
        }
        // 简单的 IPv6 验证
        if (ip.contains(':') && ip.length in 3..45) {
            return true
        }
        return false
    }

    companion object {
        /**
         * 并行从多个服务获取 IP 列表，合并去重
         */
        suspend fun syncAllServices(): Set<String> {
            val syncService = OnlineSyncService()
            val merged = mutableSetOf<String>()
            
            for (service in BuiltinIpDatabase.onlineServices) {
                val ips = syncService.syncFromService(service)
                if (ips != null) {
                    merged.addAll(ips)
                }
            }
            return merged
        }
    }
}
