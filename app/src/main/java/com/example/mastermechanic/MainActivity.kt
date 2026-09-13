package com.example.mastermechanic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.mastermechanic.ui.AuthorizationRoute
import com.example.mastermechanic.ui.CalibrationRoute
import com.example.mastermechanic.ui.PatrolConfigRoute
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
                // 页面切换：0 = 授权与运行，1 = 识别标定（T1-5b），2 = 巡查配置（M3-T3-3）
                var screen by rememberSaveable { mutableStateOf(0) }
                when (screen) {
                    0 -> AuthorizationRoute(
                        resumeTick = resumeTick.intValue,
                        onOpenCalibration = { screen = 1 },
                        onOpenPatrolConfig = { screen = 2 },
                    )
                    1 -> CalibrationRoute(
                        resumeTick = resumeTick.intValue,
                        onBack = { screen = 0 },
                    )
                    else -> PatrolConfigRoute(
                        resumeTick = resumeTick.intValue,
                        onBack = { screen = 0 },
                    )
                }
            }
        }
    }
}
