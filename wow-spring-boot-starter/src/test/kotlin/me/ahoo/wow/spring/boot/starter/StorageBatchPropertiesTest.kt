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

package me.ahoo.wow.spring.boot.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.batch.BatchOptions
import me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchEventStoreBatchProperties
import me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchSnapshotStoreBatchProperties
import me.ahoo.wow.spring.boot.starter.mongo.MongoEventStoreBatchProperties
import me.ahoo.wow.spring.boot.starter.mongo.MongoSnapshotStoreBatchProperties
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class StorageBatchPropertiesTest {
    private val propertyTypes = listOf(
        MongoEventStoreBatchProperties::class.java,
        MongoSnapshotStoreBatchProperties::class.java,
        ElasticsearchEventStoreBatchProperties::class.java,
        ElasticsearchSnapshotStoreBatchProperties::class.java,
    )

    private val bindings: Map<String, (Binder) -> BatchOptions?> = mapOf(
        MongoEventStoreBatchProperties.PREFIX to { binder ->
            binder.bindOrCreate(MongoEventStoreBatchProperties.PREFIX, MongoEventStoreBatchProperties::class.java).toOptions()
        },
        MongoSnapshotStoreBatchProperties.PREFIX to { binder ->
            binder.bindOrCreate(MongoSnapshotStoreBatchProperties.PREFIX, MongoSnapshotStoreBatchProperties::class.java).toOptions()
        },
        ElasticsearchEventStoreBatchProperties.PREFIX to { binder ->
            binder.bindOrCreate(ElasticsearchEventStoreBatchProperties.PREFIX, ElasticsearchEventStoreBatchProperties::class.java).toOptions()
        },
        ElasticsearchSnapshotStoreBatchProperties.PREFIX to { binder ->
            binder.bindOrCreate(ElasticsearchSnapshotStoreBatchProperties.PREFIX, ElasticsearchSnapshotStoreBatchProperties::class.java).toOptions()
        },
    )

    @Test
    fun `all storage batch properties should use JavaBean binding`() {
        propertyTypes.forEach { propertyType ->
            val constructor = propertyType.getDeclaredConstructor()
            assertTrue(
                constructor.parameterCount == 0,
                "${propertyType.name} must have a no-args constructor",
            )
            assertTrue(
                propertyType.declaredConstructors.none {
                    it.isAnnotationPresent(ConstructorBinding::class.java)
                },
                "${propertyType.name} must not declare @ConstructorBinding",
            )
        }
    }

    @Test
    fun `all stores should disable batching by default and share enabled defaults`() {
        bindings.forEach { (prefix, bind) ->
            bind(Binder(MapConfigurationPropertySource(emptyMap<String, String>()))).assert().isNull()
            bind(Binder(MapConfigurationPropertySource(mapOf("$prefix.enabled" to "true"))))
                .assert().isEqualTo(BatchOptions())
        }
    }

    @Test
    fun `all stores should bind the shared capacity field`() {
        bindings.forEach { (prefix, bind) ->
            val properties = mapOf(
                "$prefix.enabled" to "true",
                "$prefix.max-size" to "32",
                "$prefix.max-delay" to "2ms",
                "$prefix.max-pending-items" to "64",
                "$prefix.lane-count" to "4",
            )
            bind(Binder(MapConfigurationPropertySource(properties))).assert()
                .isEqualTo(BatchOptions(32, Duration.ofMillis(2), 64, 4))
        }
    }

    @Test
    fun `enabled stores should reject invalid capacity and lane limits`() {
        bindings.forEach { (prefix, bind) ->
            listOf("max-pending-items" to "127", "lane-count" to "4097").forEach { (key, value) ->
                assertThrows<IllegalArgumentException> {
                    bind(
                        Binder(
                            MapConfigurationPropertySource(mapOf("$prefix.enabled" to "true", "$prefix.$key" to value))
                        )
                    )
                }
            }
        }
    }
}
