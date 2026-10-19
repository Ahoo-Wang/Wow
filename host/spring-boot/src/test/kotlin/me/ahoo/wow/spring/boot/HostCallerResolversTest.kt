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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.InternalCallerResolver
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A public port's resolver beside an internal one, as CoSec's two are (review X-13): the host starts, each port takes
 * its own, and the public port never answers by the internal one.
 */
class HostCallerResolversTest {
    @Configuration(proxyBeanMethods = false)
    class TwoResolvers {
        @Bean
        fun publicCallers(): CallerResolver = CallerResolver {
            Caller(
                tenantId = Caller.DEFAULT_TENANT,
                subject = "alice"
            )
        }

        /** Authenticates no one: a public request it answered would be 401. */
        @Bean
        fun internalCallers(): InternalCallerResolver = InternalCallerResolver { null }
    }

    @Test
    fun `the host should start with both, the public port served by the public resolver`() {
        startHost(sources = listOf(HostTestApp::class.java, TwoResolvers::class.java)).use { host ->
            host.context.getBeansOfType(CallerResolver::class.java).keys.assert().containsExactly("publicCallers")
            host.post("/bank/account/open-account", """{"owner":"alice"}""").status.assert().isEqualTo(200)
        }
    }
}
