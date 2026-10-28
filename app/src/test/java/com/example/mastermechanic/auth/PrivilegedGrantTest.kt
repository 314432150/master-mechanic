package com.example.mastermechanic.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「命令授权」脚本的内容**（2026-10-25）：单测只钉"给对东西、给对包名"——
 * 这份脚本会被用户原样粘进 adb 跑，写错了（包名拼错 / 少一条 `pm grant`）会静默失效，很难在界面上看出来。
 */
class PrivilegedGrantTest {

    private val pkg = "com.example.mastermechanic"

    @Test
    fun scriptGrantsExactlyWhatTheSwitchNeeds() {
        val lines = PrivilegedGrant.scriptLines(pkg)
        assertEquals("第一行必须是 shebang，否则 `sh 文件` 在某些 ROM 上行为不一致", "#!/system/bin/sh", lines.first())
        assertTrue(
            "少了这条，无障碍开关就是假的（点了只会跳系统设置）",
            lines.contains("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"),
        )
        // 两个 app-op 在部分 ROM 上会被忽略，但别的 ROM 上缺了可能仍被"受限设置"拦住 ⇒ 必须一并写上
        assertTrue(lines.contains("appops set $pkg ACCESS_ACCESSIBILITY allow"))
        assertTrue(lines.contains("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow"))
    }

    @Test
    fun scriptNeverLeaksAnotherPackage() {
        // **授权类**命令必须只针对本包（防止复制粘贴时把别人的包名带进来）；
        // echo / shebang 不含包名是正常的，不参与这条判断。
        val granting = PrivilegedGrant.scriptLines(pkg)
            .filter { it.startsWith("pm ") || it.startsWith("appops ") }
        assertTrue("居然一条授权命令都没有", granting.isNotEmpty())
        assertTrue("有授权命令没带本包名：$granting", granting.all { it.contains(pkg) })
    }

    @Test
    fun commandLineIsWhatTheUserCopies() {
        assertEquals(
            "adb shell sh /storage/emulated/0/Android/data/pkg/files/sh/grant.sh",
            PrivilegedGrant.commandFor("/storage/emulated/0/Android/data/pkg/files/sh/grant.sh"),
        )
        // 脚本写不出来时的退路：仍然是最关键的那一条
        assertEquals(
            "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            PrivilegedGrant.fallbackCommand(pkg),
        )
    }
}
