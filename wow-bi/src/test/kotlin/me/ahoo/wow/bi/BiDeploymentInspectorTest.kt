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
        NoOpBiDeploymentInspector.inspect(BiScriptOptions()).block().assert()
            .isEqualTo(BiDeploymentInspection.Unavailable)
        NoOpBiDeploymentInspector.allowsDynamicScope.assert().isTrue()

        val authoritative = BiDeploymentInspector {
            Mono.just(BiDeploymentInspection.Available(ObservedBiDeployment(emptyList())))
        }
        authoritative.allowsDynamicScope.assert().isFalse()
    }

    @Test
    fun `object metadata codec should distinguish owned catalog objects`() {
        val metadata = BiObjectMetadata(
            deploymentId = "a".repeat(32),
            configurationFingerprint = "b".repeat(32),
            aggregate = "example.order",
            kind = BiObjectKind.QUEUE,
        )

        val encoded = BiObjectMetadataCodec.encode(metadata)

        encoded.assert().startsWith("wow-bi:")
        BiObjectMetadataCodec.decode(encoded).assert().isEqualTo(metadata)
        BiObjectMetadataCodec.decode("user-owned").assert().isNull()
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
    fun `object metadata should reject every incompatible ownership marker`() {
        val valid = BiObjectMetadata(
            deploymentId = "a".repeat(32),
            configurationFingerprint = "b".repeat(32),
            aggregate = "example.order",
            kind = BiObjectKind.QUEUE,
            consumerIdentity = "c".repeat(32),
        )
        val invalidMetadata: List<Pair<() -> Any, String>> = listOf(
            { valid.copy(protocolVersion = valid.protocolVersion + 1) } to
                "Unsupported BI object metadata protocol version",
            { valid.copy(layoutVersion = valid.layoutVersion + 1) } to
                "Unsupported BI object metadata layout version",
            { valid.copy(deploymentId = "invalid") } to "Invalid BI deploymentId",
            { valid.copy(configurationFingerprint = "invalid") } to
                "Invalid BI configurationFingerprint",
            { valid.copy(consumerIdentity = "invalid") } to "Invalid BI consumer identity",
            { valid.copy(aggregate = null) } to "requires an aggregate owner",
        )

        invalidMetadata.forEach { (createMetadata, expectedMessage) ->
            assertThrows<IllegalArgumentException> {
                createMetadata()
            }.message.assert().contains(expectedMessage)
        }

        valid.copy(kind = BiObjectKind.ANCHOR, aggregate = null).aggregate.assert().isNull()
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
