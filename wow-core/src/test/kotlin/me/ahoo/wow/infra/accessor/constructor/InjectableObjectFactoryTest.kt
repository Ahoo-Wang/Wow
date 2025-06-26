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

package me.ahoo.wow.infra.accessor.constructor

import me.ahoo.test.asserts.assert
import me.ahoo.wow.ioc.SimpleServiceProvider
import org.junit.jupiter.api.Test

internal class InjectableObjectFactoryTest {

    @Test
    fun newInstance() {
        val injectableObjectFactory =
            InjectableObjectFactory(MockServiceWithoutParameter::class.java.getConstructor(), SimpleServiceProvider())
        injectableObjectFactory.newInstance().assert().isNotNull()
    }

    @Test
    fun newInstanceWithInject() {
        val serviceProvider = SimpleServiceProvider()
        val injectService = InjectService()
        serviceProvider.register(injectService)

        val injectableObjectFactory =
            InjectableObjectFactory(
                MockServiceWithInject::class.java.getConstructor(InjectService::class.java),
                serviceProvider,
            )
        val mockServiceWithInject = injectableObjectFactory.newInstance()
        mockServiceWithInject.assert().isNotNull()
        mockServiceWithInject.injectService.assert().isEqualTo(injectService)
    }
}

internal class MockServiceWithoutParameter

internal class MockServiceWithInject(val injectService: InjectService)

internal class InjectService
