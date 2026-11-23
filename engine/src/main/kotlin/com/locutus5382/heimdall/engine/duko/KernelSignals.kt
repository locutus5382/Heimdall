package com.locutus5382.heimdall.engine.duko

/** Read-only Linux procfs evidence. Never derive per-core load from clocks. */
data class CpuTicks(val total: Long, val idle: Long)
data class CpuTickSnapshot(val aggregate: CpuTicks?, val cores: Map<Int, CpuTicks>)

object CpuStatParser {
    private val coreLabel = Regex("cpu([0-9]+)")
    fun parse(text: String): CpuTickSnapshot {
        var aggregate: CpuTicks? = null
        val cores = linkedMapOf<Int, CpuTicks>()
        text.lineSequence().take(130).forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val label = fields.firstOrNull() ?: return@forEach
            if (label != "cpu" && !coreLabel.matches(label)) return@forEach
            val numbers = fields.drop(1).take(8).map { it.toLongOrNull() }
            if (numbers.size < 4 || numbers.any { it == null || it < 0 }) return@forEach
            val total = numbers.sumOf { it ?: 0L }
            val idle = (numbers[3] ?: 0L) + (numbers.getOrNull(4) ?: 0L)
            if (total <= 0L || idle > total) return@forEach
            val ticks = CpuTicks(total, idle)
            if (label == "cpu") aggregate = ticks
            else {
                val id = coreLabel.matchEntire(label)?.groupValues?.get(1)
                    ?.toIntOrNull() ?: return@forEach
                if (id in 0..127) cores[id] = ticks
            }
        }
        return CpuTickSnapshot(aggregate, cores)
    }
    fun utilization(previous: CpuTicks?, current: CpuTicks?): Double? {
        if (previous == null || current == null) return null
        val total = current.total - previous.total
        val idle = current.idle - previous.idle
        if (total <= 0L || idle < 0L || idle > total) return null
        return 100.0 * (total - idle).toDouble() / total
    }
    fun coreLoads(previous: CpuTickSnapshot?, current: CpuTickSnapshot): Map<Int, Double> =
        current.cores.mapNotNull { (id, ticks) ->
            utilization(previous?.cores?.get(id), ticks)?.let { id to it }
        }.toMap()
}

data class PsiLine(val avg10: Double, val avg60: Double, val avg300: Double, val totalUs: Long)
data class PsiReadings(val some: PsiLine?, val full: PsiLine?)
object PsiParser {
    fun parse(text: String): PsiReadings {
        val readings = mutableMapOf<String, PsiLine>()
        text.lineSequence().take(3).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val type = parts.firstOrNull() ?: return@forEach
            if (type !in setOf("some", "full")) return@forEach
            val values = parts.drop(1).mapNotNull { token ->
                val pair = token.split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap()
            fun avg(key: String): Double? = values[key]?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
            val a10 = avg("avg10") ?: return@forEach
            val a60 = avg("avg60") ?: return@forEach
            val a300 = avg("avg300") ?: return@forEach
            val total = values["total"]?.toLongOrNull()?.takeIf { it >= 0L }
                ?: return@forEach
            readings[type] = PsiLine(a10, a60, a300, total)
        }
        return PsiReadings(readings["some"], readings["full"])
    }
}

/** ASCII encoding bounded to Android's /proc/stat CPU identifiers. */
object CoreLoadCodec {
    fun encode(loads: Map<Int, Double>): String? {
        val normalized = loads.filter { (id, v) ->
            id in 0..127 && v.isFinite() && v in 0.0..100.0
        }.toSortedMap()
        return normalized.takeIf { it.isNotEmpty() }?.entries
            ?.joinToString("|") { (id, v) -> id.toString() + "=" + v }
    }
    fun decode(text: String?): Map<Int, Double> = text?.take(3000)
        ?.split("|")?.take(128)?.mapNotNull { token ->
            val parts = token.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toIntOrNull()?.takeIf { it in 0..127 }
                ?: return@mapNotNull null
            val value = parts[1].toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
                ?: return@mapNotNull null
            id to value
        }?.toMap().orEmpty()
}