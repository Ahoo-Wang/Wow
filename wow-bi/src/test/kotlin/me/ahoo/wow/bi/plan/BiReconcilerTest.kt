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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.BiAnchorState
import me.ahoo.wow.bi.BiConsumerIdentity
import me.ahoo.wow.bi.BiDeploymentDescriptor
import me.ahoo.wow.bi.BiDeploymentPhase
import me.ahoo.wow.bi.BiDurableEntry
import me.ahoo.wow.bi.BiDurableStatus
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiObjectMetadata
import me.ahoo.wow.bi.BiOwnedObject
import me.ahoo.wow.bi.BiScriptGenerator
import me.ahoo.wow.bi.BiScriptOperation
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.DesiredBiObject
import me.ahoo.wow.bi.ObservedBiDeployment
import me.ahoo.wow.bi.ObservedBiObject
import me.ahoo.wow.bi.layout.BiEngine
import me.ahoo.wow.bi.layout.BiLayout
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test

class BiReconcilerTest {
    private val options = BiScriptOptions(consumerGroupNamespace = "test", topology = ClickHouseTopology.Standalone)
    private val descriptor = BiDeploymentDescriptor.from(options)
    private val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }
    private val preparation = BiScriptGenerator(options).prepare(setOf(aggregate))
    private val desired = preparation.desiredObjects
    private val names = BiLayout(options).of(aggregate)
    private val layout = BiLayout(options)
    private val computedKeys = desired.filter { it.expectedQuery != null }.mapTo(hashSetOf(), DesiredBiObject::key)

    @Test
    fun `should create every object for an offline preview`() {
        val plan = plan(BiScriptOperation.Deploy, observed = null)

        plan.authoritative.assert().isFalse()
        plan.drops.assert().isEmpty()
        desired.filter { it.kind != BiObjectKind.ANCHOR }
            .all { plan.action(it.key) == BiObjectAction.CREATE }.assert().isTrue()
    }

    @Test
    fun `should keep everything an idempotent deploy observes as desired`() {
        val plan = plan(BiScriptOperation.Deploy, observed(desired), verified = computedKeys)

        plan.authoritative.assert().isTrue()
        desired.filter { it.kind != BiObjectKind.ANCHOR }
            .all { plan.action(it.key) == BiObjectAction.KEEP }.assert().isTrue()
        plan.drops.assert().isEmpty()
    }

    @Test
    fun `should replace an unverified view and create a missing one`() {
        val drifted = layout.viewKey(names.command.table)
        val missing = layout.viewKey(names.stateEvent)
        val plan = plan(
            BiScriptOperation.Deploy,
            observed(desired.filterNot { it.key == missing }),
            verified = computedKeys - drifted - missing,
        )

        plan.action(drifted).assert().isEqualTo(BiObjectAction.REPLACE)
        plan.action(missing).assert().isEqualTo(BiObjectAction.CREATE)
        plan.action(layout.viewKey(names.state.table)).assert().isEqualTo(BiObjectAction.KEEP)
    }

    @Test
    fun `should replace a whole ingress chain when one consumer is not verified`() {
        val stateLastConsumer = layout.ingressKey(names.stateLastConsumer)
        val plan = plan(BiScriptOperation.Deploy, observed(desired), verified = computedKeys - stateLastConsumer)

        plan.action(stateLastConsumer).assert().isEqualTo(BiObjectAction.REPLACE)
        plan.action(layout.ingressKey(names.state.consumer)).assert().isEqualTo(BiObjectAction.REPLACE)
        plan.action(layout.ingressKey(names.command.consumer)).assert().isEqualTo(BiObjectAction.KEEP)
        plan.action(layout.ingressKey(names.state.queue)).assert().isEqualTo(BiObjectAction.KEEP)
    }

    @Test
    fun `should not keep the consumers of a queue that has to be created`() {
        val queue = layout.ingressKey(names.command.queue)
        val plan = plan(BiScriptOperation.Deploy, observed(desired.filterNot { it.key == queue }), computedKeys)

        plan.action(queue).assert().isEqualTo(BiObjectAction.CREATE)
        plan.action(layout.ingressKey(names.command.consumer)).assert().isEqualTo(BiObjectAction.REPLACE)
    }

    @Test
    fun `should drop undesired computed objects and queues and retire undesired stores`() {
        val staleStore = owned("bi_db", "bi_gone_state_store", BiObjectKind.STORE)
        val staleQueue = owned("bi_db_consumer", "bi_gone_state_queue", BiObjectKind.QUEUE)
        val staleView = owned("bi_db", "bi_gone_state", BiObjectKind.VIEW)
        val plan = plan(
            BiScriptOperation.Deploy,
            ObservedBiDeployment(observed(desired).objects + listOf(staleStore, staleQueue, staleView)),
            computedKeys,
        )

        plan.drops.assert().containsExactlyInAnyOrder(
            BiOwnedObject(staleQueue.key, BiObjectKind.QUEUE),
            BiOwnedObject(staleView.key, BiObjectKind.VIEW),
        )
        plan.durableInventory.assert().contains(BiDurableEntry(staleStore.key, BiDurableStatus.RETIRED))
        plan.durableInventory.map(BiDurableEntry::key).assert().doesNotContain(staleQueue.key)
    }

    @Test
    fun `should drop everything owned and create everything on reset`() {
        val plan = plan(BiScriptOperation.Reset(true), observed(desired), computedKeys)

        desired.filter { it.kind != BiObjectKind.ANCHOR }
            .all { plan.action(it.key) == BiObjectAction.CREATE }.assert().isTrue()
        plan.drops.map(BiOwnedObject::key).assert().doesNotContain(layout.anchor)
        plan.drops.size.assert().isEqualTo(desired.size - 1)
        plan.durableInventory.all { it.status == BiDurableStatus.ACTIVE }.assert().isTrue()
        plan.durableInventory.map(BiDurableEntry::key).assert()
            .containsExactlyInAnyOrder(
                layout.storeKey(names.command.store),
                layout.storeKey(names.state.store),
                layout.storeKey(names.stateLastStore),
            )
    }

    private fun plan(
        operation: BiScriptOperation,
        observed: ObservedBiDeployment?,
        verified: Set<BiObjectKey> = emptySet(),
    ): BiChangePlan = BiReconciler(options).plan(preparation.plannedAggregates, desired, operation, observed, verified)

    private fun observed(objects: List<DesiredBiObject>): ObservedBiDeployment = ObservedBiDeployment(
        objects.map { desiredObject ->
            if (desiredObject.kind == BiObjectKind.ANCHOR) {
                anchor()
            } else {
                ObservedBiObject(
                    database = desiredObject.key.database,
                    name = desiredObject.key.name,
                    engine = desiredObject.expectedEngine,
                    metadata = BiObjectMetadata(
                        deploymentId = descriptor.deploymentId,
                        kind = desiredObject.kind,
                        aggregate = desiredObject.aggregate,
                    ),
                )
            }
        }
    )

    private fun owned(database: String, name: String, kind: BiObjectKind): ObservedBiObject = ObservedBiObject(
        database = database,
        name = name,
        engine = when (kind) {
            BiObjectKind.STORE -> BiEngine.REPLACING_MERGE_TREE
            BiObjectKind.QUEUE -> BiEngine.KAFKA
            else -> BiEngine.VIEW
        },
        metadata = BiObjectMetadata(deploymentId = descriptor.deploymentId, kind = kind, aggregate = "bi-service.gone"),
    )

    private fun anchor(): ObservedBiObject = ObservedBiObject(
        database = layout.anchor.database,
        name = layout.anchor.name,
        engine = BiEngine.VIEW,
        metadata = BiObjectMetadata(
            deploymentId = descriptor.deploymentId,
            kind = BiObjectKind.ANCHOR,
            anchor = BiAnchorState(
                phase = BiDeploymentPhase.STABLE,
                configurationFingerprint = descriptor.configurationFingerprint,
                topologyFingerprint = descriptor.topologyFingerprint,
                consumerIdentity = BiConsumerIdentity.deterministic(descriptor).value,
            ),
        ),
    )
}
