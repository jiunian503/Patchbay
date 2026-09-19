package com.aichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aichat.theme.PatchbayTheme

/**
 * 唯一的 Activity。
 *
 * 依赖容器从 Application 取，不在这里 new —— 那样每次 Activity 重建
 * （旋屏、深色模式切换）都会重开一遍数据库连接和 KeyStore 会话。
 *
 * `enableEdgeToEdge()` 之后不再额外加 `safeDrawingPadding()`：
 * 每个屏幕的 Scaffold 会自己处理系统栏内边距，外面再加一层会推两次。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as PatchbayApp).container

        enableEdgeToEdge()
        setContent {
            PatchbayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainNavigation(container)
                }
            }
        }
    }
}
