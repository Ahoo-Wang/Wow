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

package me.ahoo.wow.cosid

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.ConfigurationKeys
import me.ahoo.wow.spring.boot.ConfigurationMetadata
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment
import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * wow-cosid's own keys (design rules K1, K8, decision D302): their metadata, generated from [CosIdProperties] and
 * shipped in the module, so a host accepts `wow.cosid.*` with the module on the class path and refuses it, naming the
 * module, without it. `WOW_UPDATE_SNAPSHOTS=1` rewrites the metadata.
 */
class CosIdMetadataTest {
    private val metadata = ConfigurationMetadata(listOf(CosIdProperties::class.java to CosIdProperties.PREFIX))
    private val environment = MockEnvironment().withProperty(CosIdProperties.AGGREGATE_GENERATOR, "snowflake")

    @Test
    fun `the committed metadata should be what the properties class gives`() {
        val (committed, expected) = metadata.committed()
        committed.assert()
            .describedAs("Run with ${ConfigurationMetadata.UPDATE}=1 to rewrite ${ConfigurationMetadata.FILE}.")
            .isEqualTo(expected)
        metadata.undocumented().assert().isEmpty()
        metadata.withIds().assert().isEmpty()
    }

    @Test
    fun `a host should accept the module's keys with the module, and refuse them without it`() {
        ConfigurationKeys.of(javaClass.classLoader).check(environment)
        val refused = assertThrows<WowConfigurationException> {
            ConfigurationKeys.of(WithoutModule(javaClass.classLoader)).check(environment)
        }
        refused.message.assert().contains("wow.cosid.aggregate-generator (needs wow-cosid on the class path)")
    }

    @Test
    fun `the former key of the global generator should be refused as unknown`() {
        // The node's own ids are never CosId's: wow.cosid.global-generator is gone, with no alias (D306).
        val former = MockEnvironment().withProperty("wow.cosid.global-generator", "default")
        assertThrows<WowConfigurationException> { ConfigurationKeys.of(javaClass.classLoader).check(former) }
            .message.assert().contains("wow.cosid.global-generator")
    }

    @Test
    fun `the aggregate generator should be the key, else the properties' default`() {
        WowCosIdAutoConfiguration.aggregateGeneratorOf(environment).assert().isEqualTo("snowflake")
        WowCosIdAutoConfiguration.aggregateGeneratorOf(MockEnvironment()).assert()
            .isEqualTo(CosIdProperties().aggregateGenerator)
    }

    /** A class loader without wow-cosid's configuration metadata, as a host without the module has none. */
    private class WithoutModule(parent: ClassLoader) : ClassLoader(parent) {
        override fun getResources(name: String): Enumeration<URL> =
            Collections.enumeration(super.getResources(name).toList().filterNot { "/cosid/" in it.path })
    }
}
