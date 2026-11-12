package com.gtipfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gtipfinder.data.AppPrefs
import com.gtipfinder.ui.MainScreen
import com.gtipfinder.ui.theme.GTIPFinderTheme

/**
 * 主 Activity - 应用入口
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化持久化（应用私有存储，无需任何权限）
        AppPrefs.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            GTIPFinderTheme {
                MainScreen()
            }
        }
    }
}
