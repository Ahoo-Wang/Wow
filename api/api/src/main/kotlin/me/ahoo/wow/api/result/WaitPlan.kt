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

package me.ahoo.wow.api.result

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * How far a command has come (decision D61). There are three stages; which kind of subscription handled a commit
 * (a projection, a reactor, a saga, the snapshot) is the subscription's own business, not a stage of its own.
 */
enum class Stage {
    /** The command passed admission. Durable only when it was routed through Kafka. */
    ACCEPTED,

    /** The command's events are appended to the log (or the command was settled without appending). */
    COMMITTED,

    /** A named subscription has handled the commit. */
    HANDLED,
}

/**
 * One hop of a wait chain (decision D62): a [stage], in a bounded [context], of a [subscription] when the stage is
 * [Stage.HANDLED].
 *
 * A null [context] means the context of the command the wait started with.
 */
data class Hop @JvmOverloads constructor(
    val stage: Stage,
    val context: String? = null,
    val subscription: String? = null
) {
    init {
        require(context == null || context.isNotBlank()) { "A hop's context, when present, is not blank." }
        if (stage == Stage.HANDLED) {
            require(!subscription.isNullOrBlank()) { "A HANDLED hop names its subscription." }
        } else {
            require(subscription == null) { "Only a HANDLED hop names a subscription." }
        }
    }

    /**
     * This hop as the `Wow-Wait` header spells it (decision D99): `accepted`, `committed`, `snapshot` and
     * `handled(subscription)`, each with its context when it has one: `committed(ctx)`, `snapshot(ctx)`,
     * `handled(ctx/subscription)`. A subscription may hold dots (`processor.function`), so `/` sets the context apart.
     */
    override fun toString(): String {
        val keyword = if (subscription == SNAPSHOT_SUBSCRIPTION) "snapshot" else stage.name.lowercase()
        val argument = when {
            subscription == null || subscription == SNAPSHOT_SUBSCRIPTION -> context
            context == null -> subscription
            else -> "$context/$subscription"
        }
        return if (argument == null) keyword else "$keyword($argument)"
    }

    companion object {
        /** The name of the built-in subscription that writes snapshots. */
        const val SNAPSHOT_SUBSCRIPTION: String = "snapshot"

        /** The name of the built-in subscription that confirms reservations (decision D50). */
        const val RESERVATION_SUBSCRIPTION: String = "reservation"

        /** The name of the built-in subscription that writes the timers of scheduled commands (decision D166). */
        const val TIMERS_SUBSCRIPTION: String = "timers"

        /** [Stage.ACCEPTED] in [context]. */
        @JvmStatic
        @JvmOverloads
        fun accepted(context: String? = null): Hop = Hop(Stage.ACCEPTED, context)

        /** [Stage.COMMITTED] in [context]. */
        @JvmStatic
        @JvmOverloads
        fun committed(context: String? = null): Hop = Hop(Stage.COMMITTED, context)

        /** [Stage.HANDLED] by [subscription] of [context]. */
        @JvmStatic
        @JvmOverloads
        fun handled(subscription: String, context: String? = null): Hop = Hop(Stage.HANDLED, context, subscription)

        /** [Stage.HANDLED] by the snapshot subscription: the snapshot holds the commit (decision D69). */
        @JvmStatic
        @JvmOverloads
        fun snapshot(context: String? = null): Hop = handled(SNAPSHOT_SUBSCRIPTION, context)
    }
}

/**
 * What the caller of a command waits for (design 04 §6; decisions D61–D64, D69): the [hops] of a chain, reached in
 * order, within [timeout]. It stays on the node that took the command; what travels with the command's messages is a
 * wait context instead.
 *
 * Within one command the stages come in order (accepted, committed, handled); a HANDLED hop of a saga may be followed
 * by more hops, which then apply to the commands that saga derived from the commit. The runtime checks the rest of
 * the chain's shape at admission.
 *
 * [isDefault] marks [DEFAULT], the plan of a caller that asked for none: it waits for the snapshot, but where the
 * aggregate has no snapshot subscription it waits for COMMITTED instead (decision D69). Any other plan is taken as
 * asked, so a snapshot hop the aggregate cannot reach is rejected at admission. [within] keeps the mark.
 *
 * From Java, build a plan with [of] and give it a timeout with the `java.time.Duration` overloads of the constructor
 * and [within]; read it back with [javaTimeout].
 */
data class WaitPlan(val hops: List<Hop>, val timeout: Duration = DEFAULT_TIMEOUT, val isDefault: Boolean = false) {
    init {
        require(hops.isNotEmpty()) { "A wait plan has at least one hop." }
        require(timeout.isPositive()) { "A wait timeout is positive." }
        require(!isDefault || hops == DEFAULT_HOPS) { "Only the snapshot plan is the default." }
    }

    /** Until [hops] are reached, in order, within [timeout]. */
    constructor(hops: List<Hop>, timeout: JavaDuration) : this(hops, timeout.toKotlinDuration())

    /** [timeout] as a `java.time.Duration`. */
    val javaTimeout: JavaDuration get() = timeout.toJavaDuration()

    /** This plan waiting at most [timeout]. */
    fun within(timeout: Duration): WaitPlan = copy(timeout = timeout)

    /** This plan waiting at most [timeout]. */
    fun within(timeout: JavaDuration): WaitPlan = within(timeout.toKotlinDuration())

    companion object {
        private val DEFAULT_HOPS = listOf(Hop.snapshot())

        /** The timeout of a wait whose caller gives none (decision D64). */
        val DEFAULT_TIMEOUT: Duration = 10.seconds

        /**
         * The plan of a caller that asked for none (decision D69): until the snapshot holds the commit, so the caller
         * reads its own write; until COMMITTED for an aggregate without a snapshot subscription.
         */
        @JvmField
        val DEFAULT: WaitPlan = WaitPlan(DEFAULT_HOPS, isDefault = true)

        /**
         * Until the snapshot holds the commit, asked for explicitly: rejected at admission for an aggregate without a
         * snapshot subscription. Equal to `of(Hop.snapshot())`.
         */
        @JvmField
        val SNAPSHOT: WaitPlan = WaitPlan(DEFAULT_HOPS)

        /** Until the events are appended. */
        @JvmField
        val COMMITTED: WaitPlan = WaitPlan(listOf(Hop.committed()))

        /** Until the command passed admission. */
        @JvmField
        val ACCEPTED: WaitPlan = WaitPlan(listOf(Hop.accepted()))

        /** Until [hops] are reached, in order. */
        @JvmStatic
        fun of(vararg hops: Hop): WaitPlan = WaitPlan(hops.toList())
    }
}
