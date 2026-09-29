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

package me.ahoo.wow.viewstore.server

import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource

/**
 * The server runs as several instances on the compensation service's middleware: MongoDB for the event streams and
 * snapshots, Kafka for the buses (Wow's defaults, so no bus or storage is set to anything else), and Redis for the
 * CosId machine ids.
 */
class ViewStoreServerConfigurationTest {
    private fun load(resource: Resource) = YamlPropertySourceLoader().load("application", resource).single()

    @ParameterizedTest
    @ValueSource(strings = ["classpath", "distribution"])
    fun `runs on MongoDB, Kafka and Redis`(source: String) {
        val properties = load(
            if (source == "classpath") {
                ClassPathResource("application.yaml")
            } else {
                FileSystemResource("src/dist/config/application.yaml")
            }
        )
        properties.getProperty("spring.mongodb.uri").assert().isNotNull()
        properties.getProperty("spring.data.redis.url").assert().isNotNull()
        properties.getProperty("wow.kafka.bootstrap-servers").assert().isNotNull()
        properties.getProperty("cosid.machine.distributor.type").assert().isEqualTo("redis")
        listOf(
            "wow.command.bus.type",
            "wow.event.bus.type",
            "wow.eventsourcing.state.bus.type",
            "wow.eventsourcing.store.storage",
            "wow.eventsourcing.snapshot.storage",
            "wow.kafka.enabled",
            "wow.mongo.enabled",
        ).forEach { properties.getProperty(it).assert().isNull() }
        properties.getProperty("spring.application.name").assert().isEqualTo("view-store-server")
    }
}
