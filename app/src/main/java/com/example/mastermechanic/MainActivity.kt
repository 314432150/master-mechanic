package com.example.mastermechanic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.mastermechanic.ui.AuthorizationRoute
import com.example.mastermechanic.ui.CalibrationRoute
import com.example.mastermechanic.ui.FriendListRoute
import com.example.mastermechanic.ui.ServerListRoute
import com.example.mastermechanic.ui.theme.MasterMechanicTheme

class MainActivity : ComponentActivity() {

    /** 每次回到前台自增：从系统设置页返回后需要刷新授权状态（M0-T0-2）。 */
    private val resumeTick = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MasterMechanicTheme {
                // 页面切换：0 = 授权与运行，1 = 识别标定（T1-5b），
                // 2 = 跑号配置（2026-09-16 已按用户口径移除，编号留空不再使用），
                // 3 = 服务器清单（M3-T3-9），4 = 好友清单（M3-T3-8）
                var screen by rememberSaveable { mutableStateOf(0) }
                // 系统返回 / 边缘侧滑返回：**在子页按返回必须回主页，绝不能退出应用**
                // （UX 审核 2026-09-15 的 P0：此前没有 BackHandler，在清单页按返回键直接退出 App——
                //  录完一堆数据按一下返回就没了，还以为没保存）。
                BackHandler(enabled = screen != 0) { screen = 0 }
                when (screen) {
                    0 -> AuthorizationRoute(
                        resumeTick = resumeTick.intValue,
                        onOpenCalibration = { screen = 1 },
                        onOpenServerList = { screen = 3 },
                        onOpenFriendList = { screen = 4 },
                    )
                    1 -> CalibrationRoute(
                        resumeTick = resumeTick.intValue,
                        onBack = { screen = 0 },
                    )
                    3 -> ServerListRoute(
                        resumeTick = resumeTick.intValue,
                        onBack = { screen = 0 },
                    )
                    else -> FriendListRoute(
                        resumeTick = resumeTick.intValue,
                        onBack = { screen = 0 },
                    )
                }
            }
        }
    }
}
