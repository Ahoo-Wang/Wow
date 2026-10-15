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

package me.ahoo.wow.http.command

import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * The `Wow-Wait` and `Wow-Wait-Timeout` headers (decisions D98, D99).
 *
 * ```
 * plan := hop (">" hop)*
 * hop  := "accepted" ["(" ctx ")"]
 *       | "committed" ["(" ctx ")"]
 *       | "snapshot" ["(" ctx ")"]
 *       | "handled(" [ctx "/"] subscription ")"
 * ```
 *
 * Keywords are case-insensitive and white space around the tokens is ignored: `handled(order/payment-saga) >
 * committed(inventory)`. A subscription is named as in the Manifest and may hold dots (`processor.function`), so the
 * context of a `handled` hop is set apart by `/`. `handled(snapshot)` is the same hop as `snapshot`. The timeout is
 * `<n>ms`, `<n>s`, `<n>m` or an ISO-8601 duration (`PT10S`), and positive.
 *
 * [format] prints a plan as [parse] reads it, the inverse of [parse].
 */
internal object WaitHeader {
    private val HOP = Regex("""^([A-Za-z]+)\s*(?:\(\s*([^()]*?)\s*\))?$""")
    private val NAME = Regex("""^[A-Za-z0-9_.\-]+$""")
    private val SHORT_TIMEOUT = Regex("""^(\d{1,9})\s*(ms|s|m)$""")

    /** The plan [value] spells. */
    fun parse(value: String): WaitPlan {
        require(value.isNotBlank()) { "A wait plan has at least one hop." }
        return WaitPlan(value.split('>').map { parseHop(it.trim()) })
    }

    /** The hop [value] spells. */
    fun parseHop(value: String): Hop {
        val match = requireNotNull(HOP.matchEntire(value)) { "Not a hop: '$value'." }
        val keyword = match.groupValues[1].lowercase()
        val argument = match.groups[2]?.value
        return when (keyword) {
            "accepted" -> Hop.accepted(argument?.let(::name))
            "committed" -> Hop.committed(argument?.let(::name))
            "snapshot" -> Hop.snapshot(argument?.let(::name))
            "handled" -> handled(requireNotNull(argument) { "A handled hop names its subscription: '$value'." })
            else -> throw IllegalArgumentException("Not a stage: '$keyword'.")
        }
    }

    private fun handled(argument: String): Hop {
        val parts = argument.split('/')
        require(parts.size <= 2) { "A handled hop is (subscription) or (context/subscription): '$argument'." }
        return if (parts.size == 1) {
            Hop.handled(name(parts[0]))
        } else {
            Hop.handled(name(parts[1].trim()), name(parts[0].trim()))
        }
    }

    private fun name(value: String): String {
        require(NAME.matches(value)) { "Not a context or subscription name: '$value'." }
        return value
    }

    /** [plan]'s hops as [parse] reads them. */
    fun format(plan: WaitPlan): String = plan.hops.joinToString(" > ", transform = ::formatHop)

    /** [hop] as [parseHop] reads it. */
    fun formatHop(hop: Hop): String {
        val context = hop.context
        return when {
            hop.stage == Stage.HANDLED && hop.subscription == Hop.SNAPSHOT_SUBSCRIPTION ->
                if (context == null) "snapshot" else "snapshot($context)"

            hop.stage == Stage.HANDLED ->
                if (context == null) "handled(${hop.subscription})" else "handled($context/${hop.subscription})"

            context == null -> hop.stage.name.lowercase()
            else -> "${hop.stage.name.lowercase()}($context)"
        }
    }

    /** The timeout [value] spells. */
    fun parseTimeout(value: String): Duration {
        val trimmed = value.trim()
        val duration = SHORT_TIMEOUT.matchEntire(trimmed)?.let { match ->
            val amount = match.groupValues[1].toLong()
            when (match.groupValues[2]) {
                "ms" -> amount.milliseconds
                "s" -> amount.seconds
                else -> amount.minutes
            }
        } ?: try {
            JavaDuration.parse(trimmed).toKotlinDuration()
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("Not a timeout: '$value'; write 500ms, 10s or an ISO-8601 duration.")
        }
        require(duration.isPositive()) { "A wait timeout is positive: '$value'." }
        return duration
    }

    /** [timeout] as [parseTimeout] reads it. */
    fun formatTimeout(timeout: Duration): String {
        val millis = timeout.inWholeMilliseconds
        return if (millis % MILLIS_PER_SECOND == 0L) "${millis / MILLIS_PER_SECOND}s" else "${millis}ms"
    }

    private const val MILLIS_PER_SECOND = 1000L
}
