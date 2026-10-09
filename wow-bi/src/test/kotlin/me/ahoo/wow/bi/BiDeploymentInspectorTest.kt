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
import reactor.core.publisher.Mono

class BiDeploymentInspectorTest {
    @Test
    fun `no-op inspector should report unavailable instead of an empty deployment`() {
        val options = BiScriptOptions()
        val preparation = BiScriptGenerator(options).prepare(emptySet())
        NoOpBiDeploymentInspector.inspect(options, BiScriptOperation.Deploy, preparation).block().assert()
            .isEqualTo(BiDeploymentInspection.Unavailable)
        NoOpBiDeploymentInspector.allowsDynamicScope.assert().isTrue()

        val inspectedOperations = mutableListOf<BiScriptOperation>()
        val authoritative = BiDeploymentInspector { _, operation, _ ->
            inspectedOperations += operation
            Mono.just(BiDeploymentInspection.Available(ObservedBiDeployment(emptyList())))
        }
        authoritative.allowsDynamicScope.assert().isFalse()
        authoritative.inspect(options, BiScriptOperation.Deploy, preparation).block()
        authoritative.inspect(options, BiScriptOperation.Reset(true), preparation).block()
        inspectedOperations.assert().containsExactly(
            BiScriptOperation.Deploy,
            BiScriptOperation.Reset(true),
        )
    }

    @Test
    fun `object metadata codec should distinguish owned catalog objects`() {
        val queue =
            BiObjectMetadata(deploymentId = "a".repeat(32), kind = BiObjectKind.QUEUE, aggregate = "example.order")
        val anchor = BiObjectMetadata(
            deploymentId = "a".repeat(32),
            kind = BiObjectKind.ANCHOR,
            anchor = BiAnchorState(
                phase = BiDeploymentPhase.STABLE,
                configurationFingerprint = "b".repeat(32),
                topologyFingerprint = "c".repeat(32),
                consumerIdentity = "d".repeat(32),
                durableInventory = listOf(
                    BiDurableEntry(BiObjectKey("bi_db", "example_order_state_store"), BiDurableStatus.RETIRED),
                ),
            ),
        )

        listOf(queue, anchor).forEach { metadata ->
            val encoded = BiObjectMetadataCodec.encode(metadata)
            encoded.assert().startsWith("wow-bi:")
            BiObjectMetadataCodec.decode(encoded).assert().isEqualTo(metadata)
        }
        BiObjectMetadataCodec.decode("user-owned").assert().isNull()
    }

    @Test
    fun `object metadata codec should keep the ownership of another layout readable for reset`() {
        val legacy = "wow-bi:{\"protocolVersion\":3,\"layoutVersion\":7,\"phase\":\"STABLE\"," +
            "\"deploymentId\":\"${"a".repeat(32)}\",\"configurationFingerprint\":\"${"b".repeat(32)}\"," +
            "\"topologyFingerprint\":\"${"c".repeat(32)}\",\"kind\":\"ANCHOR\",\"registryRevision\":3}"

        val metadata = requireNotNull(BiObjectMetadataCodec.decode(legacy))

        metadata.isCurrentLayout.assert().isFalse()
        metadata.deploymentId.assert().isEqualTo("a".repeat(32))
        metadata.kind.assert().isEqualTo(BiObjectKind.ANCHOR)
        metadata.anchor.assert().isNull()
    }

    @Test
    fun `observed deployment should reject duplicate catalog keys`() {
        val duplicate = ObservedBiObject(
            database = "bi_db_consumer",
            name = "__wow_bi_deployment",
            engine = "View",
        )

        assertThrows<IllegalArgumentException> {
            ObservedBiDeployment(listOf(duplicate, duplicate.copy(engineFull = "View")))
        }.message.assert().contains(
            "duplicate catalog object",
            "bi_db_consumer.__wow_bi_deployment",
        )
    }

