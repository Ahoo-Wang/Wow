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
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import javax.sql.DataSource

// region mongo
/**
 * The benchmark's V10 server: the account and the transfer saga on MongoDB (the `mongo` profile) or PostgreSQL (the
 * `postgres` profile), its state stream published to Kafka (`application.yaml`). A node of a cluster takes its ports
 * and node id from the command line (test/benchmarks/README.md).
 */
@SpringBootApplication(proxyBeanMethods = false)
class TransferServer

/**
 * The `mongo` profile: the application's `MongoClient` on `spring.mongodb.uri`, which selects the MongoDB backend. It
 * takes the application's driver [CommandListener]s: none in the server, the round-trip counter in ComponentBench.
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


/** The `postgres` profile: a pooled `DataSource` on `spring.datasource.*`, which selects the PostgreSQL backend. */
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
class PostgresBackend {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = environment.getRequiredProperty("spring.datasource.url")
            username = environment.getProperty("spring.datasource.username")
            password = environment.getProperty("spring.datasource.password")
        },
    )
}

fun main(args: Array<String>) {
    runApplication<TransferServer>(*args)
}
