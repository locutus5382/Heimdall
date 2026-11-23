package com.locutus5382.heimdall.engine.duko

import kotlin.math.sqrt

data class CoreLoadSummary(
    val coreId: Int,
    val measuredMs: Long,
    val sampleCount: Int,
    val averageLoadPercent: Double?,
    val sampleStandardDeviation: Double?,
    val maximumLoadPercent: Double?,
    val maximumAtMs: Long?,
    /** Sampled time for which this core had highest load of visible cores. */
    val ledObservedMs: Long,
)

data class ClusterLoadSummary(
    val policyId: String,
    val cores: List<Int>,
    val measuredMs: Long,
    val averageLoadPercent: Double?,
)

data class CoreActivityReport(
    val window: DukoWindow,
    val cores: List<CoreLoadSummary>,
    val clusters: List<ClusterLoadSummary>,
    val busiestCore: Int?,
    val busiestCluster: String?,
    val coverageFraction: Double,
) {
    val available get() = cores.isNotEmpty()
}

private class CoreNumbers {
    var samples = 0
    var mean = 0.0
    var m2 = 0.0
    var measuredMs = 0L
    var weightedSum = 0.0
    var maximum: Double? = null
    var maximumAt: Long? = null
    var ledMs = 0L
    fun observe(t: Long, v: Double) {
        samples++
        val delta = v - mean
        mean += delta / samples
        m2 += delta * (v - mean)
        if (maximum == null || v > maximum!!) {
            maximum = v
            maximumAt = t
        }
    }
    fun addInterval(v: Double, ms: Long) {
        weightedSum += v * ms
        measuredMs += ms
    }
    fun average() = if (measuredMs > 0) weightedSum / measuredMs else null
    fun stddev() = if (samples > 1)
        sqrt((m2 / (samples - 1)).coerceAtLeast(0.0)) else null
}

/** Time is credited only between adjacent valid, monotonic, same-core readings. */
class CoreActivityAccumulator(
    private val window: DukoWindow,
    private val policyCores: Map<String, Set<Int>> = emptyMap(),
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    init { require(expectedCadenceMs in 250L..60_000L) }
    private val maxGap = maxOf(expectedCadenceMs * 2, expectedCadenceMs + 1000)
    private val cores = sortedMapOf<Int, CoreNumbers>()
    private val clusterStats = sortedMapOf<String, CoreNumbers>()
    private var previous: Pair<Long, Map<Int, Double>>? = null
    private var lastTime: Long? = null

    fun accept(elapsedMs: Long, loads: Map<Int, Double>) {
        if (elapsedMs !in window.startMs..window.endMs) return
        if (lastTime != null && elapsedMs <= lastTime!!) {
            previous = null
            return
        }
        lastTime = elapsedMs
        val clean = loads.filter { (id, value) ->
            id in 0..127 && value.isFinite() && value in 0.0..100.0
        }
        clean.forEach { (id, value) ->
            cores.getOrPut(id, ::CoreNumbers).observe(elapsedMs, value)
        }
        val old = previous
        if (old != null) {
            val dt = elapsedMs - old.first
            if (dt in 1..maxGap) {
                val mutual = old.second.filterKeys { it in clean }
                mutual.forEach { (id, value) ->
                    cores.getValue(id).addInterval(value, dt)
                }
                val busiest = mutual.maxWithOrNull(compareBy<Map.Entry<Int, Double>>
                    { it.value }.thenBy { -it.key })?.key
                if (busiest != null) cores.getValue(busiest).ledMs += dt
                policyCores.forEach { (policy, members) ->
                    // Partial cluster visibility is not a valid cluster average.
                    if (members.isNotEmpty() && members.size <= 128 &&
                        members.all { it in mutual }) {
                        val load = members.sumOf { mutual.getValue(it) } / members.size
                        clusterStats.getOrPut(policy, ::CoreNumbers)
                            .addInterval(load, dt)
                    }
                }
            }
        }
        previous = elapsedMs to clean
    }

    fun result(): CoreActivityReport {
        val summaries = cores.map { (id, stat) ->
            CoreLoadSummary(id, stat.measuredMs, stat.samples,
                stat.average(), stat.stddev(), stat.maximum,
                stat.maximumAt, stat.ledMs)
        }.sortedWith(compareByDescending<CoreLoadSummary> {
            it.averageLoadPercent ?: -1.0
        }.thenBy { it.coreId })
        val groups = policyCores.mapNotNull { (id, members) ->
            val stat = clusterStats[id] ?: return@mapNotNull null
            ClusterLoadSummary(id, members.sorted(), stat.measuredMs, stat.average())
        }.sortedByDescending { it.averageLoadPercent ?: -1.0 }
        return CoreActivityReport(window, summaries, groups,
            summaries.firstOrNull { it.averageLoadPercent != null }?.coreId,
            groups.firstOrNull()?.policyId,
            (summaries.maxOfOrNull { it.measuredMs } ?: 0L).toDouble() /
                window.durationMs.toDouble())
    }
}

