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
import me.ahoo.wow.ioc.register
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InjectableObjectFactoryTest {

    @Test
    fun `should resolve constructor arguments from service provider`() {
        val serviceProvider = SimpleServiceProvider()
        val dependency = FactoryDependency("resolved")
        serviceProvider.register<FactoryDependency>(dependency)
        val constructor = FactoryCreatedObject::class.java.getConstructor(FactoryDependency::class.java)
        val factory = InjectableObjectFactory(constructor, serviceProvider)

        val created = factory.newInstance()

        created.dependency.assert().isSameAs(dependency)
    }

    @Test
    fun `should fail with service lookup exception when dependency is missing`() {
        val constructor = FactoryCreatedObject::class.java.getConstructor(FactoryDependency::class.java)
        val factory = InjectableObjectFactory(constructor, SimpleServiceProvider())

        assertThrows<IllegalArgumentException> {
            factory.newInstance()
        }.message!!.startsWith("Service[").assert().isTrue()
    }
}

private data class FactoryDependency(val value: String)

private class FactoryCreatedObject(val dependency: FactoryDependency)
