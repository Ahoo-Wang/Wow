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

import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.spring.boot.starter.bi.BiClickHouseInspectorProperties
import me.ahoo.wow.spring.boot.starter.bi.BiDeploymentInspectorProperties
import me.ahoo.wow.spring.boot.starter.bi.BiScriptClusterProperties
import me.ahoo.wow.spring.boot.starter.bi.BiScriptProperties
import me.ahoo.wow.spring.boot.starter.bi.BiScriptTopologyProperties
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import me.ahoo.wow.spring.boot.starter.command.IdempotencyProperties
import me.ahoo.wow.spring.boot.starter.compensation.CompensationProperties
import me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchEventStoreBatchProperties
import me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchProperties
import me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchQueryProperties
import me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchSnapshotStoreBatchProperties
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.AggregateStorageRouteProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageChannelRouteProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.spring.boot.starter.kafka.KafkaReceiverProperties
import me.ahoo.wow.spring.boot.starter.mongo.MongoEventStoreBatchProperties
import me.ahoo.wow.spring.boot.starter.mongo.MongoProperties
import me.ahoo.wow.spring.boot.starter.mongo.MongoSnapshotStoreBatchProperties
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIProperties
import me.ahoo.wow.spring.boot.starter.prepare.PrepareProperties
import me.ahoo.wow.spring.boot.starter.query.QueryProperties
import me.ahoo.wow.spring.boot.starter.redis.RedisProperties
import me.ahoo.wow.spring.boot.starter.redis.RedisStreamRecoveryProperties
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxProperties
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.ConstructorBinding
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class ConfigurationPropertiesStyleTest {
    private val javaBeanTypes = listOf(
        WowProperties::class.java,
        BiScriptProperties::class.java,
        CommandProperties::class.java,
        CompensationProperties::class.java,
        ElasticsearchEventStoreBatchProperties::class.java,
        ElasticsearchProperties::class.java,
        ElasticsearchQueryProperties::class.java,
        ElasticsearchSnapshotStoreBatchProperties::class.java,
        EventProperties::class.java,
        StorageRoutingProperties::class.java,
        SnapshotProperties::class.java,
        StateProperties::class.java,
        EventStoreProperties::class.java,
        MongoEventStoreBatchProperties::class.java,
        MongoProperties::class.java,
        MongoSnapshotStoreBatchProperties::class.java,
        OpenAPIProperties::class.java,
        PrepareProperties::class.java,
        RedisProperties::class.java,
        RedisStreamRecoveryProperties::class.java,
        WebFluxProperties::class.java,
        QueryProperties::class.java,
    )

    private val requiredConstructorTypes = listOf(KafkaProperties::class.java)

    private val nestedValueTypes = listOf(
        BusProperties::class.java,
        LocalFirstProperties::class.java,
        BiDeploymentInspectorProperties::class.java,
        BiClickHouseInspectorProperties::class.java,
        BiScriptTopologyProperties::class.java,
        BiScriptClusterProperties::class.java,
        IdempotencyProperties::class.java,
        IdempotencyProperties.BloomFilter::class.java,
        AggregateStorageRouteProperties::class.java,
        StorageChannelRouteProperties::class.java,
        KafkaReceiverProperties::class.java,
        WebFluxProperties.GlobalError::class.java,
        WebFluxProperties.Batch::class.java,
        WebFluxProperties.Query::class.java,
        QueryProperties.Http::class.java,
    )

    @Test
    fun `direct configuration properties should use mutable property style`() {
        (javaBeanTypes + nestedValueTypes).forEach { type ->
            assertTrue(
                type.declaredConstructors.any { it.parameterCount == 0 },
                "${type.name} must have a no-args constructor",
            )
            assertTrue(
                type.declaredConstructors.none { it.isAnnotationPresent(ConstructorBinding::class.java) },
                "${type.name} must not use @ConstructorBinding",
            )
            type.declaredPropertyFields().forEach { field ->
                assertTrue(
                    type.methods.any { it.name == field.setterName() && it.parameterCount == 1 },
                    "${type.name}.${field.name} must have a setter",
                )
            }
        }
    }

    @Test
    fun `required configuration properties should retain their required constructor`() {
        requiredConstructorTypes.forEach { type ->
            assertTrue(
                type.declaredConstructors.none { it.parameterCount == 0 },
                "${type.name} must keep its required constructor",
            )
            type.declaredPropertyFields().forEach { field ->
                assertTrue(
                    type.methods.any { it.name == field.setterName() && it.parameterCount == 1 },
                    "${type.name}.${field.name} must have a setter",
                )
            }
        }
    }

    @Test
    fun `generated metadata should include batch and nested leaf properties`() {
        val propertyNames = JsonSerializer.readTree(
            requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/spring-configuration-metadata.json"))
                .bufferedReader()
                .use { it.readText() },
        ).path("properties").asSequence().map { it.path("name").stringValue() }.toSet()
        setOf(
            "wow.mongo.event-store-batch.max-pending-items",
            "wow.mongo.snapshot-store-batch.lane-count",
            "wow.elasticsearch.event-store-batch.max-delay",
            "wow.elasticsearch.snapshot-store-batch.max-size",
            "wow.bi.script.inspector.type",
            "wow.bi.script.inspector.clickhouse.max-retries",
            "wow.bi.script.topology.cluster.name",
            "wow.command.idempotency.bloom-filter.ttl",
            "wow.kafka.receiver.retry-backoff",
            "wow.webflux.batch.prefetch",
            "wow.webflux.query.idle-timeout",
            "wow.query.require-explicit-entry",
            "wow.query.http.max-page-window",
        ).forEach { propertyName ->
            assertTrue(propertyName in propertyNames, "Missing generated metadata property: $propertyName")
        }
    }

    private fun Class<*>.declaredPropertyFields(): List<Field> = declaredFields.filter {
        Modifier.isPrivate(it.modifiers) &&
            !Modifier.isStatic(it.modifiers) &&
            !it.isSynthetic
    }

    private fun Field.setterName(): String = "set${name.replaceFirstChar(Char::uppercaseChar)}"
}
