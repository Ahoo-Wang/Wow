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

package me.ahoo.wow.infra.prepare

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toForever
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toTtlAt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap

class PrepareKeyTest {

    @Test
    fun `get filters expired prepared values and returns active values`() {
        val prepareKey = InMemoryPrepareKey<String>()
        prepareKey.prepare("expired", "old".toTtlAt(1)).block()
        prepareKey.prepare("active", "current".toForever()).block()

        StepVerifier.create(prepareKey.get("expired"))
            .verifyComplete()
        StepVerifier.create(prepareKey.get("active"))
            .expectNext("current")
            .verifyComplete()
    }

    @Test
    fun `using prepare rolls back successful preparation when operation fails`() {
        val prepareKey = InMemoryPrepareKey<String>()

        StepVerifier.create(
            prepareKey.usingPrepare("reserved", "value") {
                Mono.error(IllegalStateException("operation failed"))
            }
        )
            .expectError(IllegalStateException::class.java)
            .verify()

        StepVerifier.create(prepareKey.getValue("reserved"))
            .verifyComplete()
        prepareKey.rolledBack.assert().isEqualTo(listOf("reserved" to "value"))
    }

    @Test
    fun `reprepare changing key prepares new key and rolls back old key`() {
        val prepareKey = InMemoryPrepareKey<String>()
        prepareKey.prepare("old-key", "old-value").block()

        StepVerifier.create(prepareKey.reprepare("old-key", "old-value", "new-key", "new-value"))
            .expectNext(true)
            .verifyComplete()

        StepVerifier.create(prepareKey.get("old-key"))
            .verifyComplete()
        StepVerifier.create(prepareKey.get("new-key"))
            .expectNext("new-value")
            .verifyComplete()
    }

    @Test
    fun `reprepare changing key returns false when new key is already prepared`() {
        val prepareKey = InMemoryPrepareKey<String>()
        prepareKey.prepare("old-key", "old-value").block()
        prepareKey.prepare("new-key", "existing-value").block()

        StepVerifier.create(prepareKey.reprepare("old-key", "old-value", "new-key", "new-value"))
            .expectNext(false)
            .verifyComplete()

        StepVerifier.create(prepareKey.get("old-key"))
            .expectNext("old-value")
            .verifyComplete()
        StepVerifier.create(prepareKey.get("new-key"))
            .expectNext("existing-value")
            .verifyComplete()
    }

    @Test
    fun `reprepare changing key rejects same old and new keys`() {
        val prepareKey = InMemoryPrepareKey<String>()

        val exception = assertThrows<IllegalArgumentException> {
            prepareKey.reprepare("same-key", "old-value", "same-key", "new-value")
        }

        exception.message.assert().contains("oldKey must not be equals to newKey")
    }
}

private class InMemoryPrepareKey<V : Any>(
    override val name: String = "test-prepare-key"
) : PrepareKey<V> {
    private val values = ConcurrentHashMap<String, PreparedValue<V>>()
    val rolledBack: MutableList<Pair<String, V>> = mutableListOf()

    override fun prepare(
        key: String,
        value: PreparedValue<V>
    ): Mono<Boolean> =
        Mono.fromSupplier {
            values.putIfAbsent(key, value) == null
        }

    override fun getValue(key: String): Mono<PreparedValue<V>> =
        Mono.justOrEmpty(values[key])

    override fun rollback(key: String): Mono<Boolean> =
        Mono.fromSupplier {
            values.remove(key) != null
        }

    override fun rollback(
        key: String,
        value: V
    ): Mono<Boolean> =
        Mono.fromSupplier {
            val current = values[key] ?: return@fromSupplier false
            if (current.value != value) {
                return@fromSupplier false
            }
            values.remove(key)
            rolledBack += key to value
            true
        }

    override fun reprepare(
        key: String,
        oldValue: V,
        newValue: PreparedValue<V>
    ): Mono<Boolean> =
        Mono.fromSupplier {
            val current = values[key] ?: return@fromSupplier false
            if (current.value != oldValue) {
                return@fromSupplier false
            }
            values[key] = newValue
            true
        }

    override fun reprepare(
        key: String,
        value: PreparedValue<V>
    ): Mono<Boolean> =
        Mono.fromSupplier {
            if (!values.containsKey(key)) {
                return@fromSupplier false
            }
            values[key] = value
            true
        }
}
