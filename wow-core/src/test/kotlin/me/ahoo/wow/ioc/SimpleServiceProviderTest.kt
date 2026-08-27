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

package me.ahoo.wow.ioc

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SimpleServiceProviderTest {

    @Test
    fun `should register and look up services by exact type subtype and name`() {
        val service = DefaultFoundationService("primary")
        val provider = SimpleServiceProvider()

        provider.register<FoundationService>(service, "foundation")

        provider.serviceNames.assert().contains("foundation")
        provider.getRequiredService<FoundationService>().assert().isSameAs(service)
        provider.getRequiredService<FoundationService>("foundation").assert().isSameAs(service)

        val subtypeProvider = SimpleServiceProvider()
        subtypeProvider.register(service, "defaultFoundation")
        subtypeProvider.getRequiredService<FoundationService>().assert().isSameAs(service)
    }

    @Test
    fun `should look up mockk services through java instance fallback`() {
        val service = mockk<MockkFoundationService>()
        val provider = SimpleServiceProvider()

        provider.register(service)

        provider.getService<MockkFoundationService>().assert().isNotNull()
    }

    @Test
    fun `should copy registered services to independent providers`() {
        val service = DefaultFoundationService("copy")
        val provider = SimpleServiceProvider()
        provider.register<FoundationService>(service, "foundation")

        val copied = provider.copy()
        val target = SimpleServiceProvider()
        copied.copyTo(target)

        copied.getRequiredService<FoundationService>("foundation").assert().isSameAs(service)
        target.getRequiredService<FoundationService>("foundation").assert().isSameAs(service)
    }

    @Test
    fun `should throw current exception type and message for missing services`() {
        val provider = SimpleServiceProvider()

        assertThrows<IllegalArgumentException> {
            provider.getRequiredService<FoundationService>("missing")
        }.message.assert().isEqualTo("Service[missing] not found.")
    }
}

private interface FoundationService {
    val name: String
}

private data class DefaultFoundationService(override val name: String) : FoundationService

private interface MockkFoundationService
