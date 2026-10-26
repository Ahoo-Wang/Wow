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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

/**
 * The host's Spring configuration metadata, `META-INF/spring-configuration-metadata.json`, generated
 * from its properties classes and their KDoc ([ConfigurationMetadata]): this test fails when the committed file is not
 * what the classes give, and `WOW_UPDATE_SNAPSHOTS=1` rewrites it.
 */
class ConfigurationMetadataTest {
    private val metadata = ConfigurationMetadata(ROOTS)

    @Test
    fun `the committed metadata should be what the properties classes give`() {
        val (committed, expected) = metadata.committed()
        committed.assert()
            .describedAs("Run with ${ConfigurationMetadata.UPDATE}=1 to rewrite ${ConfigurationMetadata.FILE}.")
            .isEqualTo(expected)
    }

    @Test
    fun `the keys the modules read from the environment should be in the metadata`() {
        val names = WowJson.mapper.readTree(metadata.document())["properties"].mapNotNull { it["name"]?.asString() }
        names.assert().contains(
            "wow.cosec.authorizer.enabled",
            "wow.cosec.cross-scope.roles",
            "wow.cosec.tenant-level.policies",
            "wow.cosec.trust-injected-app",
            "wow.tenant.default",
            "wow.executor.max-in-flight",
        )
    }

    @Test
    fun `every key should have a plain description`() {
        metadata.undocumented().assert().describedAs("Write a KDoc on the property of each of these keys.").isEmpty()
        metadata.withIds().assert()
            .describedAs("Users cannot look up decision or review IDs: explain them in plain words instead.")
            .isEmpty()
    }

    private companion object {
        val ROOTS: List<Pair<Class<*>, String>> = listOf(
            WowProperties::class.java to WowProperties.PREFIX,
            QueryProperties::class.java to QueryProperties.PREFIX,
            KafkaProperties::class.java to KafkaProperties.PREFIX,
            ClickHouseProperties::class.java to ClickHouseProperties.PREFIX,
            ObservabilityProperties::class.java to ObservabilityProperties.PREFIX,
        )
    }
}
