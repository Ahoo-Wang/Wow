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

package me.ahoo.wow.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import me.ahoo.wow.tck.containers.WowContainers
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A client on the test replica set that records every command it sends (MongoDB command monitoring), so a test can
 * pin how many round trips an operation takes (B.D diagnostics).
 */
object CountedMongo {
    /** One command sent: its name (`find`, `update`, `commitTransaction`, ...) and the collection it names, if any. */
    data class Sent(val name: String, val collection: String?)

    private val sent = ConcurrentLinkedQueue<Sent>()

    private val listener = object : CommandListener {
        override fun commandStarted(event: CommandStartedEvent) {
            val target = event.command[event.commandName]
            sent += Sent(event.commandName, if (target?.isString == true) target.asString().value else null)
        }
    }

    val client: MongoClient by lazy {
        MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(WowContainers.mongoUrl))
                .addCommandListener(listener)
                .build(),
        )
    }

    /** The commands [block] sent through [client], in order; the driver's own monitoring commands are left out. */
    inline fun <T> commandsOf(block: () -> T): Pair<T, List<Sent>> {
        val mark = begin()
        val result = block()
        return result to since(mark)
    }

    fun begin(): Int = sent.size

    fun since(mark: Int): List<Sent> = sent.drop(mark).filter { it.name !in MONITORING }

    private val MONITORING = setOf("hello", "isMaster", "ismaster", "buildInfo", "endSessions", "ping")
}
