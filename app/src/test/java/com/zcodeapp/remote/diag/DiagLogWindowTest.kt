package com.zcodeapp.remote.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 日志按小时分块与时间窗选取。
 *
 * 这些是「上传哪几个文件」的唯一依据，判错了用户传上来的就是错的时段，
 * 而我们拿到日志会以为现场就那样——比没日志更危险。
 */
class DiagLogWindowTest {

    private fun names(files: List<File>) = files.map { it.name }

    /** 构造一个假日志目录，文件名即真实命名规则。 */
    private fun fakeDir(vararg names: String): File {
        val d = File.createTempFile("zr-diag", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        for (n in names) File(d, n).writeText("x\n")
        return d
    }

    private fun within(dir: File, endMs: Long, hours: Int): List<String> {
        // 与 DiagLog.filesWithinHours 同逻辑，直接对目录操作便于测试。
        val startName = "diag-" + fmt(endMs - hours * 3600_000L) + ".log"
        val endName = "diag-" + fmt(endMs) + ".log"
        val startBase = startName.removeSuffix(".log")
        val endBase = endName.removeSuffix(".log")
        return dir.listFiles { f -> f.isFile && f.name.startsWith("diag-") && f.name.endsWith(".log") }
            ?.sortedBy { it.name }
            ?.filter {
                val base = it.name.removeSuffix(".log").substringBefore('.')
                base >= startBase && base <= endBase
            }
            ?.map { it.name }
            ?: emptyList()
    }

    private fun fmt(ms: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd-HH", java.util.Locale.US).format(java.util.Date(ms))

    @Test
    fun windowCoversTwoHoursAcrossDayBoundary() {
        // 凌晨 01:30 上传，往前 2 小时应覆盖前一天 23 点与当天 00/01 点。
        val dir = fakeDir(
            "diag-20261026-22.log",
            "diag-20261026-23.log",
            "diag-20261027-00.log",
            "diag-20261027-01.log",
            "diag-20261027-02.log",
        )
        try {
            val end = ts("20261027", "01", "30")
            val got = within(dir, end, 2)
            // 23:30 起算 → 覆盖 23、00、01 三个文件；22 与 02 都不在窗口内。
            assertEquals(listOf("diag-20261026-23.log", "diag-20261027-00.log", "diag-20261027-01.log"), got)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun windowIncludesRolloverFiles() {
        // 同一小时的续号文件（.1/.2）也必须被包含，否则那小时的异常段会漏传。
        val dir = fakeDir(
            "diag-20261027-09.log",
            "diag-20261027-09.1.log",
            "diag-20261027-10.log",
        )
        try {
            val got = within(dir, ts("20261027", "10", "20"), 2)
            assertTrue("同小时的续号文件应包含", got.contains("diag-20261027-09.1.log"))
            assertTrue(got.contains("diag-20261027-09.log"))
            assertTrue(got.contains("diag-20261027-10.log"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun windowExcludesOlderHours() {
        val dir = fakeDir(
            "diag-20261027-05.log",
            "diag-20261027-09.log",
            "diag-20261027-10.log",
        )
        try {
            val got = within(dir, ts("20261027", "10", "10"), 2)
            assertFalse("凌晨的文件不该进窗口", got.contains("diag-20261027-05.log"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun windowOnFreshDayIncludesPreviousDay() {
        // 00:40 上传：往前 2 小时跨到前一天 22:40。
        // 22 点那个文件覆盖 22:00–22:59，与窗口的 22:40–22:59 有重叠，必须包含——
        // 「窗口涉及到的文件」是按重叠算，不是按整点包含算。
        val dir = fakeDir(
            "diag-20261026-21.log",
            "diag-20261026-22.log",
            "diag-20261026-23.log",
            "diag-20261027-00.log",
        )
        try {
            val got = within(dir, ts("20261027", "00", "40"), 2)
            assertEquals(
                listOf("diag-20261026-22.log", "diag-20261026-23.log", "diag-20261027-00.log"),
                got,
            )
            assertFalse("窗口外的小时应排除", got.contains("diag-20261026-21.log"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun ts(day: String, hour: String, minute: String): Long =
        java.text.SimpleDateFormat("yyyyMMdd-HH:mm", java.util.Locale.US)
            .parse("$day-$hour:$minute").time
}
