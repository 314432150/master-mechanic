package com.example.mastermechanic.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 应用偏好单测（M3-T3-7 追加 / ADR-006）：编解码往返与严格性，以及
 * "**缺失 → 用默认且不写盘 / 损坏 → 回落默认并重写**"的分流口径。
 */
class AppPreferencesTest {

    private fun tempFile(): File =
        File(Files.createTempDirectory("mm-prefs").toFile(), "settings/prefs.txt")

    @Test
    fun roundTripKeepsBothValues() {
        val on = AppPreferences(keepClickModeOnSessionStart = true)
        val off = AppPreferences(keepClickModeOnSessionStart = false)
        assertEquals(on, AppPreferencesCodec.decode(AppPreferencesCodec.encode(on)))
        assertEquals(off, AppPreferencesCodec.decode(AppPreferencesCodec.encode(off)))
        assertTrue(AppPreferencesCodec.encode(on).contains("keep-click-mode-on-session-start=true"))
    }

    @Test
    fun encodingIsDeterministic() {
        assertEquals(
            AppPreferencesCodec.encode(AppPreferences.DEFAULT),
            AppPreferencesCodec.encode(AppPreferences.DEFAULT),
        )
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val text = """
            # 手工改过的偏好
            format=mm-prefs

            version=1
            keep-click-mode-on-session-start=true
        """.trimIndent()
        assertEquals(AppPreferences(true), AppPreferencesCodec.decode(text))
    }

    @Test
    fun defaultIsSafeResetOnSessionStart() {
        // 默认必须是最安全的那一档（每次会话回到演练）
        assertFalse(AppPreferences.DEFAULT.keepClickModeOnSessionStart)
    }

    @Test
    fun missesAndCorruptionAreHandledDifferently() {
        // 缺失（首次安装）：用默认值，且**不写盘**（没发生过的事不留痕）
        val missing = tempFile()
        val missingResult = AppPreferencesStore.loadOrRecover(missing)
        assertEquals(AppPreferences.DEFAULT, missingResult.preferences)
        assertNull(missingResult.recoveredReason)
        assertFalse(missing.exists())

        // 损坏：回落默认 + **重写文件** + 带上原因（供调用方写日志）
        val broken = tempFile()
        broken.parentFile?.mkdirs()
        broken.writeText("这不是偏好文件\n", Charsets.UTF_8)
        val brokenResult = AppPreferencesStore.loadOrRecover(broken)
        assertEquals(AppPreferences.DEFAULT, brokenResult.preferences)
        assertNotNull(brokenResult.recoveredReason)
        assertTrue(brokenResult.recoveredReason!!.contains("不可用"))
        // 重写后文件已可用
        assertNull(AppPreferencesStore.loadOrRecover(broken).recoveredReason)
    }

    @Test
    fun saveThenLoadRoundTrip() {
        val file = tempFile()
        AppPreferencesStore.save(file, AppPreferences(true))
        assertEquals(
            AppPreferences(true),
            AppPreferencesStore.loadOrRecover(file).preferences,
        )
        assertFalse(File(file.parentFile, "prefs.txt.tmp").exists())
    }

    @Test
    fun rejectsMalformedContent() {
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferencesCodec.decode("format=mm-floating\nversion=1\nkeep-click-mode-on-session-start=true\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferencesCodec.decode("format=mm-prefs\nversion=9\nkeep-click-mode-on-session-start=true\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferencesCodec.decode("format=mm-prefs\nversion=1\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppPreferencesCodec.decode("format=mm-prefs\nversion=1\nkeep-click-mode-on-session-start=yes\n")
        }
        val e = assertThrows(IllegalArgumentException::class.java) {
            AppPreferencesCodec.decode("format=mm-prefs\nversion=1\nkeep-click-mode-on-session-start=true\nextra=1\n")
        }
        assertTrue(e.message!!.contains("未知键"))
    }
}
