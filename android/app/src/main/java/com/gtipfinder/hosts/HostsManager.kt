package com.gtipfinder.hosts

import android.content.Context
import android.os.Build
import com.gtipfinder.data.BuiltinIpDatabase
import java.io.File

/**
 * Hosts 文件管理器
 * 
 * Android 系统的 /system/etc/hosts 通常是只读的，
 * 本应用提供以下方式来使用测速结果：
 * 
 * 1. **导出 Hosts 片段** - 用户手动复制到 hosts 文件（需要 root）
 * 2. **通过 Magisk/KernelSU 模块** - 将 hosts 写入 /data/adb/modules/xxx/system/etc/hosts.d/
 * 3. **设置自定义 DNS** - 将结果用于代理工具
 */
class HostsManager(private val context: Context) {

    /**
     * 生成 Hosts 格式内容
     * 
     * @param bestIp 测速得到的最佳 IP
     * @param domains 需要指向该 IP 的域名列表
     * @return Hosts 文件片段字符串
     */
    fun generateHostsContent(bestIp: String, domains: List<String> = BuiltinIpDatabase.HOSTS_DOMAINS): String {
        val lines = mutableListOf<String>()
        lines.add("# GTIPFinder - 谷歌翻译最佳 IP 配置")
        lines.add("# 生成时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        lines.add("# 最佳 IP: $bestIp")
        lines.add("")
        
        for (domain in domains) {
            lines.add("$bestIp\t$domain")
        }
        
        return lines.joinToString("\n")
    }

    /**
     * 导出 Hosts 片段到应用私有目录
     */
    suspend fun exportHostsToFile(bestIp: String): Result<String> {
        return try {
            val content = generateHostsContent(bestIp)
            val fileName = "gtipfinder_hosts_${System.currentTimeMillis()}.txt"
            val file = File(context.filesDir, fileName)
            file.writeText(content)
            Result.success(file.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 检测设备是否有 root 权限
     */
    fun isRooted(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.waitFor()
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取 Magisk/KernelSU hosts 模块路径
     */
    fun getModuleHostsPath(): String? {
        val magiskPath = "/data/adb/modules/"
        val dir = File(magiskPath)
        if (dir.exists() && dir.isDirectory) {
            // 查找 GTIPFinder 相关模块，或者创建新的
            return "$magiskPath/gtipfinder/system/etc/hosts.d/translate"
        }
        return null
    }

    companion object {
        /**
         * 系统 Hosts 文件路径
         */
        const val SYSTEM_HOSTS_PATH = "/system/etc/hosts"
        
        /**
         * 检查当前 Android 版本
         */
        val androidVersion: Int
            get() = Build.VERSION.SDK_INT
    }
}