data class FrequencyCapEvent(
    val policyId: String,
    val elapsedMs: Long,
    val restricted: Boolean,
    val activeMaxMHz: Long,
    val hardwareMaxMHz: Long,
)
data class PolicyCapSummary(
    val policyId: String,
    val observedMs: Long,
    val restrictedMs: Long,
    val firstRestrictedAtMs: Long?,
    val longestContinuousRestrictedMs: Long,
    val transitions: Int,
    val presetLike: Boolean,
) {
    val restrictedFraction get() = if (observedMs > 0)
        restrictedMs.toDouble() / observedMs else 0.0
}
data class FrequencyCapTimeline(
    val window: DukoWindow,
    val policies: List<PolicyCapSummary>,
    val events: List<FrequencyCapEvent>,
) {
    val presetLikePolicies get() = policies.filter { it.presetLike }
    val sustainedPolicies get() = policies.filter {
        it.restrictedMs >= 60_000L && it.restrictedFraction >= 0.80
    }
}

private class CapState {
    var previousTime: Long? = null
    var previousRestricted: Boolean? = null
    var firstSeen: Long? = null
    var firstRestricted: Long? = null
    var observedMs = 0L
    var restrictedMs = 0L
    var streakMs = 0L
    var longestStreakMs = 0L
    var transitions = 0
    var sawUnrestricted = false
    var observedAtStart = false
}

/**
 * A policy ceiling below cpuinfo_max_freq is a *restriction*, never proof of
 * ClusterTune, a thermal event or who configured it. Entire-session behavior
 * can be classified "preset-like" only with sufficient measured coverage.
 */
class FrequencyCapTimelineAccumulator(
    private val window: DukoWindow,
    expectedCadenceMs: Long = DukoContract.DEFAULT_INTERVAL_MS,
) {
    init { require(expectedCadenceMs in 250L..60_000L) }
    private val gapMs = maxOf(expectedCadenceMs * 2, expectedCadenceMs + 1000)
    private val states = sortedMapOf<String, CapState>()
    private val changes = mutableListOf<FrequencyCapEvent>()
    private var lastTimestamp: Long? = null

    fun accept(
        elapsedMs: Long,
        limits: Map<String, Long>,
        hardwareMax: Map<String, Long>,
    ) {
        if (elapsedMs !in window.startMs..window.endMs) return
        val last = lastTimestamp
        if (last != null && elapsedMs <= last) {
            states.values.forEach {
                it.previousTime = null
                it.previousRestricted = null
                it.streakMs = 0
            }
            return
        }
        lastTimestamp = elapsedMs
        val ids = limits.keys + hardwareMax.keys
        ids.forEach { id ->
            val max = limits[id]
            val hardware = hardwareMax[id]
            val state = states.getOrPut(id, ::CapState)
            if (max == null || hardware == null || max <= 0 || hardware <= 0 ||
                max > hardware) {
                state.previousTime = null
                state.previousRestricted = null
                state.streakMs = 0L
                return@forEach
            }
            val restricted = max < hardware
            if (state.firstSeen == null) {
                state.firstSeen = elapsedMs
                state.observedAtStart =
                    elapsedMs - window.startMs <= gapMs.toLong()
            }
            if (restricted && state.firstRestricted == null)
                state.firstRestricted = elapsedMs
            if (!restricted) state.sawUnrestricted = true
            val previousTime = state.previousTime
            val before = state.previousRestricted
            if (before == null || before != restricted) {
                if (before != null) state.transitions++
                // Bounded event list; no unbounded memory from noisy vendors.
                if (changes.size < 1000)
                    changes += FrequencyCapEvent(id, elapsedMs, restricted,
                        max, hardware)
            }
            if (previousTime != null && before != null) {
                val dt = elapsedMs - previousTime
                if (dt in 1..gapMs) {
                    if (before == restricted) {
                        state.observedMs += dt
                        if (restricted) {
                            state.restrictedMs += dt
                            state.streakMs += dt
                            state.longestStreakMs =
                                maxOf(state.longestStreakMs, state.streakMs)
                        } else state.streakMs = 0L
                    } else state.streakMs = 0L
                } else state.streakMs = 0L
            }
            state.previousTime = elapsedMs
            state.previousRestricted = restricted
        }
    }

    fun result(): FrequencyCapTimeline {
        val policies = states.map { (id, s) ->
            val coverage = s.observedMs.toDouble() / window.durationMs
            PolicyCapSummary(id, s.observedMs, s.restrictedMs, s.firstRestricted,
                s.longestStreakMs, s.transitions,
                s.observedAtStart && !s.sawUnrestricted &&
                    window.durationMs >= 60_000L && coverage >= 0.80 && s.restrictedMs > 0 &&
                    s.restrictedMs.toDouble() / s.observedMs >= 0.90)
        }
        return FrequencyCapTimeline(window, policies, changes)
    }
}

/** related_cpus, e.g. "0-3" or "0 1 2 3"; never infer topology from policy names. */
object CpuPolicyTopology {
    fun parse(text: String): Set<Int> {
        val cores = mutableSetOf<Int>()
        for (part in text.trim().split(Regex("[,\\s]+"))) {
            if (part.isBlank()) continue
            val range = part.split('-', limit = 2)
            val first = range[0].toIntOrNull() ?: continue
            val last = if (range.size == 2)
                (range[1].toIntOrNull() ?: continue) else first
            if (first !in 0..127 || last !in first..127) continue
            for (id in first..last) cores += id
        }
        return cores
    }
}
