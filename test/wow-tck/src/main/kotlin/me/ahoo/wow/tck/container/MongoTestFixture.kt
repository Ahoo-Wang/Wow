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

package me.ahoo.wow.tck.container

import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoDatabase
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher

class MongoTestFixture(
    private val databasePrefix: String = "wow_it",
) : BeforeEachCallback, AfterEachCallback, TestWatcher {
    private val clients = mutableListOf<MongoClient>()

    lateinit var databaseName: String
        private set

    val connectionString: String
        get() = WowTestContainers.mongo.connectionString

    override fun beforeEach(context: ExtensionContext) {
        WowTestContainers.mongo.isRunning
        databaseName = ContainerTestIds.nextName(databasePrefix)
    }

    fun client(): MongoClient {
        return MongoClients.create(connectionString)
            .also {
                clients.add(it)
            }
    }

    fun database(): MongoDatabase {
        return client().getDatabase(databaseName)
    }

    fun database(name: String): MongoDatabase {
        return client().getDatabase(name)
    }

    override fun afterEach(context: ExtensionContext) {
        try {
            clients.forEach(MongoClient::close)
        } finally {
            clients.clear()
        }
    }

    override fun testFailed(context: ExtensionContext, cause: Throwable?) {
        ContainerDiagnostics.printFailure("mongo", WowTestContainers.mongo, requireNotNull(cause))
    }
}
