package com.locutus5382.heimdall.engine.duko

import kotlin.math.abs

/**
 * M1.9: retrospective, explainable interpretation of the M1.8 minute blocks.
 * No additional polling, inferred frames, trained model or fabricated readings.
 * Thresholds are provisional workload labels, NOT game-specific performance claims.
 */
enum class DukoWorkloadPhase { IDLE, ACTIVE, SUSTAINED_LOAD, RECOVERY, UNKNOWN }

data class DukoWorkloadSegment(
    val phase: DukoWorkloadPhase,
    val window: DukoWindow,
    val minuteCount: Int,
    val minimumLoadCoverage: Double,
    val evidence: DukoEvidence,
    val warnings: List<String>,
)

data class DukoWorkloadReport(
    val segments: List<DukoWorkloadSegment>,
    val analyzedMinutes: Int,
    val truncated: Boolean,
    val thresholdsExperimental: Boolean = true,
)

data class DukoSuggestedInterval(
    val window: DukoWindow,
    val phase: DukoWorkloadPhase,
    val minuteCount: Int,
    val minimumLoadCoverage: Double,
    val evidence: DukoEvidence,
    val rationale: String,
    val warnings: List<String>,
)

data class DukoSuggestionReport(
    val intervals: List<DukoSuggestedInterval>,
    val warnings: List<String>,
)

enum class DukoFindingKind {
    WORKLOAD, CPU_PRESSURE, GPU_PRESSURE, SYSTEM_PRESSURE,
    THERMAL_TREND, POLICY_CEILING, INSUFFICIENT_DATA,
}

data class DukoQualifiedFinding(
    val kind: DukoFindingKind,
    val evidence: DukoEvidence,
    val window: DukoWindow?,
    val summary: String,
    val limitation: String,
)

data class DukoInsightReport(
    val workload: DukoWorkloadReport,
    val suggestions: DukoSuggestionReport,
    val findings: List<DukoQualifiedFinding>,
    val caveats: List<String>,
)

object DukoIntelligentAnalysis {
    // Only full one-minute blocks with >=80% continuous observed coverage can
    // contribute a classification. Absent GPU evidence does not mean 0% GPU.
    private const val MIN_CLASSIFICATION_COVERAGE = 0.80
    private const val MIN_SUGGESTION_COVERAGE = 0.85
    private const val SUSTAINED_MINUTES = 2
    private const val MAX_SUGGESTIONS = 3
    private const val MAX_FINDINGS = 8

    private data class Label(
        val phase: DukoWorkloadPhase,
        val strong: Boolean,
        val coverage: Double,
        val evidence: DukoEvidence,
        val warnings: List<String>,
    )

    private fun usable(item: DukoMetricAnalysis?): Boolean =
        item != null && item.evidence != DukoEvidence.INSUFFICIENT &&
            item.coverage >= MIN_CLASSIFICATION_COVERAGE &&
            item.timeWeightedMean?.isFinite() == true

    private fun label(block: DukoMinuteBlock): Label {
        val cpu = block.metrics[DukoMetric.CPU_LOAD]
        val gpu = block.metrics[DukoMetric.GPU_LOAD]
        val cpuValid = usable(cpu)
        val gpuValid = usable(gpu)
        val cpuMean = if (cpuValid) cpu?.timeWeightedMean else null
        val gpuMean = if (gpuValid) gpu?.timeWeightedMean else null
        val coverage = listOfNotNull(
            cpu?.coverage?.takeIf { cpuValid },
            gpu?.coverage?.takeIf { gpuValid },
        ).minOrNull() ?: 0.0
        val weak = block.partial || block.window.durationMs < DukoMinuteAccumulator.MINUTE_MS ||
            (!cpuValid && !gpuValid)
        if (weak) return Label(DukoWorkloadPhase.UNKNOWN, false, coverage,
            DukoEvidence.INSUFFICIENT,
            listOf(if (block.partial) "PARTIAL_MINUTE" else "LOAD_EVIDENCE_INSUFFICIENT"))
        val evidence = if (cpuValid && gpuValid &&
            cpu?.evidence == DukoEvidence.SUPPORTED &&
            gpu?.evidence == DukoEvidence.SUPPORTED
        ) DukoEvidence.SUPPORTED else DukoEvidence.QUALIFIED
        val warnings = buildList {
            if (!cpuValid) add("CPU_LOAD_UNAVAILABLE")
            if (!gpuValid) add("GPU_LOAD_UNAVAILABLE")
            if (evidence == DukoEvidence.QUALIFIED) add("CLASSIFICATION_QUALIFIED")
        }
        val strong = (cpuMean != null && cpuMean >= 65.0) ||
            (gpuMean != null && gpuMean >= 75.0)
        val active = strong || (cpuMean != null && cpuMean >= 25.0) ||
            (gpuMean != null && gpuMean >= 30.0)
        val idle = cpuMean != null && gpuMean != null &&
            cpuMean <= 15.0 && gpuMean <= 15.0
        return Label(
            when {
                active -> DukoWorkloadPhase.ACTIVE
                idle -> DukoWorkloadPhase.IDLE
                else -> DukoWorkloadPhase.UNKNOWN
            },
            strong, coverage,
            if (active || idle) evidence else DukoEvidence.INSUFFICIENT,
            if (active || idle) warnings else warnings + "AMBIGUOUS_LOAD",
        )
    }

