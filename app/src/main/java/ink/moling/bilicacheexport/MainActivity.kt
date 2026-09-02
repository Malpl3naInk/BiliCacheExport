package ink.moling.bilicacheexport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ink.moling.bilicacheexport.data.CacheViewModel
import ink.moling.bilicacheexport.ui.BiliCacheScreen
import ink.moling.bilicacheexport.ui.theme.BiliCacheExportTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CacheViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BiliCacheExportTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BiliCacheScreen(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「所有文件访问」授权页返回时，若权限状态变化则自动重扫
        viewModel.onAppResumed()
    }
}