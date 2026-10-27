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

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.net.URLDecoder

/**
 * Wow's connection settings the application's own already say, as defaults that come after every other source, so a
 * `wow.*` key the application sets still wins:
 *
 * - `wow.mongo.database`, the database of `spring.mongodb.uri` ([databaseOf]) when it names one;
 * - `wow.kafka.bootstrap-servers`, `spring.kafka.bootstrap-servers`, so an application that configures Spring's
 *   Kafka connects Wow to the same brokers.
 */
internal class ConnectionDefaultsEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val binder = Binder.get(environment)
        val defaults = buildMap<String, Any> {
            binder.bind(MONGO_URI, String::class.java).orElse(null)?.let(::databaseOf)?.let {
                put(MONGO_DATABASE, it)
            }
            binder.bind(SPRING_KAFKA_SERVERS, Array<String>::class.java).orElse(null)
                ?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }
                ?.let { put(KAFKA_SERVERS, it.joinToString(",")) }
        }
        if (defaults.isNotEmpty()) {
            environment.propertySources.addLast(MapPropertySource(SOURCE, defaults))
        }
    }

    companion object {
        const val SOURCE: String = "wowConnectionDefaults"

        // Spring Boot's key, as MongoClientAutoConfiguration reads it: no MongoDB class is needed to read it here.
        const val MONGO_URI: String = "spring.mongodb.uri"
        const val MONGO_DATABASE: String = "wow.mongo.database"
        const val SPRING_KAFKA_SERVERS: String = "spring.kafka.bootstrap-servers"
        const val KAFKA_SERVERS: String = "wow.kafka.bootstrap-servers"

        /**
         * The database a MongoDB connection string names, the path after its hosts (`mongodb://host/shop?...` names
         * `shop`); null when it names none.
         */
        fun databaseOf(uri: String): String? {
            val afterScheme = uri.substringAfter("://", missingDelimiterValue = "")
            val path = afterScheme.substringAfter('/', missingDelimiterValue = "").substringBefore('?')
            return URLDecoder.decode(path, Charsets.UTF_8).takeIf { it.isNotBlank() }
        }
    }
}