    @Test
    fun `consumer identity should remain a validated opaque value`() {
        BiConsumerIdentity("0123456789abcdef0123456789abcdef").value.assert()
            .isEqualTo("0123456789abcdef0123456789abcdef")
        runCatching { BiConsumerIdentity("not-an-identity") }
            .exceptionOrNull()
            .assert()
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `deployment descriptor should distinguish physical ownership scopes and topology`() {
        val options = BiScriptOptions(
            database = "bi_db",
            consumerDatabase = "bi_db_consumer",
            consumerGroupNamespace = "orders-blue",
            topology = ClickHouseTopology.Cluster(name = "cluster-a", installation = "installation-a"),
        )
        val descriptor = BiDeploymentDescriptor.from(options)

        BiDeploymentDescriptor.from(options.copy(database = "other_db")).deploymentId.assert()
            .isNotEqualTo(descriptor.deploymentId)
        BiDeploymentDescriptor.from(options.copy(consumerDatabase = "other_consumer_db")).deploymentId.assert()
            .isNotEqualTo(descriptor.deploymentId)
        BiDeploymentDescriptor.from(options.copy(consumerGroupNamespace = "orders-green")).deploymentId.assert()
            .isNotEqualTo(descriptor.deploymentId)

        val changedTopology = BiDeploymentDescriptor.from(
            options.copy(topology = ClickHouseTopology.Cluster(name = "cluster-b", installation = "installation-a"))
        )
        changedTopology.deploymentId.assert().isEqualTo(descriptor.deploymentId)
        changedTopology.topologyFingerprint.assert().isNotEqualTo(descriptor.topologyFingerprint)
    }

    @Test
    fun `object metadata should reject every incompatible ownership marker`() {
        val valid =
            BiObjectMetadata(deploymentId = "a".repeat(32), kind = BiObjectKind.QUEUE, aggregate = "example.order")
        val state = BiAnchorState(
            phase = BiDeploymentPhase.STABLE,
            configurationFingerprint = "b".repeat(32),
            topologyFingerprint = "c".repeat(32),
            consumerIdentity = "d".repeat(32),
        )
        val entry = BiDurableEntry(BiObjectKey("bi_db", "example_order_state_store"), BiDurableStatus.ACTIVE)
        val invalid: List<Pair<() -> Any, String>> = listOf(
            { valid.copy(deploymentId = "invalid") } to "Invalid BI deploymentId",
            { valid.copy(aggregate = null) } to "requires an aggregate owner",
            { valid.copy(anchor = state) } to "only valid on the deployment anchor",
            { valid.copy(kind = BiObjectKind.ANCHOR, aggregate = null) } to "requires its anchor state",
            { state.copy(configurationFingerprint = "invalid") } to "Invalid BI configurationFingerprint",
            { state.copy(topologyFingerprint = "invalid") } to "Invalid BI topologyFingerprint",
            { state.copy(consumerIdentity = "invalid") } to "Invalid BI consumer identity",
            { state.copy(durableInventory = listOf(entry, entry.copy(status = BiDurableStatus.RETIRED))) } to
                "duplicate objects",
        )

        invalid.forEach { (create, expectedMessage) ->
            assertThrows<IllegalArgumentException> { create() }.message.assert().contains(expectedMessage)
        }
        valid.copy(kind = BiObjectKind.ANCHOR, aggregate = null, layoutVersion = 7).anchor.assert().isNull()
    }

    @Test
    fun `inspection exceptions should expose stable default error contracts`() {
        val inconsistent = BiDeploymentInspectionException.Inconsistent("inconsistent")
        val unavailable = BiDeploymentInspectionException.Unavailable()
        val timeout = BiDeploymentInspectionException.Timeout()

        inconsistent.errorInfo.errorCode.assert()
            .isEqualTo(BiDeploymentInspectionException.INCONSISTENT_ERROR_CODE)
        unavailable.message.assert().isEqualTo("BI deployment inspection is unavailable")
        unavailable.errorInfo.errorCode.assert()
            .isEqualTo(BiDeploymentInspectionException.UNAVAILABLE_ERROR_CODE)
        timeout.message.assert().isEqualTo("BI deployment inspection timed out")
        timeout.errorInfo.errorCode.assert()
            .isEqualTo(BiDeploymentInspectionException.TIMEOUT_ERROR_CODE)
    }
}
