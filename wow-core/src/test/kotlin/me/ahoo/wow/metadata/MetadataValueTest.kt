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

package me.ahoo.wow.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.proxy.PrepareKeyMetadata
import org.junit.jupiter.api.Test

class MetadataValueTest {

    @Test
    fun `should expose materialized production metadata value`() {
        val metadata: Metadata = PrepareKeyMetadata(
            name = "foundation",
            proxyInterface = MetadataPrepareKey::class,
            valueType = MetadataPreparedValue::class
        )

        val prepareKeyMetadata = metadata as PrepareKeyMetadata<*>
        prepareKeyMetadata.name.assert().isEqualTo("foundation")
        prepareKeyMetadata.proxyInterface.assert().isEqualTo(MetadataPrepareKey::class)
        prepareKeyMetadata.valueType.assert().isEqualTo(MetadataPreparedValue::class)
    }
}

private interface MetadataPrepareKey : PrepareKey<MetadataPreparedValue>

private data class MetadataPreparedValue(val id: String)