    fun segment(minutes: DukoMinuteReport): DukoWorkloadReport {
        val blocks = minutes.blocks
        if (blocks.isEmpty()) return DukoWorkloadReport(emptyList(), 0, minutes.truncated)
        val labels = blocks.map(::label).toMutableList()

        // At minute resolution, only uninterrupted streaks of >=2 complete
        // high-load blocks qualify as a sustained-load period.
        var i = 0
        while (i < labels.size) {
            if (!labels[i].strong || labels[i].phase != DukoWorkloadPhase.ACTIVE) {
                i++
                continue
            }
            var end = i + 1
            while (end < labels.size &&
                labels[end].strong && labels[end].phase == DukoWorkloadPhase.ACTIVE &&
                blocks[end - 1].window.endMs == blocks[end].window.startMs) end++
            if (end - i >= SUSTAINED_MINUTES) for (j in i until end) {
                labels[j] = labels[j].copy(phase = DukoWorkloadPhase.SUSTAINED_LOAD)
            }
            i = end
        }
        // A low-load block immediately following observed activity can be
        // labeled RECOVERY (transition in demand, NOT proof of cooling).
        for (j in 1 until labels.size) {
            if (labels[j].phase == DukoWorkloadPhase.IDLE &&
                blocks[j - 1].window.endMs == blocks[j].window.startMs &&
                labels[j - 1].phase in setOf(DukoWorkloadPhase.ACTIVE,
                    DukoWorkloadPhase.SUSTAINED_LOAD)) {
                labels[j] = labels[j].copy(phase = DukoWorkloadPhase.RECOVERY,
                    warnings = labels[j].warnings + "RECOVERY_MEANS_LOWER_OBSERVED_LOAD")
            }
        }
        val segments = ArrayList<DukoWorkloadSegment>()
        var start = 0
        while (start < labels.size) {
            var end = start + 1
            while (end < labels.size && labels[end].phase == labels[start].phase &&
                blocks[end - 1].window.endMs == blocks[end].window.startMs) end++
            val slice = labels.subList(start, end)
            val grade = when {
                slice.any { it.evidence == DukoEvidence.INSUFFICIENT } ->
                    DukoEvidence.INSUFFICIENT
                slice.any { it.evidence == DukoEvidence.QUALIFIED } ->
                    DukoEvidence.QUALIFIED
                else -> DukoEvidence.SUPPORTED
            }
            segments += DukoWorkloadSegment(
                phase = labels[start].phase,
                window = DukoWindow(blocks[start].window.startMs,
                    blocks[end - 1].window.endMs),
                minuteCount = end - start,
                minimumLoadCoverage = slice.minOf { it.coverage },
                evidence = grade,
                warnings = (slice.flatMap { it.warnings } +
                    "HEURISTIC_60_SECOND_CLASSIFICATION").distinct(),
            )
            start = end
        }
        return DukoWorkloadReport(segments, blocks.size, minutes.truncated)
    }

    private fun stablePair(a: DukoMinuteBlock, b: DukoMinuteBlock): Boolean {
        if (a.window.endMs != b.window.startMs) return false
        for (metric in listOf(DukoMetric.CPU_LOAD, DukoMetric.GPU_LOAD)) {
            val first = a.metrics[metric]?.takeIf(::usable)
            val second = b.metrics[metric]?.takeIf(::usable)
            // If this metric exists for only one minute, do not silently
            // bridge the discontinuity in comparison evidence.
            if ((first == null) != (second == null)) return false
            if (first != null && second != null &&
                abs(requireNotNull(first.timeWeightedMean) -
                    requireNotNull(second.timeWeightedMean)) >
                (if (metric == DukoMetric.CPU_LOAD) 12.0 else 15.0)) return false
        }
        return true
    }

