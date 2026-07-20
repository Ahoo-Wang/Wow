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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BiOwnershipRegistryPlanTest {
    private val options = BiScriptOptions(
        topology = ClickHouseTopology.Standalone,
        consumerGroupNamespace = "orders",
    )
    private val descriptor = BiDeploymentDescriptor.from(options)
    private val consumerIdentity = BiConsumerIdentity.deterministic(descriptor)
    private val desiredStore = DesiredBiObject(
        key = BiObjectKey(options.database, "order_state_store"),
        aggregate = "order",
        kind = BiObjectKind.STORE,
        expectedEngine = "ReplacingMergeTree",
    )

    @Test
    fun `retiring an undesired store should persist the write-ahead mutation`() {
        val current = activeRegistry(desiredStore)

        val plan = BiOwnershipRegistryPlan.create(
            descriptor = descriptor,
            consumerIdentity = consumerIdentity,
            desiredObjects = emptyList(),
            current = current,
        )

        plan.intentChanged.assert().isTrue()
        plan.verificationChanged.assert().isFalse()
        plan.beforeMutation.entries.single().status.assert().isEqualTo(BiRegistryEntryStatus.RETIRED)
        plan.afterVerification.assert().isEqualTo(plan.beforeMutation)
    }

    @Test
    fun `desired retired object should be reactivated through verified create transition`() {
        val retired = activeRegistry(desiredStore).retire(desiredStore.key)

        val plan = BiOwnershipRegistryPlan.create(
            descriptor = descriptor,
            consumerIdentity = consumerIdentity,
            desiredObjects = listOf(desiredStore),
            current = retired,
        )

        plan.intentChanged.assert().isTrue()
        plan.verificationChanged.assert().isTrue()
        plan.beforeMutation.entries.single().status.assert()
            .isEqualTo(BiRegistryEntryStatus.PENDING_CREATE)
        plan.afterVerification.entries.single().status.assert()
            .isEqualTo(BiRegistryEntryStatus.ACTIVE)
    }

    @Test
    fun `existing active registration should reject a different desired contract`() {
        val current = activeRegistry(desiredStore)
        val incompatible = desiredStore.copy(expectedEngine = "MergeTree")

        assertThrows<IllegalStateException> {
            BiOwnershipRegistryPlan.create(
                descriptor = descriptor,
                consumerIdentity = consumerIdentity,
                desiredObjects = listOf(incompatible),
                current = current,
            )
        }.message.assert().contains("registration contract")
    }

    @Test
    fun `existing computed object should update its definition through a verified transition`() {
        val current = activeRegistry(desiredView)
        val evolvedView = desiredView.copy(
            expectedQuery = ExpectedBiQuery("SELECT * FROM order_state_store FINAL WHERE deleted = false")
        )

        val plan = BiOwnershipRegistryPlan.create(
            descriptor = descriptor,
            consumerIdentity = consumerIdentity,
            desiredObjects = listOf(evolvedView),
            current = current,
        )

        plan.intentChanged.assert().isTrue()
        plan.verificationChanged.assert().isTrue()
        plan.beforeMutation.entries.single().status.assert()
            .isEqualTo(BiRegistryEntryStatus.PENDING_UPDATE)
        plan.afterVerification.entries.single().status.assert()
            .isEqualTo(BiRegistryEntryStatus.ACTIVE)
        plan.afterVerification.entries.single().definitionFingerprint.assert()
            .isNotEqualTo(current.entries.single().definitionFingerprint)
    }

    @Test
    fun `unchanged active registration should not write another revision`() {
        val current = activeRegistry(desiredStore)

        val plan = BiOwnershipRegistryPlan.create(
            descriptor = descriptor,
            consumerIdentity = consumerIdentity,
            desiredObjects = listOf(desiredStore),
            current = current,
        )

        plan.intentChanged.assert().isFalse()
        plan.verificationChanged.assert().isFalse()
        plan.beforeMutation.assert().isEqualTo(current)
        plan.afterVerification.assert().isEqualTo(current)
    }

    @Test
    fun `pending create and update should resume verification without rewriting intent`() {
        listOf(BiRegistryEntryStatus.PENDING_CREATE, BiRegistryEntryStatus.PENDING_UPDATE)
            .forEach { status ->
                val current = registryWith(desiredView, status)

                val plan = BiOwnershipRegistryPlan.create(
                    descriptor = descriptor,
                    consumerIdentity = consumerIdentity,
                    desiredObjects = listOf(desiredView),
                    current = current,
                )

                plan.intentChanged.assert().isFalse()
                plan.verificationChanged.assert().isTrue()
                plan.beforeMutation.assert().isEqualTo(current)
                plan.afterVerification.entries.single().status.assert()
                    .isEqualTo(BiRegistryEntryStatus.ACTIVE)
            }
    }

    @Test
    fun `undesired computed object should complete drop through a tombstone`() {
        val current = activeRegistry(desiredView)

        val plan = BiOwnershipRegistryPlan.create(
            descriptor = descriptor,
            consumerIdentity = consumerIdentity,
            desiredObjects = emptyList(),
            current = current,
        )

        plan.beforeMutation.entries.single().status.assert()
            .isEqualTo(BiRegistryEntryStatus.PENDING_DROP)
        plan.afterVerification.entries.single().status.assert()
            .isEqualTo(BiRegistryEntryStatus.TOMBSTONE)
    }

    @Test
    fun `desired object should reject an interrupted pending drop`() {
        val current = registryWith(desiredView, BiRegistryEntryStatus.PENDING_DROP)

        assertThrows<IllegalStateException> {
            BiOwnershipRegistryPlan.create(
                descriptor = descriptor,
                consumerIdentity = consumerIdentity,
                desiredObjects = listOf(desiredView),
                current = current,
            )
        }.message.assert().contains("is pending drop")
    }

    @Test
    fun `undesired object should reject unverified create or update intent`() {
        listOf(BiRegistryEntryStatus.PENDING_CREATE, BiRegistryEntryStatus.PENDING_UPDATE)
            .forEach { status ->
                val current = registryWith(desiredView, status)

                assertThrows<IllegalStateException> {
                    BiOwnershipRegistryPlan.create(
                        descriptor = descriptor,
                        consumerIdentity = consumerIdentity,
                        desiredObjects = emptyList(),
                        current = current,
                    )
                }.message.assert().contains("is still pending")
            }
    }

    @Test
    fun `registry from another deployment should be rejected`() {
        val foreign = BiOwnershipRegistry.empty("ffffffffffffffffffffffffffffffff")

        assertThrows<IllegalArgumentException> {
            BiOwnershipRegistryPlan.create(
                descriptor = descriptor,
                consumerIdentity = consumerIdentity,
                desiredObjects = emptyList(),
                current = foreign,
            )
        }.message.assert().contains("different deployment")
    }

    private fun activeRegistry(desired: DesiredBiObject): BiOwnershipRegistry {
        val initialPlan = BiOwnershipRegistryPlan.create(
            descriptor = descriptor,
            consumerIdentity = consumerIdentity,
            desiredObjects = listOf(desired),
            current = null,
        )
        return initialPlan.afterVerification
    }

    private fun registryWith(
        desired: DesiredBiObject,
        status: BiRegistryEntryStatus,
    ): BiOwnershipRegistry {
        val registration = desired.toRegistration(consumerIdentity)
        val entry = BiOwnershipRegistryEntry(
            key = registration.key,
            kind = registration.kind,
            aggregate = registration.aggregate,
            consumerIdentity = registration.consumerIdentity,
            definitionFingerprint = registration.definitionFingerprint,
            revision = 1,
            status = status,
        )
        return BiOwnershipRegistry.restore(descriptor.deploymentId, revision = 1, entries = listOf(entry))
    }

    private val desiredView = DesiredBiObject(
        key = BiObjectKey(options.database, "order_state"),
        aggregate = "order",
        kind = BiObjectKind.VIEW,
        expectedEngine = "View",
        expectedQuery = ExpectedBiQuery("SELECT * FROM order_state_store FINAL"),
    )
}
