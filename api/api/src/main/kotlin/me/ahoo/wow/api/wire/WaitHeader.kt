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

package me.ahoo.wow.api.wire

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.SystemSubscriptions
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * The `Wow-Wait` and `Wow-Wait-Timeout` headers.
 *
 * ```
 * plan := hop (">" hop)*
 * hop  := "accepted" ["(" ctx ")"]
 *       | "committed" ["(" ctx ")"]
 *       | "snapshot" ["(" ctx ")"]
 *       | "queryable" ["(" ctx ")"]
 *       | "handled(" [ctx "/"] subscription ")"
 * ```
 *
 * Keywords are case-insensitive and white space around the tokens is ignored: `handled(order/payment-saga) >
 * committed(inventory)`. A subscription is named as in the Manifest and may hold dots (`processor.function`), so the
 * context of a `handled` hop is set apart by `/`. `handled(wow.snapshot)` is the same hop as `snapshot`, and
 * `handled(wow.queryable)` as `queryable`: the commit is in the store the aggregate type's snapshot queries read. The
 * timeout is `<n>ms`, `<n>s`, `<n>m` with no space between the number and its unit, or an ISO-8601 duration (`PT10S`),
and one millisecond or more.
 *
 * [format] prints a plan as [parse] reads it, the inverse of [parse].
 *
 * A plan is read in time linear in its length, by hand rather than by a pattern whose quantifiers could share white
 * space and backtrack over it (#6815), and is refused before any hop is read when it is longer than [MAX_LENGTH]
 * characters or has more than [MAX_HOPS] hops: a request's header bounds the work admission does for it.
 */
@InternalWowApi
object WaitHeader {
    /** The longest plan [parse] reads: 32 hops of 128 characters. */
    const val MAX_LENGTH: Int = 4096

    /** The most hops [parse] reads, the most any node's wait follows (its `wow.wait.max-hops` is at most this). */
    const val MAX_HOPS: Int = 32

    private val NAME = Regex("""^[A-Za-z0-9_.\-]+$""")
    private val SHORT_TIMEOUT = Regex("""^(\d{1,9})(ms|s|m)$""")

    /** The plan [value] spells. */
    fun parse(value: String): WaitPlan {
        require(value.length <= MAX_LENGTH) {
            "A wait plan is at most $MAX_LENGTH characters; this one has ${value.length}."
        }
        require(value.isNotBlank()) { "A wait plan has at least one hop." }
        val hops = value.count { it == '>' } + 1
        require(hops <= MAX_HOPS) { "A wait plan has at most $MAX_HOPS hops; this one has $hops." }
        return WaitPlan(value.split('>').map { parseHop(it.trim()) })
    }

    /**
     * The hop [value] spells: a keyword of ASCII letters, then, after optional white space, an argument in parentheses
     * that holds none, white space around it ignored.
     */
    fun parseHop(value: String): Hop {
        val open = value.indexOf('(')
        val keyword = (if (open < 0) value else value.substring(0, open)).trimEnd(::isSpace)
        require(keyword.isNotEmpty() && keyword.all(::isLetter)) { "Not a hop: '$value'." }
        val argument = if (open < 0) null else argumentOf(value, open)
        return when (keyword.lowercase()) {
            "accepted" -> Hop.accepted(argument?.let(::name))
            "committed" -> Hop.committed(argument?.let(::name))
            "snapshot" -> Hop.snapshot(argument?.let(::name))
            "queryable" -> Hop.queryable(argument?.let(::name))
            "handled" -> handled(requireNotNull(argument) { "A handled hop names its subscription: '$value'." })
            else -> throw IllegalArgumentException("Not a stage: '${keyword.lowercase()}'.")
        }
    }

    /** The argument of [value], whose `(` is at [open]: what lies before its last character, `)`, trimmed. */
    private fun argumentOf(value: String, open: Int): String {
        val close = value.length - 1
        require(close > open && value[close] == ')' && value.indexOfAny(PARENTHESES, open + 1) == close) {
            "Not a hop: '$value'."
        }
        return value.substring(open + 1, close).trim(::isSpace)
    }

    /** An ASCII letter, as a keyword holds. */
    private fun isLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

    /** White space as a hop ignores it: a space, a tab, a line feed, a vertical tab, a form feed or a return. */
    private fun isSpace(char: Char): Boolean = char == ' ' || char in '\t'..'\r'

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
            hop.stage == Stage.HANDLED && hop.subscription == SystemSubscriptions.SNAPSHOT ->
                if (context == null) "snapshot" else "snapshot($context)"

            hop.stage == Stage.HANDLED && hop.subscription == SystemSubscriptions.QUERYABLE ->
                if (context == null) "queryable" else "queryable($context)"

            hop.stage == Stage.HANDLED ->
                if (context == null) "handled(${hop.subscription})" else "handled($context/${hop.subscription})"

            context == null -> WireNames.of(hop.stage)
            else -> "${WireNames.of(hop.stage)}($context)"
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
        require(duration >= 1.milliseconds) { "A wait timeout is one millisecond or more: '$value'." }
        return duration
    }

    /** [timeout] as [parseTimeout] reads it. */
    fun formatTimeout(timeout: Duration): String {
        val millis = timeout.inWholeMilliseconds
        return if (millis % MILLIS_PER_SECOND == 0L) "${millis / MILLIS_PER_SECOND}s" else "${millis}ms"
    }

    private const val MILLIS_PER_SECOND = 1000L

    private val PARENTHESES = charArrayOf('(', ')')
}
