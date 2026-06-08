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

package me.ahoo.wow.infra.prepare.proxy

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.PreparableKey
import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import me.ahoo.wow.infra.prepare.PreparedValue
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toForever
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class PrepareKeyProxyAndMetadataTest {

    @Test
    fun `should parse preparable key metadata from annotated interface`() {
        val metadata = prepareKeyMetadata<EmailPrepareKey>()

        metadata.name.assert().isEqualTo("email")
        metadata.proxyInterface.assert().isEqualTo(EmailPrepareKey::class)
        metadata.valueType.assert().isEqualTo(EmailIndexValue::class)
    }

    @Test
    fun `should use interface simple name when preparable key name is blank`() {
        val metadata = prepareKeyMetadata<DefaultNamedPrepareKey>()

        metadata.name.assert().isEqualTo("DefaultNamedPrepareKey")
        metadata.proxyInterface.assert().isEqualTo(DefaultNamedPrepareKey::class)
        metadata.valueType.assert().isEqualTo(DefaultNamedIndexValue::class)
    }

    @Test
    fun `should create proxy and delegate prepare key calls`() {
        val factory = RecordingPrepareKeyFactory()
        val metadata = prepareKeyMetadata<EmailPrepareKey>()
        val proxy = DefaultPrepareKeyProxyFactory(factory).create(metadata)
        val value = EmailIndexValue("user-1").toForever()

        proxy.assert().isInstanceOf(EmailPrepareKey::class.java)
        proxy.name.assert().isEqualTo("email")
        StepVerifier.create(proxy.prepare("a@example.com", value))
            .expectNext(true)
            .verifyComplete()

        factory.createdName.assert().isEqualTo("email")
        factory.createdValueClass.assert().isEqualTo(EmailIndexValue::class.java)
        factory.createdKey!!.preparedKey.assert().isEqualTo("a@example.com")
        factory.createdKey!!.preparedValue.assert().isSameAs(value)
    }

    @Test
    fun `proxy should propagate original delegate exception`() {
        val metadata = prepareKeyMetadata<EmailPrepareKey>()
        val proxy = DefaultPrepareKeyProxyFactory(FailingPrepareKeyFactory).create(metadata)
        val value = EmailIndexValue("user-1").toForever()

        val error = assertThrows<IllegalStateException> {
            proxy.prepare("a@example.com", value)
        }
        error.message.assert().isEqualTo("boom a@example.com")
    }
}

@PreparableKey(name = "email")
private interface EmailPrepareKey : PrepareKey<EmailIndexValue>

private data class EmailIndexValue(val userId: String)

@PreparableKey
private interface DefaultNamedPrepareKey : PrepareKey<DefaultNamedIndexValue>

private data class DefaultNamedIndexValue(val id: String)

private class RecordingPrepareKeyFactory : PrepareKeyFactory {
    var createdName: String? = null
        private set
    var createdValueClass: Class<*>? = null
        private set
    var createdKey: RecordingPrepareKey<*>? = null
        private set

    override fun <V : Any> create(
        name: String,
        valueClass: Class<V>
    ): PrepareKey<V> {
        createdName = name
        createdValueClass = valueClass
        return RecordingPrepareKey<V>(name).also {
            createdKey = it
        }
    }
}

private class RecordingPrepareKey<V : Any>(override val name: String) : PrepareKey<V> {
    var preparedKey: String? = null
        private set
    var preparedValue: PreparedValue<V>? = null
        private set

    override fun prepare(
        key: String,
        value: PreparedValue<V>
    ): Mono<Boolean> {
        preparedKey = key
        preparedValue = value
        return Mono.just(true)
    }

    override fun getValue(key: String): Mono<PreparedValue<V>> = Mono.empty()

    override fun rollback(key: String): Mono<Boolean> = Mono.just(true)

    override fun rollback(
        key: String,
        value: V
    ): Mono<Boolean> = Mono.just(true)

    override fun reprepare(
        key: String,
        oldValue: V,
        newValue: PreparedValue<V>
    ): Mono<Boolean> = Mono.just(true)

    override fun reprepare(
        key: String,
        value: PreparedValue<V>
    ): Mono<Boolean> = Mono.just(true)
}

private object FailingPrepareKeyFactory : PrepareKeyFactory {
    override fun <V : Any> create(
        name: String,
        valueClass: Class<V>
    ): PrepareKey<V> = FailingPrepareKey(name)
}

private class FailingPrepareKey<V : Any>(override val name: String) : PrepareKey<V> {
    override fun prepare(
        key: String,
        value: PreparedValue<V>
    ): Mono<Boolean> {
        error("boom $key")
    }

    override fun getValue(key: String): Mono<PreparedValue<V>> = Mono.empty()

    override fun rollback(key: String): Mono<Boolean> = Mono.just(true)

    override fun rollback(
        key: String,
        value: V
    ): Mono<Boolean> = Mono.just(true)

    override fun reprepare(
        key: String,
        oldValue: V,
        newValue: PreparedValue<V>
    ): Mono<Boolean> = Mono.just(true)

    override fun reprepare(
        key: String,
        value: PreparedValue<V>
    ): Mono<Boolean> = Mono.just(true)
}
