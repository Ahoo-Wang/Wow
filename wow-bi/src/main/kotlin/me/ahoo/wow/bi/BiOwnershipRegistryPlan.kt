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

internal data class BiOwnershipRegistryPlan(
    val beforeMutation: BiOwnershipRegistry,
    val afterVerification: BiOwnershipRegistry,
    val bootstrap: Boolean,
    val intentChanged: Boolean,
) {
    val verificationChanged: Boolean
        get() = beforeMutation.revision != afterVerification.revision

    companion object {
        fun create(
            descriptor: BiDeploymentDescriptor,
            consumerIdentity: BiConsumerIdentity,
            desiredObjects: List<DesiredBiObject>,
            current: BiOwnershipRegistry?,
        ): BiOwnershipRegistryPlan {
            var before = current ?: BiOwnershipRegistry.empty(descriptor.deploymentId)
            require(before.deploymentId == descriptor.deploymentId) {
                "BI ownership registry belongs to a different deployment"
            }
            val registryObjects = desiredObjects.filter { desired -> desired.kind != BiObjectKind.ANCHOR }
            val desiredByKey = registryObjects.associateBy(DesiredBiObject::key)
            before = before.reconcileDesired(registryObjects, consumerIdentity)
            before = before.reconcileUndesired(desiredByKey.keys)
            val after = before.verifyPending(desiredByKey.keys)
            return BiOwnershipRegistryPlan(
                beforeMutation = before,
                afterVerification = after,
                bootstrap = current == null,
                intentChanged = current != null && current.revision != before.revision ||
                    current == null && before.revision > 0,
            )
        }
    }
}

internal fun BiOwnershipRegistryEntry.requireSameContract(registration: BiOwnershipRegistration) {
    check(
        kind == registration.kind &&
            aggregate == registration.aggregate &&
            consumerIdentity == registration.consumerIdentity &&
            definitionFingerprint == registration.definitionFingerprint
    ) {
        "BI object [${key.database}.${key.name}] registration contract differs from the desired contract"
    }
}

private fun BiOwnershipRegistryEntry.requireUpdatableComputedContract(
    registration: BiOwnershipRegistration
) {
    check(
        kind in COMPUTED_OBJECT_KINDS &&
            kind == registration.kind &&
            aggregate == registration.aggregate &&
            consumerIdentity == registration.consumerIdentity
    ) {
        "BI object [${key.database}.${key.name}] registration contract differs from the desired contract"
    }
}

internal fun DesiredBiObject.toRegistration(consumerIdentity: BiConsumerIdentity): BiOwnershipRegistration =
    BiOwnershipRegistration(
        key = key,
        kind = kind,
        aggregate = aggregate,
        consumerIdentity = consumerIdentity.value,
        definitionFingerprint = ownershipDefinitionFingerprint(),
    )

private fun DesiredBiObject.ownershipDefinitionFingerprint(): String =
    biOwnershipDefinitionFingerprint(
        BiOwnershipDefinition(
            key = key,
            kind = kind,
            aggregate = aggregate,
            expectedEngine = expectedEngine,
            expectedQuery = expectedQuery,
        )
    )

internal data class BiOwnershipDefinition(
    val key: BiObjectKey,
    val kind: BiObjectKind,
    val aggregate: String?,
    val expectedEngine: String,
    val expectedQuery: ExpectedBiQuery?,
)

internal fun biOwnershipDefinitionFingerprint(definition: BiOwnershipDefinition): String = with(definition) {
    biOwnershipDigest(
        listOf(
            key.database,
            key.name,
            kind.name,
            aggregate.orEmpty(),
            expectedEngine,
            expectedQuery?.selectSql.orEmpty(),
            expectedQuery?.target?.database.orEmpty(),
            expectedQuery?.target?.name.orEmpty(),
        ).joinToString("\u0000")
    )
}

private fun BiOwnershipRegistry.reconcileDesired(
    desiredObjects: List<DesiredBiObject>,
    consumerIdentity: BiConsumerIdentity,
): BiOwnershipRegistry {
    var result = this
    desiredObjects.sortedDesiredObjects().forEach { desired ->
        val existing = result.entries.firstOrNull { entry -> entry.key == desired.key }
        val registration = desired.toRegistration(consumerIdentity)
        result = when (existing?.status) {
            null,
            BiRegistryEntryStatus.TOMBSTONE,
            -> result.beginCreate(registration)

            BiRegistryEntryStatus.PENDING_CREATE,
            -> result.also { existing.requireSameContract(registration) }

            BiRegistryEntryStatus.PENDING_UPDATE ->
                result.also { existing.requireSameContract(registration) }

            BiRegistryEntryStatus.ACTIVE ->
                if (runCatching { existing.requireSameContract(registration) }.isSuccess) {
                    result
                } else {
                    existing.requireUpdatableComputedContract(registration)
                    result.beginUpdate(registration)
                }

            BiRegistryEntryStatus.RETIRED -> result.beginCreate(registration)
            BiRegistryEntryStatus.PENDING_DROP ->
                error("Desired BI object [${desired.key.database}.${desired.key.name}] is pending drop")
        }
    }
    return result
}

private fun BiOwnershipRegistry.reconcileUndesired(desiredKeys: Set<BiObjectKey>): BiOwnershipRegistry {
    var result = this
    entries.filter { entry -> entry.key !in desiredKeys }.sortedRegistryEntries().forEach { entry ->
        result = when (entry.status) {
            BiRegistryEntryStatus.ACTIVE ->
                if (entry.kind == BiObjectKind.STORE) result.retire(entry.key) else result.beginDrop(entry.key)

            BiRegistryEntryStatus.RETIRED,
            BiRegistryEntryStatus.PENDING_DROP,
            BiRegistryEntryStatus.TOMBSTONE,
            -> result

            BiRegistryEntryStatus.PENDING_UPDATE ->
                error(
                    "Undesired BI object [${entry.key.database}.${entry.key.name}] is still pending update"
                )

            BiRegistryEntryStatus.PENDING_CREATE ->
                error(
                    "Undesired BI object [${entry.key.database}.${entry.key.name}] is still pending create"
                )
        }
    }
    return result
}

private fun BiOwnershipRegistry.verifyPending(desiredKeys: Set<BiObjectKey>): BiOwnershipRegistry {
    var result = this
    entries.sortedRegistryEntries().forEach { entry ->
        result = when {
            entry.status == BiRegistryEntryStatus.PENDING_CREATE && entry.key in desiredKeys ->
                result.markMutationVerified(entry.key)

            entry.status == BiRegistryEntryStatus.PENDING_UPDATE && entry.key in desiredKeys ->
                result.markMutationVerified(entry.key)

            entry.status == BiRegistryEntryStatus.PENDING_DROP && entry.key !in desiredKeys ->
                result.markAbsenceVerified(entry.key)

            else -> result
        }
    }
    return result
}

private fun List<DesiredBiObject>.sortedDesiredObjects(): List<DesiredBiObject> =
    sortedWith(compareBy({ it.key.database }, { it.key.name }))

private fun List<BiOwnershipRegistryEntry>.sortedRegistryEntries(): List<BiOwnershipRegistryEntry> =
    sortedWith(compareBy({ it.key.database }, { it.key.name }))

private val COMPUTED_OBJECT_KINDS: Set<BiObjectKind> =
    setOf(BiObjectKind.VIEW, BiObjectKind.CONSUMER)
