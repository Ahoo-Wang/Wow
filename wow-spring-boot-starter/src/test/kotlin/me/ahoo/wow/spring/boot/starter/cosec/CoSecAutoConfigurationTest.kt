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

package me.ahoo.wow.spring.boot.starter.cosec

import me.ahoo.test.asserts.assert
import me.ahoo.wow.cosec.appender.CoSecCommandRequestHeaderAppender
import me.ahoo.wow.cosec.extractor.CoSecCommandBuilderExtractor
import me.ahoo.wow.cosec.query.CoSecQueryPolicy
import me.ahoo.wow.cosec.query.CoSecRewriteRequestCondition
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.webflux.route.query.WebFluxQueryAuthorityResolver
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

class CoSecAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with cosec beans`() {
        contextRunner
            .enableWow()
            .withBean(WebFluxQueryAuthorityResolver::class.java, { trustedAuthorityResolver })
            .withUserConfiguration(CoSecAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(CoSecCommandRequestHeaderAppender::class.java)
                    .hasSingleBean(CoSecCommandBuilderExtractor::class.java)
                    .hasSingleBean(CoSecRewriteRequestCondition::class.java)
                    .hasSingleBean(CoSecQueryPolicy::class.java)
            }
    }

    @Test
    fun `should fail startup with the subject only authority resolver`() {
        contextRunner
            .enableWow()
            .withBean(
                WebFluxQueryAuthorityResolver::class.java,
                { WebFluxQueryAuthorityResolver.SUBJECT },
            )
            .withUserConfiguration(CoSecAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.message.assert().contains("trusted query authority")
            }
    }

    @Test
    fun `should keep cosec policy without a webflux query resolver`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(CoSecAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.startupFailure.assert().isNull()
                context.assert().hasSingleBean(CoSecQueryPolicy::class.java)
            }
    }

    @Test
    fun `retained CoSec rewrite registration method is deprecated`() {
        val method = CoSecAutoConfiguration::class.java.getDeclaredMethod("coSecRewriteRequestCondition")

        method.isAnnotationPresent(kotlin.Deprecated::class.java).assert().isTrue()
    }

    private companion object {
        val trustedAuthorityResolver = WebFluxQueryAuthorityResolver {
            Mono.just(QueryAuthorityView("subject", "tenant", null, setOf("space"), emptySet()))
        }
    }
}
