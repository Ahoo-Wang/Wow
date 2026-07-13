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

class BiDeploymentInspectorTest {
    @Test
    fun `no-op inspector should report unavailable instead of an empty deployment`() {
        NoOpBiDeploymentInspector.inspect(BiScriptOptions()).block().assert()
            .isEqualTo(BiDeploymentInspection.Unavailable)
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
}
