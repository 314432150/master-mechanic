package com.example.mastermechanic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.example.mastermechanic.ui.AuthorizationRoute
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
                AuthorizationRoute(resumeTick = resumeTick.intValue)
            }
        }
    }
}
