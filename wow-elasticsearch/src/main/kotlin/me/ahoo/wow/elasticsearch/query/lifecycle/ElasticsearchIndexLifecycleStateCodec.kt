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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.elasticsearch.query.lifecycle

import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.gateway.QueryTarget
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap

/** Explicit, bounded wire format for durable lifecycle state. */
internal class ElasticsearchIndexLifecycleStateCodec {
    fun encode(state: ElasticsearchIndexMigrationState): ByteArray = try {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeState(state)
        }
        buffer.toByteArray().also { encoded ->
            if (encoded.size > MAX_PAYLOAD_BYTES) {
                corrupt(state.manifest.id, "Encoded Elasticsearch lifecycle state exceeds the payload limit.")
            }
        }
    } catch (error: ElasticsearchIndexLifecycleException) {
        throw error
    } catch (error: IllegalArgumentException) {
        corrupt(state.manifest.id, "Failed to encode Elasticsearch lifecycle state.", error)
    } catch (error: IOException) {
        corrupt(state.manifest.id, "Failed to encode Elasticsearch lifecycle state.", error)
    }

    fun decode(
        expectedId: ElasticsearchIndexMigrationId,
        encoded: ByteArray,
    ): ElasticsearchIndexMigrationState {
        if (encoded.size > MAX_PAYLOAD_BYTES) {
            corrupt(expectedId, "Persisted Elasticsearch lifecycle state exceeds the payload limit.")
        }
        return try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                    corrupt(expectedId, "Persisted Elasticsearch lifecycle state has an unsupported format.")
                }
                val state = input.readState()
                if (state.manifest.id != expectedId) {
                    corrupt(expectedId, "Persisted Elasticsearch lifecycle state belongs to another migration.")
                }
                if (input.available() != 0) {
                    corrupt(expectedId, "Persisted Elasticsearch lifecycle state contains trailing data.")
                }
                state
            }
        } catch (error: ElasticsearchIndexLifecycleException) {
            throw error
        } catch (error: EOFException) {
            corrupt(expectedId, "Persisted Elasticsearch lifecycle state is truncated.", error)
        } catch (error: IllegalArgumentException) {
            corrupt(expectedId, "Persisted Elasticsearch lifecycle state is invalid.", error)
        } catch (error: IOException) {
            corrupt(expectedId, "Failed to decode persisted Elasticsearch lifecycle state.", error)
        }
    }

    private fun DataOutputStream.writeState(state: ElasticsearchIndexMigrationState) {
        writeManifest(state.manifest)
        writeEnum(state.phase)
        writeLong(state.revision)
        writeNullable(state.activeCommand) { writeActiveCommand(it) }
        writeNullable(state.lastCompletedCommand) { writeCompletedCommand(it) }
        writeNullable(state.inventory) { writeInventory(it) }
        writeNullable(state.destinationAttestation) { writeAttestation(it) }
        writeNullable(state.rebuildReceipt) { writeRebuildReceipt(it) }
        writeNullable(state.destinationVerification) { writeVerification(it) }
        writeNullable(state.cutover) { writeAliasTransition(it) }
        writeNullable(state.rollbackVerification) { writeVerification(it) }
        writeNullable(state.rollback) { writeAliasTransition(it) }
        writeNullable(state.retainedSourceUntil) { writeInstantValue(it) }
    }

    private fun DataInputStream.readState(): ElasticsearchIndexMigrationState = ElasticsearchIndexMigrationState(
        manifest = readManifest(),
        phase = readEnum(),
        revision = readLong(),
        activeCommand = readNullable { readActiveCommand() },
        lastCompletedCommand = readNullable { readCompletedCommand() },
        inventory = readNullable { readInventory() },
        destinationAttestation = readNullable { readAttestation() },
        rebuildReceipt = readNullable { readRebuildReceipt() },
        destinationVerification = readNullable { readVerification() },
        cutover = readNullable { readAliasTransition() },
        rollbackVerification = readNullable { readVerification() },
        rollback = readNullable { readAliasTransition() },
        retainedSourceUntil = readNullable { readInstantValue() },
    )

    private fun DataOutputStream.writeManifest(manifest: ElasticsearchIndexMigrationManifest) {
        writeStringValue(manifest.id.value)
        writeStringValue(manifest.target.namedAggregate.contextName)
        writeStringValue(manifest.target.namedAggregate.aggregateName)
        writeEnum(manifest.target.documentKind)
        writeInt(manifest.mappingVersion.value)
        writeInt(manifest.generation.value)
        writeStringValue(manifest.schemaContractId.value)
        writeStringValue(manifest.capabilityDigest.value)
        writeStringValue(manifest.sourcePhysicalIndex.value)
        writeEnum(manifest.rebuildStrategy)
        writeEnum(manifest.verificationContract.checksumAlgorithm)
        writeStringValue(manifest.verificationContract.probeSuiteId.value)
        writeDuration(manifest.maxCursorTtl)
        writeDuration(manifest.rollbackWindow)
    }

    private fun DataInputStream.readManifest(): ElasticsearchIndexMigrationManifest =
        ElasticsearchIndexMigrationManifest(
            ElasticsearchIndexMigrationId(readStringValue()),
            QueryTarget(
                MaterializedNamedAggregate(readStringValue(), readStringValue()),
                readEnum(),
            ),
            ElasticsearchIndexMappingVersion(readInt()),
            ElasticsearchIndexGeneration(readInt()),
            SchemaContractId(readStringValue()),
            ElasticsearchIndexCapabilityDigest(readStringValue()),
            ElasticsearchPhysicalIndex(readStringValue()),
            readEnum(),
            ElasticsearchIndexVerificationContract(
                readEnum(),
                ElasticsearchIndexProbeSuiteId(readStringValue()),
            ),
            readDuration(),
            readDuration(),
        )

    private fun DataOutputStream.writeActiveCommand(command: ElasticsearchIndexActiveCommand) {
        writeStringValue(command.id.value)
        writeEnum(command.type)
        writeEnum(command.from)
        writeEnum(command.to)
        writeInstantValue(command.startedAt)
    }

    private fun DataInputStream.readActiveCommand() = ElasticsearchIndexActiveCommand(
        ElasticsearchIndexLifecycleCommandId(readStringValue()),
        readEnum(),
        readEnum(),
        readEnum(),
        readInstantValue(),
    )

    private fun DataOutputStream.writeCompletedCommand(command: ElasticsearchIndexCompletedCommand) {
        writeStringValue(command.id.value)
        writeEnum(command.type)
        writeInstantValue(command.completedAt)
    }

    private fun DataInputStream.readCompletedCommand() = ElasticsearchIndexCompletedCommand(
        ElasticsearchIndexLifecycleCommandId(readStringValue()),
        readEnum(),
        readInstantValue(),
    )

    private fun DataOutputStream.writeInventory(inventory: ElasticsearchIndexInventory) {
        writeStringValue(inventory.alias.value)
        writeNullable(inventory.aliasTarget) { value -> writeStringValue(value.value) }
        writeCollectionSize(inventory.indices.size)
        inventory.indices.forEach { (physical, attestation) ->
            writeStringValue(physical.value)
            writeAttestation(attestation)
        }
        writeInstantValue(inventory.observedAt)
    }

    private fun DataInputStream.readInventory(): ElasticsearchIndexInventory {
        val alias = ElasticsearchIndexAlias(readStringValue())
        val aliasTarget = readNullable { ElasticsearchPhysicalIndex(readStringValue()) }
        val count = readCollectionSize()
        val indices = LinkedHashMap<ElasticsearchPhysicalIndex, ElasticsearchIndexAttestation>(count)
        repeat(count) {
            val physical = ElasticsearchPhysicalIndex(readStringValue())
            require(indices.put(physical, readAttestation()) == null) {
                "Persisted Elasticsearch inventory contains a duplicate index."
            }
        }
        return ElasticsearchIndexInventory(alias, aliasTarget, indices, readInstantValue())
    }

    private fun DataOutputStream.writeAttestation(attestation: ElasticsearchIndexAttestation) {
        writeStringValue(attestation.physicalIndex.value)
        writeInt(attestation.mappingVersion.value)
        writeEnum(attestation.documentKind)
        writeStringValue(attestation.schemaContractId.value)
        writeStringValue(attestation.capabilityDigest.value)
    }

    private fun DataInputStream.readAttestation() = ElasticsearchIndexAttestation(
        ElasticsearchPhysicalIndex(readStringValue()),
        ElasticsearchIndexMappingVersion(readInt()),
        readEnum(),
        SchemaContractId(readStringValue()),
        ElasticsearchIndexCapabilityDigest(readStringValue()),
    )

    private fun DataOutputStream.writeRebuildReceipt(receipt: ElasticsearchIndexRebuildReceipt) {
        writeStringValue(receipt.physicalIndex.value)
        writeEnum(receipt.strategy)
        writeNullableLong(receipt.authoritativeWatermark)
        writeNullableLong(receipt.indexedWatermark)
        writeInstantValue(receipt.completedAt)
    }

    private fun DataInputStream.readRebuildReceipt() = ElasticsearchIndexRebuildReceipt(
        ElasticsearchPhysicalIndex(readStringValue()),
        readEnum(),
        readNullableLong(),
        readNullableLong(),
        readInstantValue(),
    )

    private fun DataOutputStream.writeVerification(verification: ElasticsearchIndexVerification) {
        writeStringValue(verification.physicalIndex.value)
        writeLong(verification.expectedCount)
        writeLong(verification.actualCount)
        writeStringValue(verification.expectedIdentityChecksum.value)
        writeStringValue(verification.actualIdentityChecksum.value)
        writeStringValue(verification.expectedContentChecksum.value)
        writeStringValue(verification.actualContentChecksum.value)
        writeBoolean(verification.versionContinuity)
        writeNullableLong(verification.authoritativeWatermark)
        writeNullableLong(verification.indexedWatermark)
        writeLong(verification.recordProbeMismatchCount)
        writeLong(verification.analyticsProbeMismatchCount)
        writeInstantValue(verification.verifiedAt)
    }

    private fun DataInputStream.readVerification() = ElasticsearchIndexVerification(
        ElasticsearchPhysicalIndex(readStringValue()),
        readLong(),
        readLong(),
        ElasticsearchIndexChecksum(readStringValue()),
        ElasticsearchIndexChecksum(readStringValue()),
        ElasticsearchIndexChecksum(readStringValue()),
        ElasticsearchIndexChecksum(readStringValue()),
        readBoolean(),
        readNullableLong(),
        readNullableLong(),
        readLong(),
        readLong(),
        readInstantValue(),
    )

    private fun DataOutputStream.writeAliasTransition(transition: ElasticsearchAliasTransition) {
        writeStringValue(transition.alias.value)
        writeNullable(transition.previous) { value -> writeStringValue(value.value) }
        writeStringValue(transition.current.value)
        writeInstantValue(transition.transitionedAt)
    }

    private fun DataInputStream.readAliasTransition() = ElasticsearchAliasTransition(
        ElasticsearchIndexAlias(readStringValue()),
        readNullable { ElasticsearchPhysicalIndex(readStringValue()) },
        ElasticsearchPhysicalIndex(readStringValue()),
        readInstantValue(),
    )

    private fun DataOutputStream.writeDuration(duration: Duration) {
        writeLong(duration.seconds)
        writeInt(duration.nano)
    }

    private fun DataInputStream.readDuration(): Duration = Duration.ofSeconds(readLong(), readInt().toLong())

    private fun DataOutputStream.writeInstantValue(instant: Instant) {
        writeLong(instant.epochSecond)
        writeInt(instant.nano)
    }

    private fun DataInputStream.readInstantValue(): Instant = Instant.ofEpochSecond(readLong(), readInt().toLong())

    private fun DataOutputStream.writeStringValue(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Persisted Elasticsearch lifecycle string exceeds the limit." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readStringValue(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Persisted Elasticsearch lifecycle string length is invalid." }
        val bytes = ByteArray(length).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataOutputStream.writeCollectionSize(size: Int) {
        require(size in 0..MAX_COLLECTION_SIZE) { "Persisted Elasticsearch lifecycle collection is too large." }
        writeInt(size)
    }

    private fun DataInputStream.readCollectionSize(): Int = readInt().also { size ->
        require(size in 0..MAX_COLLECTION_SIZE) { "Persisted Elasticsearch lifecycle collection size is invalid." }
    }

    private inline fun <T : Any> DataOutputStream.writeNullable(value: T?, writer: DataOutputStream.(T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) writer(value)
    }

    private inline fun <T : Any> DataInputStream.readNullable(reader: DataInputStream.() -> T): T? =
        if (readBoolean()) reader() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) =
        writeNullable(value) { nonNull -> writeLong(nonNull) }

    private fun DataInputStream.readNullableLong(): Long? = readNullable { readLong() }

    private fun DataOutputStream.writeEnum(value: Enum<*>) = writeStringValue(value.name)

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T = enumValueOf(readStringValue())

    internal companion object {
        const val MAX_PAYLOAD_BYTES = 1_048_576
        private const val MAX_STRING_BYTES = 65_536
        private const val MAX_COLLECTION_SIZE = 4_096
        private const val FORMAT_VERSION = 2
        private const val MAGIC = 0x57514C53
    }
}

private fun corrupt(id: ElasticsearchIndexMigrationId, message: String): Nothing =
    throw corrupted(id, message, null)

private fun corrupt(
    id: ElasticsearchIndexMigrationId,
    message: String,
    cause: Throwable,
): Nothing = throw corrupted(id, message, cause)

private fun corrupted(
    id: ElasticsearchIndexMigrationId,
    message: String,
    cause: Throwable?,
) = ElasticsearchIndexLifecycleException(
    ElasticsearchIndexLifecycleErrorCode.REPOSITORY_CORRUPTED,
    id,
    message,
    cause,
)
