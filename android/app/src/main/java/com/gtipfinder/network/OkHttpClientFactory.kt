package com.gtipfinder.network

import okhttp3.*
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * OkHttp 客户端工厂
 * 
 * 核心原理：OkHttp 原生支持通过自定义 DNS 将域名解析到指定 IP，
 * 同时保留原始的 TLS SNI 和 HTTP Host Header，实现 IP 伪装访问。
 */
object OkHttpClientFactory {

    /**
     * 创建用于 IP 测试的 OkHttpClient
     * 
     * @param targetIp 要测试的目标 IP 地址
     * @param serverHostname 服务器主机名（用于 SNI 和 Host header）
     * @param timeoutSec 超时时间（秒）
     * 
     * 关键实现：使用自定义 DNS 钩子，让 OkHttp 认为目标域名解析到了指定 IP，
     * 但 TLS 握手时仍使用 serverHostname 进行 SNI，HTTP 请求头仍使用 Host: serverHostname
     */
    fun createForIpTest(
        targetIp: String,
        serverHostname: String,
        timeoutSec: Long = 2
    ): OkHttpClient {
        return OkHttpClient.Builder()
            // 关键：自定义 DNS，将 serverHostname 解析到目标 IP
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    // 只伪装目标服务器的 DNS 解析
                    if (hostname.equals(serverHostname, ignoreCase = true)) {
                        return try {
                            listOf(InetAddress.getByName(targetIp))
                        } catch (e: UnknownHostException) {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    }
                    // 其他域名使用系统 DNS
                    return Dns.SYSTEM.lookup(hostname)
                }
            })
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // 不设全局超时
            // 禁用连接池，每次请求独立
            .connectionPool(ConnectionPool(0, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 创建用于在线同步的普通 OkHttpClient
     */
    fun createForSync(timeoutSec: Long = 15): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
    }
}
