package com.locutus5382.heimdall.recording

import android.os.SystemClock
import com.locutus5382.heimdall.engine.duko.CpuStatParser
import com.locutus5382.heimdall.engine.duko.CpuTickSnapshot
import com.locutus5382.heimdall.engine.duko.PsiParser
import java.io.File

/** System-wide /proc signals, read-only and never interpreted as game FPS. */
internal data class ProcSignals(
    val overallCpuPercent: Double?,
    val perCorePercent: Map<Int, Double>,
    val cpuPsiSomeAvg10: Double?,
    val memoryPsiSomeAvg10: Double?,
    val memoryPsiFullAvg10: Double?,
)

internal class ProcSignalReader(
    private val statFile: File = File("/proc/stat"),
    private val cpuPsiFile: File = File("/proc/pressure/cpu"),
    private val memoryPsiFile: File = File("/proc/pressure/memory")
) {
    private var previous: CpuTickSnapshot? = null
    private var previousAtMs: Long? = null
    private fun readBounded(file: File, maxLines: Int): String? = runCatching {
        file.bufferedReader().use { reader ->
            buildString {
                for (i in 0 until maxLines) {
                    val line = reader.readLine() ?: break
                    append(line).append('\n')
                }
            }.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    fun read(nowMs: Long = SystemClock.elapsedRealtime()): ProcSignals {
        val current = readBounded(statFile, 130)?.let(CpuStatParser::parse)
        val validPrior = previousAtMs?.let {
            nowMs > it && nowMs - it <= 8000L
        } ?: false
        val prior = previous.takeIf { validPrior }
        val cpu = CpuStatParser.utilization(prior?.aggregate, current?.aggregate)
        val cores = current?.let { CpuStatParser.coreLoads(prior, it) }.orEmpty()
        previous = current
        previousAtMs = if (current?.aggregate != null) nowMs else null

        val cpuPsi = readBounded(cpuPsiFile, 2)?.let(PsiParser::parse)
        val memoryPsi = readBounded(memoryPsiFile, 2)?.let(PsiParser::parse)
        return ProcSignals(
            cpu, cores, cpuPsi?.some?.avg10,
            memoryPsi?.some?.avg10, memoryPsi?.full?.avg10
        )
    }
}
