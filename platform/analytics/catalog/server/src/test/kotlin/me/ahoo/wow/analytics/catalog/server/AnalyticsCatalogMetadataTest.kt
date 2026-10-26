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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.ConfigurationMetadata
import org.junit.jupiter.api.Test

/**
 * The configuration metadata of the service's own keys, `META-INF/spring-configuration-metadata.json`, generated from
 * its properties class and its KDoc ([ConfigurationMetadata]): so the host knows the keys, refuses any other, and every
 * key has a description. `WOW_UPDATE_SNAPSHOTS=1` rewrites it.
 */
class AnalyticsCatalogMetadataTest {
    private val metadata = ConfigurationMetadata(listOf(AnalyticsCatalogProperties::class.java to AnalyticsCatalogProperties.PREFIX))

    @Test
    fun `the committed metadata should be what the properties class gives`() {
        val (committed, expected) = metadata.committed()
        committed.assert()
            .describedAs("Run with ${ConfigurationMetadata.UPDATE}=1 to rewrite ${ConfigurationMetadata.FILE}.")
            .isEqualTo(expected)
    }

    @Test
    fun `every key should have a plain description`() {
        metadata.undocumented().assert().isEmpty()
        metadata.withIds().assert().isEmpty()
    }
}
