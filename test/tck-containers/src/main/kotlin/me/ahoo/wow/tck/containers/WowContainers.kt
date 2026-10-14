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

package me.ahoo.wow.tck.containers

import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The containers the V10 integration tests run against (decision D196): one image per backend, so every module tests
 * the versions the others do.
 *
 * The properties are shared: started on first use and kept for the rest of the JVM (Testcontainers removes them when
 * it exits), so the TCK specs and fixtures of one module run on one container each. A `new*` function builds a
 * container that is not started, for a test that owns its lifecycle: one that stops it, or configures it first.
 */
object WowContainers {
    const val POSTGRES_IMAGE = "postgres:17-alpine"

    /** The MongoDB image, the one 9.x's wow-tck tests against (`ContainerImages.MONGO`). */
    const val MONGO_IMAGE = "mongo:7.0"
    const val KAFKA_IMAGE = "confluentinc/cp-kafka:7.4.0"
    const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"

    val postgres: PostgreSQLContainer by lazy { newPostgres().also { it.start() } }

    /** A single-node replica set: the MongoDB backend writes in transactions. */
    val mongo: MongoDBContainer by lazy { newMongo().also { it.start() } }

    val kafka: ConfluentKafkaContainer by lazy { newKafka().also { it.start() } }

    val clickHouse: ClickHouseContainer by lazy { newClickHouse().also { it.start() } }

    fun newPostgres(): PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE)

    /** A single-node replica set, not started; connect through its `replicaSetUrl`. */
    fun newMongo(): MongoDBContainer = MongoDBContainer(MONGO_IMAGE).withReplicaSet()

    fun newKafka(): ConfluentKafkaContainer = ConfluentKafkaContainer(KAFKA_IMAGE)

    fun newClickHouse(): ClickHouseContainer = ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE))
}
