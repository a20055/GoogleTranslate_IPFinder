package com.gtipfinder.scan

import kotlin.math.pow

/**
 * CIDR 工具类
 * 
 * 移植原 Python 版 ipaddress 模块的核心功能：
 * - CIDR 解析
 * - IP 生成
 * - 子网划分
 */
object CidrUtil {

    /**
     * 解析 CIDR 表示法，生成该网段内的所有 IP
     * 
     * 与 Python 版 ip_generator 保持一致的逻辑：
     * - 当网段 <= 256 个地址时直接遍历
     * - 较大网段时进行分块（每个 chunk 256 个地址），然后随机交错生成
     */
    fun generateIps(cidr: String, randomized: Boolean = false): Sequence<String> {
        val (network, prefix) = parseCidr(cidr)
        val totalAddresses = 2.0.pow(32 - prefix).toInt()
        
        // 生成 chunk 列表
        val chunks = when {
            totalAddresses <= 256 -> listOf(network to prefix)
            totalAddresses > 131072 -> splitIntoSubnets(network, prefix, 9)
            else -> splitIntoSubnets(network, prefix, 8)
        }
        
        return if (randomized) {
            generateRandomized(chunks)
        } else {
            generateSequential(chunks)
        }
    }

    /**
     * 解析 CIDR 表示法
     * @return Pair(networkAddress, prefixLength)
     */
    fun parseCidr(cidr: String): Pair<Long, Int> {
        val parts = cidr.split('/')
        val ipStr = parts[0]
        val prefix = parts[1].toInt()
        
        val ipLong = ipToLong(ipStr)
        val mask = if (prefix == 0) 0 else (-1L shl (32 - prefix)).toLong()
        val network = ipLong and mask
        
        return network to prefix
    }

    /**
     * 将 IPv4 地址转换为 Long
     */
    fun ipToLong(ip: String): Long {
        val parts = ip.split('.').map { it.toLong() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    /**
     * 将 Long 转换为 IPv4 地址
     */
    fun longToIp(value: Long): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    /**
     * 将大网段划分为若干小子网
     */
    private fun splitIntoSubnets(network: Long, prefix: Int, bits: Int): List<Pair<Long, Int>> {
        val newPrefix = prefix + bits
        val count = 2.0.pow(bits).toInt()
        val results = mutableListOf<Pair<Long, Int>>()
        val increment = 1L shl (32 - newPrefix)
        
        for (i in 0 until count) {
            results.add((network + i * increment) to newPrefix)
        }
        return results
    }

    /**
     * 顺序生成所有 IP
     */
    private fun generateSequential(chunks: List<Pair<Long, Int>>): Sequence<String> = sequence {
        for ((network, prefix) in chunks) {
            val count = 2.0.pow(32 - prefix).toInt()
            // 跳过网络地址和广播地址，生成 hosts
            for (i in 1 until count - 1) {
                yield(longToIp(network + i))
            }
        }
    }

    /**
     * 随机交错生成 IP
     * 与 Python 版的随机生成器行为一致
     */
    private fun generateRandomized(chunks: List<Pair<Long, Int>>): Sequence<String> = sequence {
        val generators = chunks.map { (network, prefix) ->
            val count = 2.0.pow(32 - prefix).toInt()
            // 每个 chunk 的 host IP 列表（不含网络/广播地址）
            (1 until count - 1).map { longToIp(network + it) }.iterator()
        }.toMutableList()
        
        val random = java.util.Random()
        
        while (generators.isNotEmpty()) {
            // 随机打乱生成器顺序
            generators.shuffle(random)
            val remaining = mutableListOf<Iterator<String>>()
            
            for (gen in generators) {
                if (gen.hasNext()) {
                    yield(gen.next())
                    remaining.add(gen)
                }
            }
            generators.clear()
            generators.addAll(remaining)
        }
    }
}
