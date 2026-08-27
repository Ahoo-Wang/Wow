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

package me.ahoo.wow.tck.prepare

import me.ahoo.test.asserts.assert
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toTtlAt
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration

abstract class PrepareKeySpec<V : Any> {
    abstract val name: String
    abstract val valueType: Class<V>
    abstract fun generateValue(): V
    private lateinit var prepareKey: PrepareKey<V>

    abstract fun createPrepareKey(name: String): PrepareKey<V>

    @BeforeEach
    open fun setup() {
        prepareKey = createPrepareKey(name)
    }

    @Test
    fun prepare() {
        val key = generateGlobalId()
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
        val key = generateGlobalId()
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
    fun reprepareNewKey() {
        val oldKey = generateGlobalId()
        val oldValue = generateValue()

        val key = generateGlobalId()
        val value = generateValue()

        prepareKey.prepare(oldKey, oldValue)
            .test()
            .expectNext(true)
            .verifyComplete()

        prepareKey.reprepare(oldKey, oldValue, key, value)
            .test()
            .expectNext(true)
            .verifyComplete()

        prepareKey.get(key)
            .test()
            .expectNext(value)
            .verifyComplete()

        prepareKey.reprepare(oldKey, oldValue, key, value)
            .test()
            .expectNext(false)
            .verifyComplete()
    }

    @Test
    fun reprepareNewKeyNotFoundKey() {
        val key = generateGlobalId()
        val value = generateValue()

        val notFoundKey = generateGlobalId()
        prepareKey.reprepare(notFoundKey, value, key, value)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()

        prepareKey.getValue(key)
            .test()
            .verifyComplete()
    }

    @Test
    fun reprepareNewKeyEqKey() {
        val key = generateGlobalId()
        val value = generateValue()
        assertThrows<IllegalArgumentException> {
            prepareKey.reprepare(key, value, key, value)
        }
    }

    @Test
    fun prepareWithTtlAt() {
        val key = generateGlobalId()
        val expireAfter = Duration.ofSeconds(2)
        val ttlAt = System.currentTimeMillis() + expireAfter.toMillis()
        val preparedValue = generateValue().toTtlAt(ttlAt)
        prepareKey.prepare(key, preparedValue)
            .test()
            .expectNext(true)
            .verifyComplete()
        Thread.sleep(expireAfter.toMillis())
        prepareKey.get(key)
            .test()
            .verifyComplete()
        val ttlAt2 = System.currentTimeMillis() + expireAfter.toMillis()
        val preparedValue2 = generateValue().toTtlAt(ttlAt2)
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
        val key = generateGlobalId()
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
        val key = generateGlobalId()
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
        prepareKey.name.assert().isEqualTo(name)
    }
}
