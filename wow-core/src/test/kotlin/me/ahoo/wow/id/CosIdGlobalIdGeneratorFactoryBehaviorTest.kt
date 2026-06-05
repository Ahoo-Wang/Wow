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

package me.ahoo.wow.id

import io.mockk.mockk
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class CosIdGlobalIdGeneratorFactoryBehaviorTest {

    @Test
    fun `create should return configured CosId generator by global id name`() {
        val provider = DefaultIdGeneratorProvider()
        val generator = mockk<CosIdGenerator>()
        provider.set(CosIdGlobalIdGeneratorFactory.ID_NAME, generator)

        CosIdGlobalIdGeneratorFactory(provider).create().assert().isSameAs(generator)
    }

    @Test
    fun `create should return null when provider does not contain global id name`() {
        CosIdGlobalIdGeneratorFactory(DefaultIdGeneratorProvider()).create().assert().isNull()
    }
}
