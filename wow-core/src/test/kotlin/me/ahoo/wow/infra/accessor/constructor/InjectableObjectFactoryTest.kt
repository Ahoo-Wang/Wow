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

import me.ahoo.wow.ioc.SimpleServiceProvider
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

internal class InjectableObjectFactoryTest {

    @Test
    fun newInstance() {
        val injectableObjectFactory =
            InjectableObjectFactory(MockServiceWithoutParameter::class.java.getConstructor(), SimpleServiceProvider())
        assertThat(injectableObjectFactory.newInstance(), notNullValue())
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
        assertThat(mockServiceWithInject, notNullValue())
        assertThat(mockServiceWithInject.injectService, equalTo(injectService))
    }
}

internal class MockServiceWithoutParameter

internal class MockServiceWithInject(val injectService: InjectService)

internal class InjectService
