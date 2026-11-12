package com.gtipfinder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gtipfinder.data.BuiltinIpDatabase

/**
 * 主界面 - 测速/扫描/设置三标签页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val viewModel: MainViewModel = viewModel()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("测速", "扫描", "调试", "设置")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "GTIPFinder",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                when (index) {
                                    0 -> Icons.Default.Speed
                                    1 -> Icons.Default.Search
                                    2 -> Icons.Default.BugReport
                                    else -> Icons.Default.Settings
                                },
                                contentDescription = tab
                            )
                        },
                        label = { Text(tab) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> SpeedTestScreen(viewModel)
                1 -> ScanScreen(viewModel)
                2 -> DebugScreen(viewModel)
                3 -> SettingsScreen(viewModel)
            }
        }
    }
}

/**
 * 测速页面
 */
@Composable
fun SpeedTestScreen(viewModel: MainViewModel) {
    val isTesting by viewModel.isTesting.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val results by viewModel.speedTestResults.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val ipLibrary by viewModel.ipLibrary.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 控制按钮区
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isTesting) {
                Button(
                    onClick = { viewModel.startSpeedTest() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始测速 (${ipLibrary.size} 个IP)")
                }
                OutlinedButton(
                    onClick = { viewModel.syncFromOnline() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("在线同步")
                }
            } else {
                Button(
                    onClick = { viewModel.cancelSpeedTest() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935)
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("取消测速")
                }
            }
        }

        // 在线同步状态提示
        when (syncStatus) {
            is SyncStatus.Loading -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text((syncStatus as SyncStatus.Loading).message, fontSize = 12.sp)
            }
            is SyncStatus.Success -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Text(
                        (syncStatus as SyncStatus.Success).message,
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF2E7D32)
                    )
                }
            }
            is SyncStatus.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                ) {
                    Text(
                        (syncStatus as SyncStatus.Error).message,
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFFC62828)
                    )
                }
            }
            is SyncStatus.Idle -> {}
        }

        // 进度卡片
        if (progress.total > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "进度: ${progress.completed}/${progress.total}",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "可用: ${progress.available}",
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val percent = if (progress.total > 0) progress.completed.toFloat() / progress.total else 0f
                    LinearProgressIndicator(
                        progress = { percent },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // 结果列表
        Text(
            "测速结果",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        if (results.isEmpty() && !isTesting) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("暂无测速结果，请点击开始测速")
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(results.take(50)) { (ip, latency) ->
                    ResultItem(ip, latency)
                }
            }
        }

        // 最佳 IP 提示
        viewModel.getBestIp()?.let { bestIp ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFA000)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("最佳 IP: $bestIp", fontWeight = FontWeight.Bold)
                        Text(
                            "写入 Hosts 即可使用",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条测速结果项
 * 
 * 交互：
 * - 点击：弹出菜单（复制 IP / 复制完整行 / 复制 Hosts 格式）
 * - 长按：直接复制 IP 到剪贴板
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ResultItem(ip: String, latencyMs: Long) {
    val latencyColor = when {
        latencyMs < 200 -> Color(0xFF2E7D32)
        latencyMs < 500 -> Color(0xFFF57C00)
        else -> Color(0xFFC62828)
    }
    
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { showMenu = true },
                    onLongClick = { copyToClipboard(context, ip, "IP", ip) }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = latencyColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    ip,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            Text(
                "${latencyMs}ms",
                fontWeight = FontWeight.Bold,
                color = latencyColor
            )
        }
    }

    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("复制 IP") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
            onClick = { showMenu = false; copyToClipboard(context, ip, "IP", ip) }
        )
        DropdownMenuItem(
            text = { Text("复制完整行") },
            leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
            onClick = { showMenu = false; copyToClipboard(context, "$ip  ${latencyMs}ms", "测速结果", ip) }
        )
        DropdownMenuItem(
            text = { Text("复制 Hosts 格式") },
            leadingIcon = { Icon(Icons.Default.Gavel, null) },
            onClick = { showMenu = false; copyToClipboard(context, "$ip www.google.com", "Hosts", ip) }
        )
    }
}

/**
 * 将文本复制到系统剪贴板并 Toast 提示
 */
private fun copyToClipboard(context: Context, text: String, label: String, toastMsg: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制: $toastMsg", Toast.LENGTH_SHORT).show()
}

/**
 * CIDR 扫描页面
 */
