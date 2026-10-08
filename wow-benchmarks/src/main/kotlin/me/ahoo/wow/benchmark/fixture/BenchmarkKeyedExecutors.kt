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

package me.ahoo.wow.benchmark.fixture

import me.ahoo.wow.execution.KeyedExecutor
import java.time.Duration

/**
 * The [KeyedExecutor] of benchmark runtimes. `-Dwow.benchmark.dispatch.spin=<duration>` (passed to the fork, e.g.
 * `-jvmArgsAppend -Dwow.benchmark.dispatch.spin=0`) overrides the worker spin, so the same jar can be measured with
 * the spin off or at another budget; without it the executor uses [KeyedExecutor.DEFAULT_SPIN].
 *
 * The duration is ISO-8601 (`PT0.00002S`) or a number with a unit suffix: `ns`, `us`, `ms` or `s` (`0`, `20us`,
 * `1ms`); a bare number is milliseconds, as in Spring's `wow.dispatch.spin`.
 */
object BenchmarkKeyedExecutors {
    const val SPIN_PROPERTY: String = "wow.benchmark.dispatch.spin"

    private val SUFFIXED = Regex("""(\d+)\s*(ns|us|µs|ms|s)?""")

    /** The spin from [SPIN_PROPERTY], else the default. */
    fun spin(): Duration = System.getProperty(SPIN_PROPERTY)?.let(::parseDuration) ?: KeyedExecutor.DEFAULT_SPIN

    fun create(workers: Int = KeyedExecutor.DEFAULT_WORKERS): KeyedExecutor =
        KeyedExecutor(workers = workers, spin = spin())

    fun parseDuration(text: String): Duration {
        val value = text.trim()
        if (value.startsWith("P", ignoreCase = true)) {
            return Duration.parse(value)
        }
        val match = requireNotNull(SUFFIXED.matchEntire(value)) {
            "$SPIN_PROPERTY must be ISO-8601 or a number with ns/us/ms/s, but was [$text]."
        }
        val amount = match.groupValues[1].toLong()
        return when (match.groupValues[2]) {
            "ns" -> Duration.ofNanos(amount)
            "us", "µs" -> Duration.ofNanos(amount * NANOS_PER_MICRO)
            "s" -> Duration.ofSeconds(amount)
            else -> Duration.ofMillis(amount)
        }
    }

    private const val NANOS_PER_MICRO = 1_000L
}
