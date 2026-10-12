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

package me.ahoo.wow.runtime.wait

import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan

/** What checking a wait plan gives: a [WaitChain], or [WaitChain.Invalid]. */
internal sealed interface CheckedPlan

/**
 * A wait plan checked and made concrete for a command of [context] (design 04 §6.1, decision D62): every hop names
 * its context, a null one being [context].
 */
internal class WaitChain private constructor(val hops: List<Hop>) : CheckedPlan {
    /** A plan that is not a chain the runtime can follow, and why. */
    class Invalid(val reason: String) : CheckedPlan

    companion object {
        /**
         * The chain of [plan] for a command of [context], or why it is not one:
         *
         * - at most [maxHops] hops;
         * - within one command the stages come in order, each at most once: ACCEPTED, COMMITTED, then HANDLED;
         * - every HANDLED hop names a subscription [directory] knows, and only a saga's may be followed by more hops,
         *   which apply to the commands it derived.
         *
         * The default plan, [WaitPlan.SNAPSHOT], waits for COMMITTED instead when [directory] knows no snapshot
         * subscription in [context]: its aggregates have no snapshot to wait for (decision D69).
         */
        fun of(plan: WaitPlan, context: String, directory: SubscriptionDirectory, maxHops: Int): CheckedPlan {
            var hops = plan.hops.map { if (it.context == null) it.copy(context = context) else it }
            if (plan.hops == WaitPlan.SNAPSHOT.hops &&
                directory.kindOf(context, Hop.SNAPSHOT_SUBSCRIPTION) == null
            ) {
                hops = listOf(Hop.committed(context))
            }
            if (hops.size > maxHops) {
                return Invalid("A wait chain has at most $maxHops hops, not ${hops.size}.")
            }
            var last: Stage? = null
            hops.forEachIndexed { index, hop ->
                val order = last?.let { hop.stage > it } ?: true
                if (!order) {
                    return Invalid("Hop $hop cannot follow a ${last!!.name} hop of the same command.")
                }
                last = hop.stage
                if (hop.stage == Stage.HANDLED) {
                    val kind = directory.kindOf(hop.context!!, hop.subscription!!)
                        ?: return Invalid("Hop $hop names an unknown subscription.")
                    if (index < hops.lastIndex) {
                        if (kind != FunctionKind.SAGA) {
                            return Invalid("Only a saga's hop may be followed by more hops; $hop is a $kind.")
                        }
                        last = null
                    }
                }
            }
            return WaitChain(hops)
        }
    }
}
