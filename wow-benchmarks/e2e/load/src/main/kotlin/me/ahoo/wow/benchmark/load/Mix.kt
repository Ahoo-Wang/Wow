/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.benchmark.load

/**
 * The scenarios a run sends, by weight: one scenario (`--scenario=E1`), or a mix (`--mix=E1:50,E4-state:40,E4-list:10`)
 * whose operation `i` runs the scenario at slot `i` of a smoothly interleaved cycle (smooth weighted round-robin), so
 * every second sends the same proportions and no scenario comes in bursts. A mix records each scenario's latencies
 * apart, so the writes' latency under the reads (the query bulkheads, the write priority) shows.
 */
class Mix(val parts: List<Pair<Scenario, Int>>) {
    init {
        require(parts.isNotEmpty() && parts.all { it.second > 0 }) { "A mix has scenarios of positive weight." }
        require(parts.map { it.first }.toSet().size == parts.size) { "A scenario appears once in a mix." }
    }

    private val cycle: List<Scenario> = interleave(parts)

    /** Whether more than one scenario runs. */
    val mixed: Boolean get() = parts.size > 1

    /** Whether any of the scenarios moves an amount between two accounts. */
    val transfers: Boolean get() = parts.any { it.first.transfers }

    /** The scenario of operation [index]. */
    fun pick(index: Long): Scenario = cycle[(index % cycle.size).toInt()]

    /** `E1`, or `E1:50,E4-state:40,E4-list:10`. */
    val label: String
        get() = if (mixed) parts.joinToString(",") { "${it.first.label}:${it.second}" } else parts.single().first.label

    val description: String
        get() = if (mixed) {
            "mix of " + parts.joinToString(", ") { "${it.first.label} ${it.second}/${parts.sumOf { p -> p.second }}" }
        } else {
            parts.single().first.description
        }

    companion object {
        /** One scenario alone. */
        fun of(scenario: Scenario): Mix = Mix(listOf(scenario to 1))

        /** `E1:50,E4-state:40,E4-list:10`; a scenario without a weight weighs 1. */
        fun parse(text: String): Mix = Mix(
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
                val label = part.substringBefore(':')
                val weight = if (':' in part) part.substringAfter(':').toInt() else 1
                Scenario.of(label) to weight
            },
        )

        /** The cycle of smooth weighted round-robin: weights reduced by their gcd, each slot to the most owed. */
        private fun interleave(parts: List<Pair<Scenario, Int>>): List<Scenario> {
            val divisor = parts.map { it.second }.reduce(::gcd)
            val weights = parts.map { it.second / divisor }
            val total = weights.sum()
            val current = IntArray(parts.size)
            return List(total) {
                weights.forEachIndexed { index, weight -> current[index] += weight }
                val chosen = current.indices.maxBy { current[it] }
                current[chosen] -= total
                parts[chosen].first
            }
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
