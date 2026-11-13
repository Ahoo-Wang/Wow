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
import com.mongodb.event.CommandSucceededEvent
import me.ahoo.wow.spi.store.HeldStoreCallMarks
import me.ahoo.wow.tck.containers.WowContainers
import java.util.concurrent.TimeUnit

/**
 * A client on the test replica set built with a command clock, as the benchmark server builds its: each command's own
 * time added to the marks of the call its thread holds ([HeldStoreCallMarks]).
 */
object ClockedMongo {
    val client: MongoClient by lazy {
        MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(WowContainers.mongoUrl))
                .addCommandListener(
                    object : CommandListener {
                        override fun commandSucceeded(event: CommandSucceededEvent) {
                            HeldStoreCallMarks.held()?.commanded(event.getElapsedTime(TimeUnit.NANOSECONDS))
                        }
                    },
                )
                .build(),
        )
    }
}
