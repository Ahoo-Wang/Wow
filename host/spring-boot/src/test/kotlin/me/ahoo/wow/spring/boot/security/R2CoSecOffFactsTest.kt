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

package me.ahoo.wow.spring.boot.security

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Round two of the deep review, security: a service that turns CoSec off (`wow.cosec.mode: disabled`, which sets
 * `cosec.enabled=false`) with CoSec's modules still on its class path. The CoSec request fact (`CoSec-Device-Id`, a
 * client's persistent device id) is still copied from any client into every command's request facts, the immutable
 * log, though nothing lists it in `wow.http.request-facts` and CoSec does not run.
 */
class R2CoSecOffFactsTest {
    @Configuration(proxyBeanMethods = false)
    class Application {
        @Bean
        fun callerResolver(): CallerResolver = CallerResolver.ANONYMOUS

        @Bean
        fun options(): WowHttpOptions = WowHttpOptions()
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecAutoConfiguration::class.java))
        .withUserConfiguration(Application::class.java)

    @Test
    fun `a service with CoSec off records no CoSec request fact and reads no CoSec request id`() {
        runner.withPropertyValues("cosec.enabled=false").run { context ->
            val options = context.getBean(WowHttpOptions::class.java)
            options.requestFacts.assert().isEmpty()
            options.requestIdHeaders.assert().containsExactly(WowHeaders.REQUEST_ID)
        }
    }
}
