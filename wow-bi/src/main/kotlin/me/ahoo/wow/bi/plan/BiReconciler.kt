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

package me.ahoo.wow.bi.plan

import me.ahoo.wow.bi.BiConsumerIdentity
import me.ahoo.wow.bi.BiDeploymentDescriptor
import me.ahoo.wow.bi.BiDurableEntry
import me.ahoo.wow.bi.BiDurableStatus
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiOperationPolicy
import me.ahoo.wow.bi.BiOwnedObject
import me.ahoo.wow.bi.BiScriptOperation
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.DesiredBiObject
import me.ahoo.wow.bi.ObservedBiDeployment
import me.ahoo.wow.bi.PlannedAggregate
import me.ahoo.wow.bi.layout.BiLayout

/**
 * Compares the desired layout with the observed catalog and decides, per object, what the script does.
 *
 * DEPLOY keeps what exists as desired and creates what is missing. A computed object it cannot keep is replaced;
 * a consumer is kept only together with its whole ingress chain, because a consumer dropped while the Kafka engine
 * is between its view check and its insert lets that poll cycle commit messages without writing them, and the state
 * consumer feeds `state_last` through the state store. Undesired owned objects are dropped, except stores, which are
 * retired with their data. RESET drops everything the deployment owns and creates everything again.
 */
internal class BiReconciler(private val options: BiScriptOptions) {
    private val layout = BiLayout(options)
    private val policy = BiOperationPolicy(options)

    fun plan(
        plannedAggregates: List<PlannedAggregate>,
        desiredObjects: List<DesiredBiObject>,
        operation: BiScriptOperation,
        observed: ObservedBiDeployment?,
        verifiedComputedKeys: Set<BiObjectKey>,
    ): BiChangePlan {
        val descriptor = BiDeploymentDescriptor.from(options)
        observed?.let { deployment -> policy.validate(deployment, descriptor, desiredObjects, operation) }
        val owned = observed?.let { deployment ->
            policy.ownedBy(deployment, descriptor).map { BiOwnedObject(it.key, checkNotNull(it.metadata).kind) }
        }.orEmpty()
        val ownedKeys = owned.mapTo(hashSetOf(), BiOwnedObject::key)
        val desiredKeys = desiredObjects.mapTo(hashSetOf(), DesiredBiObject::key)
        val keptConsumers = if (operation == BiScriptOperation.Deploy) {
            keptIngressChains(plannedAggregates, ownedKeys, verifiedComputedKeys)
        } else {
            emptySet()
        }
        val actions = desiredObjects.associate { desired ->
            desired.key to actionOf(desired, operation, ownedKeys, keptConsumers, verifiedComputedKeys)
        }
        val drops = when (operation) {
            BiScriptOperation.Deploy -> owned.filter { it.key !in desiredKeys && it.kind != BiObjectKind.STORE }
            is BiScriptOperation.Reset -> owned.filter { it.key != layout.anchor }
        }
        return BiChangePlan(
            operation = operation,
            authoritative = observed != null,
            consumerIdentity = consumerIdentity(operation, descriptor, observed),
            actions = actions,
            drops = drops,
            durableInventory = durableInventory(operation, desiredObjects, owned),
        )
    }

    /**
     * The action for one desired object. The anchor is rewritten by every script; RESET and missing objects are
     * created; existing stores and queues are kept; an existing consumer is kept with its ingress chain; any other
     * existing computed object is kept only when its definition was verified.
     */
    private fun actionOf(
        desired: DesiredBiObject,
        operation: BiScriptOperation,
        ownedKeys: Set<BiObjectKey>,
        keptConsumers: Set<BiObjectKey>,
        verifiedComputedKeys: Set<BiObjectKey>,
    ): BiObjectAction = when {
        desired.kind == BiObjectKind.ANCHOR -> BiObjectAction.REPLACE
        operation is BiScriptOperation.Reset || desired.key !in ownedKeys -> BiObjectAction.CREATE
        desired.kind == BiObjectKind.STORE || desired.kind == BiObjectKind.QUEUE -> BiObjectAction.KEEP
        desired.kind == BiObjectKind.CONSUMER ->
            if (desired.key in keptConsumers) BiObjectAction.KEEP else BiObjectAction.REPLACE
        desired.key in verifiedComputedKeys -> BiObjectAction.KEEP
        else -> BiObjectAction.REPLACE
    }

    /** The consumers of every stream whose queue and whole consumer chain are verified as desired. */
    private fun keptIngressChains(
        plannedAggregates: List<PlannedAggregate>,
        ownedKeys: Set<BiObjectKey>,
        verifiedComputedKeys: Set<BiObjectKey>,
    ): Set<BiObjectKey> = plannedAggregates.flatMapTo(linkedSetOf()) { planned ->
        val names = layout.of(planned.namedAggregate)
        listOf(
            names.command to listOf(names.command.consumer),
            names.state to listOf(names.state.consumer, names.stateLastConsumer),
        ).filter { (stream, chain) ->
            layout.ingressKey(stream.queue) in ownedKeys &&
                chain.all { consumer -> layout.ingressKey(consumer) in verifiedComputedKeys }
        }.flatMap { (_, chain) -> chain.map(layout::ingressKey) }
    }

    /**
     * The stores and queues that exist when the anchor is written, so the inventory never runs ahead of the catalog.
     *
     * DEPLOY writes the anchor last: every desired store and queue, plus the stores it keeps for their data after
     * their aggregate left. RESET writes it before Kafka ingress, so it records the stores only; the next DEPLOY
     * records the queues.
     */
    private fun durableInventory(
        operation: BiScriptOperation,
        desiredObjects: List<DesiredBiObject>,
        owned: List<BiOwnedObject>,
    ): List<BiDurableEntry> {
        val recorded = if (operation == BiScriptOperation.Deploy) {
            setOf(BiObjectKind.STORE, BiObjectKind.QUEUE)
        } else {
            setOf(BiObjectKind.STORE)
        }
        val desired = desiredObjects.filter { it.kind in recorded }
            .map { BiDurableEntry(it.key, BiDurableStatus.ACTIVE) }
        val desiredKeys = desired.mapTo(hashSetOf(), BiDurableEntry::key)
        val retired = if (operation == BiScriptOperation.Deploy) {
            owned.filter { it.kind == BiObjectKind.STORE && it.key !in desiredKeys }
                .map { BiDurableEntry(it.key, BiDurableStatus.RETIRED) }
        } else {
            emptyList()
        }
        return (desired + retired).sortedWith(compareBy({ it.key.database }, { it.key.name }))
    }

    private fun consumerIdentity(
        operation: BiScriptOperation,
        descriptor: BiDeploymentDescriptor,
        observed: ObservedBiDeployment?,
    ): BiConsumerIdentity = when (operation) {
        BiScriptOperation.Deploy -> observed?.let { deployment -> policy.consumerIdentity(deployment, descriptor) }
            ?: BiConsumerIdentity.deterministic(descriptor)

        is BiScriptOperation.Reset -> observed?.let { deployment -> policy.resettingAnchor(deployment, descriptor) }
            ?.consumerIdentity?.let(::BiConsumerIdentity) ?: BiConsumerIdentity.random()
    }
}
