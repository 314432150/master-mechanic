package com.example.mastermechanic.calibration

import android.content.Context
import java.io.File

/**
 * 标定产物存取（T1-5b）：产物文件位于应用私有目录（随应用卸载清除，不跨设备复用）。
 *
 * 写入采用「临时文件 + 重命名」：避免半写文件被后续加载误读（掉电 / 进程被杀场景）。
 */
object CalibrationStore {

    private const val DIR_NAME = "calibration"
    private const val FILE_NAME = "calibration.txt"

    fun artifactFile(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    fun exists(context: Context): Boolean = artifactFile(context).isFile

    /** 保存（覆盖写）；返回产物文件。 */
    fun save(context: Context, data: CalibrationData): File {
        val file = artifactFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(CalibrationCodec.encode(data), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // 重命名失败（个别文件系统限制）：退化为直接覆盖写
            file.writeText(CalibrationCodec.encode(data), Charsets.UTF_8)
            tmp.delete()
        }
        return file
    }

    /** 删除产物；返回是否删除成功（不存在时为 false）。 */
    fun delete(context: Context): Boolean = artifactFile(context).delete()

    /** 加载产物；文件不存在返回 null；解析失败抛 [IllegalArgumentException]（不静默降级）。 */
    fun load(context: Context): CalibrationData? {
        val file = artifactFile(context)
        if (!file.isFile) return null
        return CalibrationCodec.decode(file.readText(Charsets.UTF_8))
    }
}