    fun suggest(minutes: DukoMinuteReport, workload: DukoWorkloadReport): DukoSuggestionReport {
        val candidates = ArrayList<DukoSuggestedInterval>()
        val blocks = minutes.blocks
        for (segment in workload.segments) {
            if (segment.phase !in setOf(DukoWorkloadPhase.ACTIVE,
                    DukoWorkloadPhase.SUSTAINED_LOAD)) continue
            val relevant = blocks.filter {
                it.window.startMs >= segment.window.startMs &&
                    it.window.endMs <= segment.window.endMs
            }
            var index = 0
            while (index < relevant.size) {
                fun eligible(b: DukoMinuteBlock): Boolean {
                    val cpu = b.metrics[DukoMetric.CPU_LOAD]
                    val gpu = b.metrics[DukoMetric.GPU_LOAD]
                    return !b.partial && b.window.durationMs == 60_000L &&
                        listOf(cpu, gpu).any { usable(it) &&
                            requireNotNull(it).coverage >= MIN_SUGGESTION_COVERAGE } &&
                        listOfNotNull(cpu, gpu).filter(::usable).all {
                            it.coverage >= MIN_SUGGESTION_COVERAGE &&
                                (it.sampleStandardDeviation ?: Double.POSITIVE_INFINITY) <= 24.0
                        }
                }
                if (!eligible(relevant[index])) { index++; continue }
                var end = index + 1
                while (end < relevant.size && eligible(relevant[end]) &&
                    stablePair(relevant[end - 1], relevant[end])) end++
                if (end - index >= 2) {
                    val chosen = relevant.subList(index, end)
                    val usableSignals = chosen.flatMap { listOfNotNull(
                        it.metrics[DukoMetric.CPU_LOAD],
                        it.metrics[DukoMetric.GPU_LOAD],
                    ).filter(::usable) }
                    val allSupported = usableSignals.isNotEmpty() &&
                        usableSignals.all { it.evidence == DukoEvidence.SUPPORTED } &&
                        chosen.all { usable(it.metrics[DukoMetric.CPU_LOAD]) &&
                            usable(it.metrics[DukoMetric.GPU_LOAD]) }
                    candidates += DukoSuggestedInterval(
                        DukoWindow(chosen.first().window.startMs,
                            chosen.last().window.endMs),
                        segment.phase, chosen.size,
                        usableSignals.minOf { it.coverage },
                        if (allSupported) DukoEvidence.SUPPORTED else DukoEvidence.QUALIFIED,
                        "Adjacent complete minutes with stable observed CPU/GPU load " +
                            "and at least 85% load-signal coverage",
                        listOf("MATCH_GAME_SCENE_DRIVER_AND_DEVICE_SETTINGS",
                            "NO_FPS_OR_WORKLOAD_EQUIVALENCE_INFERRED"),
                    )
                }
                index = end
            }
        }
        return DukoSuggestionReport(
            candidates.sortedWith(
                compareByDescending<DukoSuggestedInterval> { it.minuteCount }
                    .thenByDescending { it.minimumLoadCoverage }
                    .thenBy { it.window.startMs }
            ).take(MAX_SUGGESTIONS),
            buildList {
                add("COMPARABILITY_REQUIRES_MATCHED_GAME_SCENE_DRIVER_AND_DEVICE_SETTINGS")
                if (minutes.truncated) add("SUGGESTIONS_ONLY_COVER_DISPLAYED_FIRST_MINUTES")
                if (candidates.isEmpty()) add("NO_STABLE_120_SECOND_INTERVAL_WITH_SUFFICIENT_EVIDENCE")
            }
        )
    }

