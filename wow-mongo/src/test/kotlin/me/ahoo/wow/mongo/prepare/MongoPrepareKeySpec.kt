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

package me.ahoo.wow.mongo.prepare

import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.asTtlAt
import me.ahoo.wow.mongo.MongoLauncher
import me.ahoo.wow.mongo.SchemaInitializerSpec
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration

abstract class MongoPrepareKeySpec<V : Any>(
    private val name: String,
    private val valueType: Class<V>
) {
    private val client = MongoClients.create(MongoLauncher.getConnectionString())
    private val database: MongoDatabase = client.getDatabase(SchemaInitializerSpec.DATABASE_NAME)
    private val prepareKey = MongoPrepareKey<V>(name, valueType, database)

    abstract fun generateValue(): V

    @Test
    fun prepare() {
        val key = GlobalIdGenerator.generateAsString()
        val value = generateValue()
        prepareKey.prepare(key, value)
            .test()
            .expectNext(true)
            .verifyComplete()
        prepareKey.prepare(key, value)
            .test()
            .expectNext(false)
            .verifyComplete()
        prepareKey.get(key)
            .test()
            .expectNext(value)
            .verifyComplete()

        prepareKey.rollback(key)
            .test()
            .expectNext(true)
            .verifyComplete()

        prepareKey.get(key)
            .test()
            .verifyComplete()

        prepareKey.prepare(key, value)
            .test()
            .expectNext(true)
            .verifyComplete()

        val newValue = generateValue()
        prepareKey.reprepare(key, value, newValue)
            .test()
            .expectNext(true)
            .verifyComplete()
        prepareKey.get(key)
            .test()
            .expectNext(newValue)
            .verifyComplete()
        prepareKey.rollback(key)
            .test()
            .expectNext(true)
            .verifyComplete()
    }

    @Test
    fun reprepare() {
        val key = GlobalIdGenerator.generateAsString()
        val value = generateValue()
        prepareKey.prepare(key, value)
            .test()
            .expectNext(true)
            .verifyComplete()

        prepareKey.get(key)
            .test()
            .expectNext(value)
            .verifyComplete()

        val newValue = generateValue()
        prepareKey.reprepare(key, value, newValue)
            .test()
            .expectNext(true)
            .verifyComplete()
        prepareKey.get(key)
            .test()
            .expectNext(newValue)
            .verifyComplete()
        val newValue2 = generateValue()
        prepareKey.reprepare(key, value, newValue2)
            .test()
            .expectNext(false)
            .verifyComplete()
        prepareKey.reprepare(key, newValue2)
            .test()
            .expectNext(true)
            .verifyComplete()

        prepareKey.get(key)
            .test()
            .expectNext(newValue2)
            .verifyComplete()
        prepareKey.rollback(key)
            .test()
            .expectNext(true)
            .verifyComplete()
    }

    @Test
    fun prepareWithTtlAt() {
        val key = GlobalIdGenerator.generateAsString()
        val expireAfter = Duration.ofSeconds(2)
        val ttlAt = System.currentTimeMillis() + expireAfter.toMillis()
        val preparedValue = generateValue().asTtlAt(ttlAt)
        prepareKey.prepare(key, preparedValue)
            .test()
            .expectNext(true)
            .verifyComplete()
        Thread.sleep(expireAfter.toMillis())
        prepareKey.get(key)
            .test()
            .verifyComplete()
        val ttlAt2 = System.currentTimeMillis() + expireAfter.toMillis()
        val preparedValue2 = generateValue().asTtlAt(ttlAt2)
        prepareKey.prepare(key, preparedValue2)
            .test()
            .expectNext(true)
            .verifyComplete()
        prepareKey.get(key)
            .test()
            .expectNext(preparedValue2.value)
            .verifyComplete()
    }

    @Test
    fun rollback() {
        val key = GlobalIdGenerator.generateAsString()
        val value = generateValue()
        prepareKey.prepare(key, value)
            .test()
            .expectNext(true)
            .verifyComplete()
        prepareKey.rollback(key, value)
            .test()
            .expectNext(true)
            .verifyComplete()
        prepareKey.prepare(key, value)
            .test()
            .expectNext(true)
            .verifyComplete()
        val otherValue = generateValue()
        prepareKey.rollback(key, otherValue)
            .test()
            .expectNext(false)
            .verifyComplete()
        prepareKey.rollback(key, value)
            .test()
            .expectNext(true)
            .verifyComplete()
    }

    @Test
    fun usingPrepare() {
        val key = GlobalIdGenerator.generateAsString()
        val value = generateValue()
        prepareKey.usingPrepare(key, value) {
            require(it)
            RuntimeException().toMono<String>()
        }.test()
            .expectError(RuntimeException::class.java)
            .verify()

        prepareKey.get(key)
            .test()
            .verifyComplete()
    }

    @Test
    fun getName() {
        assertThat(prepareKey.name, equalTo(name))
    }
}
