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

package me.ahoo.wow.mongo.log

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import me.ahoo.wow.tck.container.ContainerImages
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger

/** One MongoDB replica set for the test JVM, and a fresh context (so fresh collections) per log. */
object MongoLogFixture {
    private val container: MongoDBContainer by lazy {
        MongoDBContainer(DockerImageName.parse(ContainerImages.MONGO)).also { it.start() }
    }

    val client: MongoClient by lazy { MongoClients.create(container.replicaSetUrl) }

    private val contexts = AtomicInteger()

    fun newContext(prefix: String = "tck"): String = "$prefix-${contexts.incrementAndGet()}"

    fun log(partitions: Int, context: String = newContext()): MongoEventLog =
        MongoEventLog(client, DATABASE, context, partitions).also { it.createSchema() }

    const val DATABASE = "wow_v10_log"
}
