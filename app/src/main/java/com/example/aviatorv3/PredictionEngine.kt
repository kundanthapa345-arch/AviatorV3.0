package com.example.aviatorv3

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class EstimateResult(
    val estimatedX: Double,
    val confidence: Int
)

object PredictionEngine {

    fun estimate(rounds: List<Double>): EstimateResult {
        if (rounds.isEmpty()) {
            return EstimateResult(0.0, 0)
        }

        val recent = rounds.takeLast(20)

        val sorted = recent.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }

        val weightedAverage = run {
            var weightedSum = 0.0
            var totalWeight = 0.0

            recent.forEachIndexed { index, value ->
                val weight = (index + 1).toDouble()
                weightedSum += value * weight
                totalWeight += weight
            }

            weightedSum / totalWeight
        }

        val estimate = (median * 0.6) + (weightedAverage * 0.4)

        val mean = recent.average()
        val variance = recent
            .map { value -> (value - mean) * (value - mean) }
            .average()

        val deviation = kotlin.math.sqrt(variance)

        val consistency = if (mean > 0.0) {
            1.0 - min(1.0, deviation / mean)
        } else {
            0.0
        }

        val sampleFactor = min(1.0, recent.size / 20.0)

        val confidence = (
            (consistency * 70.0) +
            (sampleFactor * 30.0)
        ).toInt().coerceIn(0, 99)

        return EstimateResult(
            estimatedX = max(1.0, estimate),
            confidence = confidence
        )
    }
}
