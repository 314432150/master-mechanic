package com.example.mastermechanic.preset

import android.content.Context
import com.example.mastermechanic.log.MmLog
import java.io.File

/**
 * 预设的落盘（`files/preset/visit.txt`，ADR-006 同套）：
 * **临时文件 + rename**（写到一半被杀不会留下半个文件），解析失败**抛错不静默降级**。
 */
object VisitPresetStore {

    private const val TAG = "MM-Preset"
    private const val DIR = "preset"
    private const val FILE = "visit.txt"

    fun presetFile(context: Context): File = File(File(context.filesDir, DIR), FILE)

    /** 文件不存在返回 null（= 还没配过）；解析失败抛 [IllegalArgumentException]。 */
    fun load(context: Context): VisitPreset? = load(presetFile(context))

    /** 纯文件版（JVM 可测）。 */
    fun load(file: File): VisitPreset? =
        if (!file.isFile) null else VisitPresetCodec.decode(file.readText(Charsets.UTF_8))

    fun save(context: Context, preset: VisitPreset) = save(presetFile(context), preset)

    fun save(file: File, preset: VisitPreset) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(VisitPresetCodec.encode(preset), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            // rename 失败（少见，但有些设备上目标已存在时会这样）→ 退化成覆盖写，不能悄悄丢掉这次保存
            file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
            MmLog.w(TAG, "预设 rename 失败，已退化为直接覆盖写")
        }
    }

}
