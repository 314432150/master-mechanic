package com.example.mastermechanic.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import java.io.File
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 预设模型：就绪判定、非法输入、去空白。 */
class VisitPresetTest {

    @Test
    fun nextServerOnlyNeedsFriend() {
        // 最常用的日常路径：换下一个服务器 + 指定好友
        val preset = VisitPreset.of(ServerChoice.NEXT, "", "星月晚")
        assertTrue(preset.isReady)
    }

    @Test
    fun fixedServerWithoutNameIsAllowedAsDraftButNotReady() {
        // 草稿态：用户刚从"下一个"切到"指定"、还没选区服 —— 必须允许存在，
        // 否则界面一切换策略就抛异常（2026-09-16 真机闪退）。但这时**不算配好**，不能执行。
        val draft = VisitPreset(ServerChoice.FIXED, "", "星月晚")
        assertFalse(draft.isReady)
    }

    @Test
    fun emptyFriendMeansNotConfiguredYet() {
        // 还没配好 → 界面据此提示去配，而不是静默失败
        assertFalse(VisitPreset.EMPTY.isReady)
        assertFalse(VisitPreset.of(ServerChoice.NEXT, "", "").isReady)
        // 固定区服但没填好友 → 同样没配好
        assertFalse(VisitPreset(ServerChoice.FIXED, "龙腾", "").isReady)
    }

    @Test
    fun namesRejectSeparatorAndNewline() {
        assertFalse(VisitPreset.isValidName("阿|明"))
        assertFalse(VisitPreset.isValidName("阿\n明"))
        assertTrue(VisitPreset.isValidName(""))
    }

    @Test
    fun ofTrimsButKeepsInnerSpaces() {
        // 中间的空格必须原样保留：它会直接用于界面定位
        val preset = VisitPreset.of(ServerChoice.NEXT, " 龙腾 ", " 星 月晚 ")
        assertEquals("龙腾", preset.serverName)
        assertEquals("星 月晚", preset.friendName)
    }
}

/** 预设编解码：往返、拒收脏数据（带行号）。 */
class VisitPresetCodecTest {

    @Test
    fun roundTripNext() {
        val preset = VisitPreset.of(ServerChoice.NEXT, "", "星月晚")
        assertEquals(preset, VisitPresetCodec.decode(VisitPresetCodec.encode(preset)))
    }

    @Test
    fun roundTripFixed() {
        val preset = VisitPreset.of(ServerChoice.FIXED, "龙腾", "阿明")
        assertEquals(preset, VisitPresetCodec.decode(VisitPresetCodec.encode(preset)))
    }

    @Test
    fun unknownKeyIsRejectedWithLineNumber() {
        val text = VisitPresetCodec.encode(VisitPreset.EMPTY) + "surprise=1\n"
        val error = runCatching { VisitPresetCodec.decode(text) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("不认识的键"))
    }

    @Test
    fun wrongVersionIsRejected() {
        val text = VisitPresetCodec.encode(VisitPreset.EMPTY).replace("version=1", "version=99")
        assertTrue(
            runCatching { VisitPresetCodec.decode(text) }.exceptionOrNull()
                ?.message.orEmpty().contains("版本"),
        )
    }

    @Test
    fun otherFormatIsRejected() {
        val text = VisitPresetCodec.encode(VisitPreset.EMPTY).replace("format=mm-visit-preset", "format=mm-other")
        assertTrue(
            runCatching { VisitPresetCodec.decode(text) }.exceptionOrNull()
                ?.message.orEmpty().contains("不是一键拜访预设"),
        )
    }

    @Test
    fun missingFriendIsRejected() {
        val text = VisitPresetCodec.encode(VisitPreset.EMPTY)
            .split('\n').filterNot { it.startsWith("friendName=") }.joinToString("\n")
        assertTrue(
            runCatching { VisitPresetCodec.decode(text) }.exceptionOrNull()
                ?.message.orEmpty().contains("friendName"),
        )
    }
}

/** 预设落盘：不存在返回 null、往返、坏文件抛错。 */
class VisitPresetStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun loadMissingFileIsNull() {
        // 注意：不能用 TemporaryFolder.newFile（它会**创建**文件）—— 这里要的是"文件不存在"
        assertNull(VisitPresetStore.load(File(folder.root, "nothing-here.txt")))
    }

    @Test
    fun saveThenLoad() {
        val file = folder.newFile("visit.txt")
        val preset = VisitPreset.of(ServerChoice.FIXED, "龙腾", "星月晚")
        VisitPresetStore.save(file, preset)
        assertEquals(preset, VisitPresetStore.load(file))
    }

    @Test
    fun brokenFileThrowsInsteadOfSilentFallback() {
        val file = folder.newFile("visit.txt")
        file.writeText("format=mm-visit-preset\nversion=1\nbroken line\n")
        assertTrue(runCatching { VisitPresetStore.load(file) }.exceptionOrNull() is IllegalArgumentException)
    }
}
