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

package me.ahoo.wow.routing

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.pow
import kotlin.random.Random

/** Which aggregate the next command targets: an index into the seeded accounts. */
sealed interface Access {
    val label: String

    fun next(random: ThreadLocalRandom): Int

    /** Every seeded account equally likely: almost every command finds its aggregate cold. */
    class Uniform(private val aggregates: Int) : Access {
        override val label = "uniform"

        override fun next(random: ThreadLocalRandom): Int = random.nextInt(aggregates)
    }

    /**
     * Zipf with exponent [s] over [aggregates] ranks; ranks map to accounts through a fixed permutation, so the hot
     * accounts spread over the partitions as real ids would.
     */
    class Zipf(aggregates: Int, s: Double, seed: Long = SEED) : Access {
        override val label = "zipf"
        private val cumulative = DoubleArray(aggregates)
        private val permutation = IntArray(aggregates) { it }.also { it.shuffle(Random(seed)) }

        init {
            var sum = 0.0
            for (rank in 0 until aggregates) {
                sum += 1.0 / (rank + 1.0).pow(s)
                cumulative[rank] = sum
            }
            for (rank in 0 until aggregates) {
                cumulative[rank] /= sum
            }
        }

        override fun next(random: ThreadLocalRandom): Int {
            val u = random.nextDouble()
            var low = 0
            var high = cumulative.size - 1
            while (low < high) {
                val middle = (low + high) ushr 1
                if (cumulative[middle] < u) low = middle + 1 else high = middle
            }
            return permutation[low]
        }
    }

    /** [count] extremely hot accounts take every command. */
    class Hot(aggregates: Int, count: Int, seed: Long = SEED) : Access {
        override val label = "hot$count"
        private val hot = IntArray(aggregates) { it }.also { it.shuffle(Random(seed)) }.copyOf(count)

        override fun next(random: ThreadLocalRandom): Int = hot[random.nextInt(hot.size)]
    }

    companion object {
        const val SEED = 42L
    }
}

/** The state an account carries and how far its snapshot trails it. */
enum class StateShape(
    val label: String,
    /** Operations the state keeps. */
    val recentCap: Int,
    /** Memo length of each operation, which sizes the events and the state. */
    val memoLength: Int,
    /** A snapshot is written after every commit whose version is 1 more than a multiple of this. */
    val snapshotEvery: Int,
    /** The seeded tail after the version-1 snapshot is drawn from 0 until this. */
    val seededTail: Int,
) {
    /** A few fields; a snapshot after every commit, so a cold load reads the snapshot and one stream. */
    SMALL("small state, short tail", recentCap = 0, memoLength = 8, snapshotEvery = 1, seededTail = 1),

    /** About 3 KB of state; a snapshot every 10 versions, so a cold load reads the snapshot and up to 10 streams. */
    LARGE("large state, long tail", recentCap = 32, memoLength = 64, snapshotEvery = 10, seededTail = 10),
    ;

    fun snapshots(version: Int): Boolean = (version - 1) % snapshotEvery == 0
}

/** One cell of the matrix, without the routing variant and the load. */
data class Workload(
    val access: Access,
    /** Whether a client sends the next command of an aggregate only once its previous one has answered. */
    val sequential: Boolean,
    val shape: StateShape,
    val nodes: Int,
    /** The pre-registered criteria read this cell: it gets more repetitions. */
    val decisive: Boolean = false,
) {
    val name: String
        get() = "${access.label}, ${if (sequential) "sequential" else "concurrent"}, ${shape.label}, $nodes nodes"
}

/** How commands reach the node that executes them. */
enum class Routing(val label: String) {
    /** A random node receives the command and executes it. */
    LOCAL("Local"),

    /** A random node receives the command and forwards it to the owner of its partition unless that is itself. */
    PARTITIONED("Partitioned"),

    /** The load balancer sends the command straight to the owner of its partition. */
    INGRESS_HASH("Ingress-hash"),
}
