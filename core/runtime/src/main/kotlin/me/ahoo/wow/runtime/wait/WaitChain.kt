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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.manifest.ContextName

/** What checking a wait plan gives: a [WaitChain], or [WaitChain.Invalid]. */
internal sealed interface CheckedPlan

/**
 * A wait plan checked and made concrete for a command of an aggregate type: every hop
 * names its context, a null one being the type's context.
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
         * - every HANDLED hop names a subscription [directory] knows, in any context, and only a saga's may be followed
         *   by more hops, which apply to the commands it derived;
         * - an ACCEPTED or COMMITTED hop is of the command's context, or, after a saga's hop, of a context [directory]
         *   knows ([SubscriptionDirectory.hasContext]), one the saga derives commands of when it declares them
         *   ([SubscriptionDirectory.derivedContextsOf]; undeclared, it is not checked);
         * - with [unknownContexts] [UnknownContexts.ALLOW], a hop of a context [directory] does not
         *   know is let through when the context's name is legal: its subscription and kind are
         *   checked where the chain arrives, and a HANDLED hop of it may be followed by more hops;
         * - a snapshot hop of the command itself (its first HANDLED hop) is one [type] has: the snapshot subscription
         *   of [type]'s context covers [type] ([SubscriptionDirectory.covers]).
         *
         * Only the default plan ([WaitPlan.isDefault]) waits for COMMITTED instead where [type] has no snapshot to
         * wait for: the aggregate has its snapshots disabled, or its context has no snapshot subscription.
         * A plan that asks for that snapshot explicitly is rejected, rather than left to time out.
         */
        fun of(
            plan: WaitPlan,
            type: AggregateType,
            directory: SubscriptionDirectory,
            maxHops: Int,
            unknownContexts: UnknownContexts = UnknownContexts.REJECT,
        ): CheckedPlan {
            val context = type.context
            val snapshots = directory.covers(Hop.SNAPSHOT_SUBSCRIPTION, type)
            if (plan.isDefault && !snapshots) {
                return WaitChain(listOf(Hop.committed(context)))
            }
            val hops = plan.hops.map { if (it.context == null) it.copyWith(context = context) else it }
            val own = hops.firstOrNull { it.stage == Stage.HANDLED }
            if (!snapshots && own == Hop.snapshot(context)) {
                return Invalid("Hop $own waits for a snapshot, but $type has no snapshot subscription.")
            }
            if (hops.size > maxHops) {
                return Invalid("A wait chain has at most $maxHops hops, not ${hops.size}.")
            }
            var last: Stage? = null
            var saga: Hop? = null
            hops.forEachIndexed { index, hop ->
                val order = last?.let { hop.stage > it } ?: true
                if (!order) {
                    return Invalid("Hop $hop cannot follow a ${last!!.name} hop of the same command.")
                }
                last = hop.stage
                if (hop.stage == Stage.HANDLED) {
                    val followed = index < hops.lastIndex
                    handledProblem(hop, followed, directory, unknownContexts)?.let { return Invalid(it) }
                    if (followed) {
                        last = null
                        saga = hop
                    }
                } else {
                    contextProblem(hop, saga, context, directory, unknownContexts)?.let { return Invalid(it) }
                }
            }
            return WaitChain(hops)
        }

        /**
         * Why the HANDLED [hop] cannot be reached, or null: it names a known subscription, a saga's when [followed];
         * or, when [unknownContexts] allows it, any subscription of a context [directory] does not know.
         */
        private fun handledProblem(
            hop: Hop,
            followed: Boolean,
            directory: SubscriptionDirectory,
            unknownContexts: UnknownContexts,
        ): String? {
            val of = hop.context!!
            if (!directory.hasContext(of)) {
                return unknownContextProblem(hop, unknownContexts)
            }
            val kind = directory.kindOf(of, hop.subscription!!)
                ?: return "Hop $hop names an unknown subscription."
            return "Only a saga's hop may be followed by more hops; $hop is a $kind.".takeIf {
                followed && kind != FunctionKind.SAGA
            }
        }

        /**
         * Why [hop], of a context the directory does not know, cannot be followed, or null when [unknownContexts]
         * lets it through: the context's name is legal, so the node it arrives at checks it.
         */
        private fun unknownContextProblem(hop: Hop, unknownContexts: UnknownContexts): String? {
            val of = hop.context!!
            if (unknownContexts == UnknownContexts.REJECT) {
                return "Hop $hop names an unknown context."
            }
            return ContextName.problem(of)?.let { "Hop $hop names no context: $it." }
        }

        /**
         * Why the ACCEPTED or COMMITTED [hop] cannot be reached, or null: the root command's hops are of its
         * [context]; after the [saga] hop, the derived commands' are of a context the [directory] knows, and one the
         * saga declares it derives commands of, if it declares any.
         */
        private fun contextProblem(
            hop: Hop,
            saga: Hop?,
            context: String,
            directory: SubscriptionDirectory,
            unknownContexts: UnknownContexts,
        ): String? {
            val of = hop.context!!
            if (saga == null) {
                return "Hop $hop is not of the command's context $context.".takeIf { of != context }
            }
            if (!directory.hasContext(of)) {
                unknownContextProblem(hop, unknownContexts)?.let { return it }
            }
            val derived = directory.derivedContextsOf(saga.context!!, saga.subscription!!) ?: return null
            if (of in derived) {
                return null
            }
            return "Hop $hop follows ${saga.context}/${saga.subscription}, which derives commands of " +
                "${derived.sorted()} only."
        }
    }
}
