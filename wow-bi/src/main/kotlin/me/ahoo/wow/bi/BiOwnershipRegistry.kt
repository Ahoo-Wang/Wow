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

import java.security.MessageDigest
import java.util.Collections

internal enum class BiRegistryEntryStatus {
    PENDING_CREATE,
    PENDING_UPDATE,
    ACTIVE,
    PENDING_DROP,
    RETIRED,
    TOMBSTONE,
}

internal data class BiOwnershipRegistration(
    val key: BiObjectKey,
    val kind: BiObjectKind,
    val aggregate: String?,
    val consumerIdentity: String?,
    val definitionFingerprint: String,
)

internal data class BiOwnershipRegistryEntry(
    val key: BiObjectKey,
    val kind: BiObjectKind,
    val aggregate: String?,
    val consumerIdentity: String?,
    val definitionFingerprint: String,
    val revision: Long,
    val status: BiRegistryEntryStatus,
) {
    init {
        require(revision > 0) { "registry entry revision must be positive" }
        definitionFingerprint.requireDigest("definitionFingerprint")
        consumerIdentity?.let(::BiConsumerIdentity)
        require(kind == BiObjectKind.ANCHOR || aggregate != null) {
            "BI registry entry [$kind] requires an aggregate owner"
        }
    }
}

/**
 * Immutable current-layout ownership state.
 *
 * Persisting a returned revision before executing its DDL provides the write-ahead side of create/update/drop recovery.
 */
internal class BiOwnershipRegistry private constructor(
    val deploymentId: String,
    val revision: Long,
    entries: List<BiOwnershipRegistryEntry>,
) {
    val entries: List<BiOwnershipRegistryEntry> =
        Collections.unmodifiableList(ArrayList(entries))

    init {
        deploymentId.requireDigest("deploymentId")
        require(revision >= 0) { "registry revision must not be negative" }
        require(entries.map(BiOwnershipRegistryEntry::key).distinct().size == entries.size) {
            "BI ownership registry contains duplicate object keys"
        }
        require(entries.all { it.revision <= revision }) {
            "BI ownership registry entry revision is ahead of the registry"
        }
    }

    val name: String
        get() = "__wow_bi_registry_$deploymentId"

    fun beginCreate(registration: BiOwnershipRegistration): BiOwnershipRegistry = with(registration) {
        require(
            entries.none {
                it.key == key &&
                    it.status != BiRegistryEntryStatus.TOMBSTONE &&
                    it.status != BiRegistryEntryStatus.RETIRED
            }
        ) {
            "BI registry object [${key.database}.${key.name}] is already registered"
        }
        replaceEntry(toRegistryEntry(revision + 1, BiRegistryEntryStatus.PENDING_CREATE))
    }

    fun beginUpdate(registration: BiOwnershipRegistration): BiOwnershipRegistry = with(registration) {
        val current = requireEntry(key)
        require(current.status == BiRegistryEntryStatus.ACTIVE) {
            "BI registry update requires ACTIVE, but was ${current.status}"
        }
        replaceEntry(toRegistryEntry(revision + 1, BiRegistryEntryStatus.PENDING_UPDATE))
    }

    fun markMutationVerified(key: BiObjectKey): BiOwnershipRegistry {
        val current = requireEntry(key)
        require(
            current.status == BiRegistryEntryStatus.PENDING_CREATE ||
                current.status == BiRegistryEntryStatus.PENDING_UPDATE
        ) {
            "BI registry mutation verification requires PENDING_CREATE or PENDING_UPDATE, but was ${current.status}"
        }
        return transition(key, current.status, BiRegistryEntryStatus.ACTIVE)
    }

    fun retire(key: BiObjectKey): BiOwnershipRegistry =
        transition(key, BiRegistryEntryStatus.ACTIVE, BiRegistryEntryStatus.RETIRED)

    fun beginDrop(key: BiObjectKey): BiOwnershipRegistry {
        val current = requireEntry(key)
        require(current.status in setOf(BiRegistryEntryStatus.ACTIVE, BiRegistryEntryStatus.RETIRED)) {
            "BI registry drop requires ACTIVE or RETIRED, but was ${current.status}"
        }
        return replaceEntry(current.copy(revision = revision + 1, status = BiRegistryEntryStatus.PENDING_DROP))
    }

    fun markAbsenceVerified(key: BiObjectKey): BiOwnershipRegistry =
        transition(key, BiRegistryEntryStatus.PENDING_DROP, BiRegistryEntryStatus.TOMBSTONE)

    private fun transition(
        key: BiObjectKey,
        expected: BiRegistryEntryStatus,
        next: BiRegistryEntryStatus,
    ): BiOwnershipRegistry {
        val current = requireEntry(key)
        require(current.status == expected) {
            "BI registry transition requires $expected, but was ${current.status}"
        }
        return replaceEntry(current.copy(revision = revision + 1, status = next))
    }

    private fun requireEntry(key: BiObjectKey): BiOwnershipRegistryEntry =
        requireNotNull(entries.firstOrNull { it.key == key }) {
            "BI registry object [${key.database}.${key.name}] is not registered"
        }

    private fun replaceEntry(entry: BiOwnershipRegistryEntry): BiOwnershipRegistry {
        val updated = entries.filterNot { it.key == entry.key } + entry
        return BiOwnershipRegistry(
            deploymentId = deploymentId,
            revision = entry.revision,
            entries = updated.sortedWith(compareBy({ it.key.database }, { it.key.name })),
        )
    }

    companion object {
        fun empty(deploymentId: String): BiOwnershipRegistry =
            BiOwnershipRegistry(deploymentId, revision = 0, entries = emptyList())

        fun restore(
            deploymentId: String,
            revision: Long,
            entries: List<BiOwnershipRegistryEntry>,
        ): BiOwnershipRegistry = BiOwnershipRegistry(deploymentId, revision, entries)
    }
}

private fun BiOwnershipRegistration.toRegistryEntry(
    revision: Long,
    status: BiRegistryEntryStatus,
): BiOwnershipRegistryEntry = BiOwnershipRegistryEntry(
    key = key,
    kind = kind,
    aggregate = aggregate,
    consumerIdentity = consumerIdentity,
    definitionFingerprint = definitionFingerprint,
    revision = revision,
    status = status,
)

internal fun BiOwnershipRegistry.snapshotFingerprint(): String = biRegistryDigest(
    entries.joinToString("\u0000") { entry ->
        listOf(
            entry.key.database,
            entry.key.name,
            entry.kind.name,
            entry.aggregate.orEmpty(),
            entry.consumerIdentity.orEmpty(),
            entry.definitionFingerprint,
            entry.revision,
            entry.status.name,
        ).joinToString("\u0001")
    }
)

internal fun BiOwnershipRegistryEntry.rowFingerprint(): String = biRegistryDigest(
    listOf(
        key.database,
        key.name,
        kind.name,
        aggregate.orEmpty(),
        consumerIdentity.orEmpty(),
        definitionFingerprint,
        revision,
        status.name,
    ).joinToString("\u0000")
)

private fun biRegistryDigest(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val BI_DIGEST_PATTERN = Regex("[0-9a-f]{32}")

private fun String.requireDigest(name: String) {
    require(BI_DIGEST_PATTERN.matches(this)) { "Invalid BI $name: $this" }
}
