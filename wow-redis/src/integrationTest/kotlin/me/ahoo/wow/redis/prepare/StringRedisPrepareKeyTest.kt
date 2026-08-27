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

package me.ahoo.wow.redis.prepare

import me.ahoo.wow.api.annotation.PreparableKey
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toForever
import me.ahoo.wow.infra.prepare.proxy.DefaultPrepareKeyProxyFactory
import me.ahoo.wow.infra.prepare.proxy.prepareKeyMetadata
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class StringRedisPrepareKeyTest : RedisPrepareKeySpec<String>() {
    override val name: String = StringPrepareKey.NAME
    override val valueType: Class<String>
        get() = String::class.java

    override fun generateValue(): String {
        return GlobalIdGenerator.generateAsString()
    }

    override fun createPrepareKey(name: String): PrepareKey<String> {
        val metadata = prepareKeyMetadata<StringPrepareKey>()
        return DefaultPrepareKeyProxyFactory(prepareKeyFactory).create(metadata)
    }

    @Test
    fun `same raw key should be isolated by prepare name`() {
        val username = prepareKeyFactory.create("username", String::class.java)
        val email = prepareKeyFactory.create("email", String::class.java)

        username.prepare("same:{雪}", "alice".toForever())
            .test()
            .expectNext(true)
            .verifyComplete()
        email.prepare("same:{雪}", "alice@example.com".toForever())
            .test()
            .expectNext(true)
            .verifyComplete()

        username.get("same:{雪}")
            .test()
            .expectNext("alice")
            .verifyComplete()
        email.get("same:{雪}")
            .test()
            .expectNext("alice@example.com")
            .verifyComplete()
    }

    @PreparableKey(name = StringPrepareKey.NAME)
    interface StringPrepareKey : PrepareKey<String> {
        companion object {
            const val NAME = "string"
        }
    }
}