@Composable
fun ScanScreen(viewModel: MainViewModel) {
    val isTesting by viewModel.isTesting.collectAsState()
    val progress by viewModel.progress.collectAsState()
    var selectedCidr by remember { mutableStateOf(BuiltinIpDatabase.scanRanges.first().cidr) }
    var customCidr by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("CIDR 扫描", fontWeight = FontWeight.Bold, fontSize = 18.sp)

        // 预设网段选择
        Text("选择预设网段:", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BuiltinIpDatabase.scanRanges.forEach { range ->
                FilterChip(
                    selected = selectedCidr == range.cidr,
                    onClick = { selectedCidr = range.cidr; customCidr = "" },
                    label = { Text(range.cidr) }
                )
            }
        }

        // 自定义网段输入
        OutlinedTextField(
            value = customCidr,
            onValueChange = { customCidr = it; if (it.isNotBlank()) selectedCidr = it },
            label = { Text("或输入自定义 CIDR (如 142.250.0.0/15)") },
            modifier = Modifier.fillMaxWidth()
        )

        // 开始扫描按钮
        Button(
            onClick = { viewModel.startScan(selectedCidr) },
            enabled = !isTesting && selectedCidr.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("开始扫描")
        }

        // 扫描进度
        if (progress.total > 0 || isTesting) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("扫描进度: ${progress.completed}")
                    Text("发现可用: ${progress.available}", color = Color(0xFF2E7D32))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "提示: CIDR 扫描范围较大，可能需要较长时间。建议使用内置 IP 库进行测速。",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

/**
 * 调试页面 - 单个 IP 测试
 */
@Composable
fun DebugScreen(viewModel: MainViewModel) {
    val debugResult by viewModel.debugResult.collectAsState()
    var inputIp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("单 IP 调试", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("输入单个 IP 进行多次稳定性测试", fontSize = 14.sp, color = Color.Gray)

        OutlinedTextField(
            value = inputIp,
            onValueChange = { inputIp = it },
            label = { Text("IP 地址") },
            placeholder = { Text("例如: 142.250.75.1") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { if (inputIp.isNotBlank()) viewModel.testSingleIp(inputIp) },
            enabled = inputIp.isNotBlank() && debugResult !is DebugResult.Testing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.BugReport, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("测试")
        }

        // 调试结果展示
        debugResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (result) {
                        is DebugResult.Success -> Color(0xFFE8F5E9)
                        is DebugResult.Fail -> Color(0xFFFFEBEE)
                        is DebugResult.Testing -> Color(0xFFE3F2FD)
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (result) {
                        is DebugResult.Testing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("正在测试 ${result.ip}...")
                            }
                        }
                        is DebugResult.Success -> {
                            Text("✓ 测试通过", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            Spacer(Modifier.height(8.dp))
                            Text("IP: ${result.ip}")
                            Text("平均延迟: ${result.avgLatencyMs}ms")
                            Text("最小延迟: ${result.minLatencyMs}ms")
                            Text("最大延迟: ${result.maxLatencyMs}ms")
                            Text("成功率: ${result.successCount}/${result.successCount + result.failCount}")
                        }
                        is DebugResult.Fail -> {
                            Text("✗ 测试失败", fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                            Spacer(Modifier.height(8.dp))
                            Text("IP: ${result.ip}")
                            Text("失败原因: ${result.reason}")
                            Text("失败次数: ${result.failCount}")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置页面
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)

        // 超时设置
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("请求超时", fontWeight = FontWeight.Medium)
                Text("每次 HTTP 请求的超时时间", fontSize = 12.sp, color = Color.Gray)
            }
            var timeoutSlider by remember { mutableFloatStateOf(viewModel.timeoutMs.toFloat()) }
            Column(horizontalAlignment = Alignment.End) {
                Text("${timeoutSlider.toLong()}ms")
                Slider(
                    value = timeoutSlider,
                    onValueChange = { timeoutSlider = it; viewModel.timeoutMs = it.toLong() },
                    valueRange = 500f..5000f,
                    steps = 8,
                    modifier = Modifier.width(120.dp)
                )
            }
        }

        HorizontalDivider()

        // 重复测试次数
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("重复测试次数", fontWeight = FontWeight.Medium)
                Text("每个 IP 连续测试的次数（建议 IPv6 设为 10）", fontSize = 12.sp, color = Color.Gray)
            }
            var repeatSlider by remember { mutableIntStateOf(viewModel.repeatCount) }
            Column(horizontalAlignment = Alignment.End) {
                Text("$repeatSlider 次")
                Slider(
                    value = repeatSlider.toFloat(),
                    onValueChange = { repeatSlider = it.toInt(); viewModel.repeatCount = it.toInt() },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.width(120.dp)
                )
            }
        }

        HorizontalDivider()

        // 并发数
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("并发数", fontWeight = FontWeight.Medium)
                Text("同时测试的 IP 数量，越大越快但越消耗资源", fontSize = 12.sp, color = Color.Gray)
            }
            var concurrencySlider by remember { mutableIntStateOf(viewModel.concurrency) }
            Column(horizontalAlignment = Alignment.End) {
                Text("$concurrencySlider")
                Slider(
                    value = concurrencySlider.toFloat(),
                    onValueChange = { concurrencySlider = it.toInt(); viewModel.concurrency = it.toInt() },
                    valueRange = 4f..96f,
                    steps = 22,
                    modifier = Modifier.width(120.dp)
                )
            }
        }

        HorizontalDivider()

        // 重置按钮
        OutlinedButton(
            onClick = { viewModel.resetToBuiltin() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("重置为内置 IP 库")
        }

        Spacer(Modifier.height(16.dp))

        // 版本信息
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GTIPFinder v1.0.0", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("谷歌翻译 IP 测速工具 (Android 版)")
                Spacer(Modifier.height(4.dp))
                Text("移植自: GoodCoder666/GoogleTranslate_IPFinder", fontSize = 12.sp, color = Color.Gray)
                Text("License: GPL-3.0", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}
