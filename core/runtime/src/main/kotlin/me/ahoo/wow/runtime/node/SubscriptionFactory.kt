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

package me.ahoo.wow.runtime.node

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.runtime.command.ReceiptHandler
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.spi.wait.WaitContext

/**
 * The application's subscriptions of one bounded context (projections, reactors, sagas), beside the built-in
 * `wow.snapshot` and `wow.reservation` ones. Called once per context while the node is assembled, with the context's
 * parts, so a saga can [forward][me.ahoo.wow.runtime.wait.WaitCoordinator.forward] through its coordinator; the
 * subscriptions are registered when the node starts. The annotated processors' come from
 * [me.ahoo.wow.runtime.binding.SubscriptionBindings].
 *
 * [sourced] are the context's subscriptions of other contexts' published streams: the node reads
 * each stream's topic ([SourceTopics]) for them instead of the log.
 */
@InternalWowApi
fun interface SubscriptionFactory {
    fun subscriptions(context: ContextNode): List<Subscription>

    /**
     * The subscriptions of [context] that read the published streams of other contexts' aggregates,
     * each with the aggregate types it reads. The node finds each type's topic in its contracts
     * ([me.ahoo.wow.runtime.contract.ContractDirectory.publishedOf]) and reads it ([SourceTopics]). None by default.
     */
    fun sourced(context: ContextNode): List<SourcedSubscription> = emptyList()

    /**
     * What handles the receipts of the commands [context]'s sagas sent to other contexts that rejected or committed
     * them: the node reads the context's receipts topic for it ([SourceTopics.receiptsOf]). None
     * by default; a factory whose sagas have `@OnRejected` or `@OnCommitted` handlers gives them.
     */
    fun receipts(context: ContextNode): ReceiptHandler? = null

    /**
     * The `@OnCommitted` handlers of [context]'s sagas, by name (`processor.function`): each is a saga's hop a wait
     * chain may name, though no subscription of the context's log runs it. None by default.
     */
    fun committedHandlers(context: String): Set<String> = emptySet()

    /**
     * The jobs of [context]'s `@OnSchedule` handlers, by the name of the subscription that runs each, asked before the
     * context is assembled: the node adds the built-in job aggregate `<context>.schedule` deciding them, starts the
     * SYSTEM and SHARDED ones, and gives each subscription its job's settings ([ServiceNodeOptions.schedules]). None by
     * default.
     */
    fun jobs(context: String): Map<String, ScheduleJob> = emptyMap()

    /**
     * The aggregate types among [context]'s [models] whose state after each commit the subscriptions read:
     * the context's executor keeps their shared states ([ContextNode.views]), asked before the context is
     * assembled. None by default.
     */
    fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> = emptySet()

    /**
     * The state consumers among the subscriptions of [context], by the reader name each reads the
     * shared states with ([ContextNode.stateReader]), with the aggregate types it reads: a view is evicted once every
     * reader of its type passed it, or it ages out. By default one reader named after this factory's class, of the
     * types [viewTypes] names: it passes nothing unless its subscriptions read under that name, so their views only
     * age out. A factory that overrides it also overrides [viewTypes] with the types it names.
     */
    fun stateReaders(context: String, models: List<AggregateModel<*>>): Map<String, Set<AggregateType>> {
        val types = viewTypes(context, models)
        return if (types.isEmpty()) emptyMap() else mapOf(javaClass.name to types)
    }

    /** This factory's subscriptions, then [other]'s, with the state readers either has. */
    operator fun plus(other: SubscriptionFactory): SubscriptionFactory {
        val first = this
        return object : SubscriptionFactory {
            override fun subscriptions(context: ContextNode): List<Subscription> =
                first.subscriptions(context) + other.subscriptions(context)

            override fun sourced(context: ContextNode): List<SourcedSubscription> =
                first.sourced(context) + other.sourced(context)

            override fun committedHandlers(context: String): Set<String> =
                first.committedHandlers(context) + other.committedHandlers(context)

            override fun receipts(context: ContextNode): ReceiptHandler? {
                val mine = first.receipts(context) ?: return other.receipts(context)
                val theirs = other.receipts(context) ?: return mine
                return object : ReceiptHandler {
                    override suspend fun rejected(
                        rejected: CommandReceipt.Rejected<*>,
                        command: Class<*>
                    ): List<String>? =
                        mine.rejected(rejected, command) ?: theirs.rejected(rejected, command)

                    override suspend fun committed(
                        committed: CommandReceipt.Committed<*>,
                        command: Class<*>,
                        wait: WaitContext?,
                    ): List<String>? =
                        mine.committed(committed, command, wait) ?: theirs.committed(committed, command, wait)
                }
            }

            override fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> =
                first.viewTypes(context, models) + other.viewTypes(context, models)

            override fun jobs(context: String): Map<String, ScheduleJob> = first.jobs(context) + other.jobs(context)

            override fun stateReaders(
                context: String,
                models: List<AggregateModel<*>>,
            ): Map<String, Set<AggregateType>> {
                val readers = first.stateReaders(context, models).toMutableMap()
                other.stateReaders(context, models).forEach { (reader, types) ->
                    readers.merge(reader, types) { left, right -> left + right }
                }
                return readers
            }
        }
    }

    @InternalWowApi
    companion object {
        val NONE: SubscriptionFactory = SubscriptionFactory { emptyList() }

        /** [factories] in their order, as [plus] combines two; [NONE] when there are none. */
        @JvmStatic
        fun of(factories: List<SubscriptionFactory>): SubscriptionFactory =
            factories.reduceOrNull { all, factory -> all + factory } ?: NONE
    }
}

/**
 * A [subscription] that reads the published streams of [types], aggregates of another context: its
 * filter is of that context, and it handles the records read from each type's topic.
 */
@InternalWowApi
data class SourcedSubscription(val subscription: Subscription, val types: Set<AggregateType>) {
    init {
        require(types.isNotEmpty()) { "${subscription.name} reads no published stream." }
        require(types.all { it.context == subscription.filter.context }) {
            "${subscription.name} reads $types, not all of its filter's context ${subscription.filter.context}."
        }
    }
}
