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

package me.ahoo.wow.spring.boot

import com.mongodb.ConnectionString
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import javax.sql.DataSource

/**
 * The application's database connection pool, from its properties alone (decision D310): when `spring.datasource.url`
 * is set, HikariCP is on the class path, the application has no [DataSource] bean and no Spring Boot JDBC
 * (`spring-boot-jdbc`, whose own auto-configuration then makes it), the host makes a [HikariDataSource] of
 * `spring.datasource.url`, `.username`, `.password` and `.driver-class-name`, with HikariCP's settings under
 * `spring.datasource.hikari.*` as Boot reads them; closed with the context. Its pool opens at the first connection.
 * The backend then follows the [DataSource] bean as it follows the application's (`wow.backend`, D110); without the
 * URL the host connects to no database.
 */
@AutoConfiguration(before = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [HIKARI_DATA_SOURCE])
@ConditionalOnMissingClass(BOOT_JDBC_AUTO_CONFIGURATION)
@ConditionalOnProperty(prefix = DataSourceAutoConfiguration.PREFIX, name = ["url"])
class DataSourceAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DataSource::class)
    fun wowDataSource(environment: Environment): HikariDataSource {
        val binder = Binder.get(environment)
        val dataSource = HikariDataSource()
        binder.bind("$PREFIX.hikari", Bindable.ofInstance(dataSource))
        dataSource.jdbcUrl = environment.getRequiredProperty("$PREFIX.url")
        environment.getProperty("$PREFIX.username")?.let { dataSource.username = it }
        environment.getProperty("$PREFIX.password")?.let { dataSource.password = it }
        environment.getProperty("$PREFIX.driver-class-name")?.let { dataSource.driverClassName = it }
        logger.info { "The host's DataSource connects to ${dataSource.jdbcUrl} (spring.datasource.url, decision D310)." }
        return dataSource
    }

    companion object {
        /** Spring Boot's prefix of a DataSource's properties. */
        const val PREFIX: String = "spring.datasource"

        private val logger = KotlinLogging.logger {}
    }
}

/**
 * The application's MongoDB client, from its properties alone (decision D318): when wow-mongo is on the class path,
 * `spring.mongodb.uri` is set, the application has no [MongoClient] bean and no Spring Boot MongoDB
 * (`spring-boot-mongodb`, whose own auto-configuration then makes it), the host makes a [MongoClient] of the URI;
 * closed with the context. The MongoDB stores then open over it, in the database their `database` option names.
 */
@AutoConfiguration(before = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [MONGO_MODULE])
@ConditionalOnMissingClass(BOOT_MONGO_AUTO_CONFIGURATION)
@ConditionalOnProperty(MongoClientAutoConfiguration.URI)
class MongoClientAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(MongoClient::class)
    fun wowMongoClient(environment: Environment): MongoClient {
        val uri = ConnectionString(environment.getRequiredProperty(URI))
        logger.info { "The host's MongoClient connects to ${uri.hosts} ($URI, decision D318)." }
        return MongoClients.create(uri)
    }

    companion object {
        /** Spring Boot's key of a MongoDB connection string. */
        const val URI: String = "spring.mongodb.uri"

        private val logger = KotlinLogging.logger {}
    }
}

/** HikariCP's pool, present only when the application has HikariCP. */
internal const val HIKARI_DATA_SOURCE = "com.zaxxer.hikari.HikariDataSource"

/** Spring Boot's JDBC auto-configuration: with it, the application's DataSource is Boot's to make. */
internal const val BOOT_JDBC_AUTO_CONFIGURATION = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"

/** A class of wow-mongo, present only when the application has the module. */
internal const val MONGO_MODULE = "me.ahoo.wow.mongo.MongoStoreProvider"

/** Spring Boot's MongoDB auto-configuration: with it, the application's MongoClient is Boot's to make. */
internal const val BOOT_MONGO_AUTO_CONFIGURATION = "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration"
