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

package me.ahoo.wow.benchmark.transfer

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment

// region mongo
/**
 * The benchmark's V10 server: the account and the transfer saga on MongoDB (the `mongo` profile) or PostgreSQL (the
 * `postgres` profile), its state stream published to Kafka (`application.yaml`). A node of a cluster takes its ports
 * and node id from the command line (test/benchmarks/README.md).
 */
@SpringBootApplication(proxyBeanMethods = false)
class TransferServer

/**
 * The `mongo` profile: the application's `MongoClient` on `spring.mongodb.uri`, which selects the MongoDB backend. A
 * bean of its own, not the one the host makes of the property, because it takes the application's
 * driver [CommandListener]s, which no property can add: none in the server, the round-trip counter in ComponentBench.
 * The `postgres` profile needs no bean: the host makes its pool of `spring.datasource.*`.
 */
@Configuration(proxyBeanMethods = false)
@Profile("mongo")
class MongoBackend {
    @Bean(destroyMethod = "close")
    fun mongoClient(environment: Environment, listeners: ObjectProvider<CommandListener>): MongoClient =
        MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(environment.getRequiredProperty("spring.mongodb.uri")))
                .apply { listeners.orderedStream().forEach(::addCommandListener) }
                .build(),
        )
}
// endregion

/** The benchmark's clients are anonymous; outside development the host wants that said in a bean. */
@Configuration(proxyBeanMethods = false)
class AnonymousCallers {
    @Bean
    fun anonymousCallerResolver(): CallerResolver = CallerResolver.ANONYMOUS
}

fun main(args: Array<String>) {
    runApplication<TransferServer>(*args)
}
