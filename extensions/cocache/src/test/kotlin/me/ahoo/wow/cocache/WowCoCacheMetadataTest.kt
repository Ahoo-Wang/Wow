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

package me.ahoo.wow.cocache

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
 * wow-cocache's own keys (design rules K1, K8, decision D302): their metadata, generated from [WowCoCacheProperties]
 * and shipped in the module, so a host accepts `wow.cocache.*` with the module on the class path and refuses it,
 * naming the module, without it. `WOW_UPDATE_SNAPSHOTS=1` rewrites the metadata.
 */
class WowCoCacheMetadataTest {
    private val metadata = ConfigurationMetadata(
        listOf(WowCoCacheProperties::class.java to WowCoCacheProperties.PREFIX)
    )
    private val environment = MockEnvironment().withProperty("${WowCoCacheProperties.PUBLISH}[0]", "shop.order")
        .withProperty(WowCoCacheProperties.TTL, "10m")

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
        refused.message.assert().contains("wow.cocache.publish")
    }

    /** A class loader without wow-cocache's configuration metadata, as a host without the module has none. */
    private class WithoutModule(parent: ClassLoader) : ClassLoader(parent) {
        override fun getResources(name: String): Enumeration<URL> =
            Collections.enumeration(super.getResources(name).toList().filterNot { "/cocache/" in it.path })
    }
}
