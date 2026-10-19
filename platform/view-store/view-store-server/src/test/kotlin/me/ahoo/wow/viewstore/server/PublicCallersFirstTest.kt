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

package me.ahoo.wow.viewstore.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.InternalCallerResolver
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.web.reactive.function.server.ServerRequest

class PublicCallersFirstTest {
    private fun beans(internal: Boolean, publicCallers: Int): DefaultListableBeanFactory {
        val beans = DefaultListableBeanFactory()
        if (internal) {
            beans.registerBeanDefinition("internal", RootBeanDefinition(InternalCallers::class.java))
        }
        repeat(
            publicCallers
        ) { beans.registerBeanDefinition("public-$it", RootBeanDefinition(PublicCallers::class.java)) }
        PublicCallersFirst().postProcessBeanFactory(beans)
        return beans
    }

    private fun DefaultListableBeanFactory.primary(): List<String> =
        beanDefinitionNames.filter { getBeanDefinition(it).isPrimary }

    @Test
    fun `the one public resolver beside an internal one should be primary`() {
        beans(internal = true, publicCallers = 1).primary().assert().containsExactly("public-0")
    }

    @Test
    fun `nothing should be primary without an internal resolver, or without a single public one`() {
        beans(internal = false, publicCallers = 1).primary().assert().isEmpty()
        beans(internal = true, publicCallers = 0).primary().assert().isEmpty()
        beans(internal = true, publicCallers = 2).primary().assert().isEmpty()
    }

    class PublicCallers : CallerResolver by CallerResolver.ANONYMOUS

    class InternalCallers : InternalCallerResolver {
        override suspend fun resolve(request: ServerRequest): Caller? = null
    }
}