    fun analyze(
        minutes: DukoMinuteReport,
        events: DukoEventTimeline?,
        caps: FrequencyCapTimeline?,
        thermal: Map<DukoMetric, DukoThermalReport>,
        outcome: DukoSessionOutcome,
    ): DukoInsightReport {
        val workload = segment(minutes)
        val suggestions = suggest(minutes, workload)
        val findings = ArrayList<DukoQualifiedFinding>()
        val bestSustained = workload.segments
            .filter { it.phase == DukoWorkloadPhase.SUSTAINED_LOAD }
            .maxByOrNull { it.window.durationMs }
        if (bestSustained != null) findings += DukoQualifiedFinding(
            DukoFindingKind.WORKLOAD, bestSustained.evidence,
            bestSustained.window,
            "Sustained high observed system CPU or GPU load for " +
                (bestSustained.window.durationMs / 60_000L) + " complete minutes",
            "Heuristic system-workload label, not measured game FPS or a bottleneck diagnosis.",
        )

        fun pressure(type: DukoEventType, kind: DukoFindingKind, title: String) {
            val longest = events?.events?.filter { it.type == type }
                ?.maxByOrNull { it.durationMs } ?: return
            findings += DukoQualifiedFinding(
                kind, DukoEvidence.QUALIFIED,
                if (longest.endMs > longest.startMs)
                    DukoWindow(longest.startMs, longest.endMs) else null,
                title + " for " + (longest.durationMs / 1_000L) + " recorded seconds",
                "Threshold is experimental; a system-wide signal cannot locate the game stall.",
            )
        }
        pressure(DukoEventType.CPU_LOAD_HIGH, DukoFindingKind.CPU_PRESSURE,
            "High system CPU utilization observed")
        pressure(DukoEventType.GPU_LOAD_HIGH, DukoFindingKind.GPU_PRESSURE,
            "High sampled GPU utilization observed")
        pressure(DukoEventType.CPU_PSI_PRESSURE, DukoFindingKind.SYSTEM_PRESSURE,
            "System CPU PSI pressure observed")
        pressure(DukoEventType.MEMORY_PSI_PRESSURE, DukoFindingKind.SYSTEM_PRESSURE,
            "System memory PSI pressure observed")

        caps?.sustainedPolicies?.take(2)?.forEach { cap ->
            findings += DukoQualifiedFinding(
                DukoFindingKind.POLICY_CEILING, DukoEvidence.QUALIFIED, caps.window,
                cap.policyId + " maximum policy frequency was below the reported " +
                    "hardware ceiling for " + (cap.restrictedMs / 1000L) +
                    " observed seconds",
                "Restriction cause unknown; not proof of thermal throttling or ClusterTune.",
            )
        }
        val warming = thermal.values.filter {
            it.evidence != DukoEvidence.INSUFFICIENT &&
                (it.warmingSlopeCPerMinute ?: 0.0) >= 0.30
        }.sortedByDescending { it.warmingSlopeCPerMinute }.firstOrNull()
        if (warming != null) findings += DukoQualifiedFinding(
            DukoFindingKind.THERMAL_TREND, warming.evidence,
            warming.statistics.window,
            warming.metric.name.replace('_', ' ') + " rose at approximately " +
                ((requireNotNull(warming.warmingSlopeCPerMinute) * 10.0)
                    .toInt() / 10.0) + " °C/min during its longest continuous segment",
            "Sensor trend only; warming does not establish a frequency or FPS effect.",
        )
        if (findings.isEmpty()) findings += DukoQualifiedFinding(
            DukoFindingKind.INSUFFICIENT_DATA, DukoEvidence.INSUFFICIENT,
            null, "No strong evidence-qualified workload or pressure finding",
            "Absence of a finding is not evidence that pressure or throttling did not occur.",
        )
        val caveats = buildList {
            add("HEURISTIC_THRESHOLDS_PENDING_ODIN_DEVICE_VALIDATION")
            add("NO_FPS_OR_CAUSAL_THROTTLING_INFERENCE")
            if (outcome != DukoSessionOutcome.COMPLETED)
                add("SESSION_INTERRUPTED_OR_RECOVERED_CONCLUSIONS_DOWNGRADED")
            if (minutes.truncated) add("WORKLOAD_PHASES_LIMITED_TO_FIRST_360_MINUTES")
            if ((events?.droppedEvents ?: 0) > 0)
                add("EVENT_TIMELINE_TRUNCATED")
        }
        return DukoInsightReport(
            workload, suggestions,
            findings.take(MAX_FINDINGS).map {
                if (outcome == DukoSessionOutcome.COMPLETED) it else
                    it.copy(evidence = when (it.evidence) {
                        DukoEvidence.SUPPORTED -> DukoEvidence.QUALIFIED
                        else -> it.evidence
                    })
            }, caveats
        )
    }
}
