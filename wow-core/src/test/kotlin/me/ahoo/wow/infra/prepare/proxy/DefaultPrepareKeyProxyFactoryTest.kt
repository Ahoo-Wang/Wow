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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.PrepareKeyFactory
import org.junit.jupiter.api.Test

class DefaultPrepareKeyProxyFactoryTest {
    object MockPrepareKeyFactory : PrepareKeyFactory {
        override fun <V : Any> create(
            name: String,
            valueClass: Class<V>
        ): PrepareKey<V> {
            return mockk()
        }
    }

    @Test
    fun create() {
        val proxyFactory = DefaultPrepareKeyProxyFactory(MockPrepareKeyFactory)
        val prepareKey = proxyFactory.create(prepareKeyMetadata<PrepareKeyMetadataParserTest.NameNotEmpty>())
        prepareKey.assert().isInstanceOf(PrepareKeyMetadataParserTest.NameNotEmpty::class.java)
    }
}
