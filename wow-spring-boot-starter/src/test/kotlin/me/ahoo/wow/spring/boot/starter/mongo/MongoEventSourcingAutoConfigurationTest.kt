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

package me.ahoo.wow.spring.boot.starter.mongo

import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.MongoSnapshotRepository
import me.ahoo.wow.mongo.prepare.MongoPrepareKeyFactory
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.container.MongoLauncher
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MongoEventSourcingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${MongoProperties.PREFIX}.event-stream-database=testEventStream",
                "${MongoProperties.PREFIX}.snapshot-database=testSnapshot",
                "${MongoProperties.PREFIX}.prepare-database=testPrepare",
                "${MongoProperties.PREFIX}.error-database=testError",
            )
            .withBean(MongoClient::class.java, {
                MongoClients.create(MongoLauncher.getConnectionString())
            })
            .withUserConfiguration(
                MongoEventSourcingAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(MongoEventStore::class.java)
                    .hasSingleBean(MongoSnapshotRepository::class.java)
                    .hasSingleBean(MongoPrepareKeyFactory::class.java)
            }
    }
}
