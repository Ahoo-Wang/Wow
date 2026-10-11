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
import me.ahoo.wow.cosec.abac.CoSecAbacQueryPolicy
import me.ahoo.wow.cosec.appender.CoSecCommandRequestHeaderAppender
import me.ahoo.wow.cosec.identity.CoSecIdentityHeaders
import me.ahoo.wow.query.snapshot.filter.AbacQueryOptions
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration
import me.ahoo.wow.webflux.route.identity.IdentityHeaderAliases
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CoSecAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    /**
     * WebFlux registers its defaults with `@ConditionalOnMissingBean`, so CoSec must be processed first. That must
     * be declared, not left to Spring Boot's alphabetical pre-sort of auto-configuration class names.
     */
    @Test
    fun `should be ordered before WebFlux explicitly`() {
        CoSecAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)
            .before.toList().assert().contains(WebFluxAutoConfiguration::class)
    }

    /**
     * Since 9.3.0 CoSec contributes `CoSec-Space-Id` / `CoSec-Request-Id` as identity header aliases, read by the
     * default command builder extractor and query request scope, instead of overriding those two SPIs.
     */
    @Test
    fun `should load context with cosec beans`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(CoSecAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(CoSecCommandRequestHeaderAppender::class.java)
                    .hasSingleBean(IdentityHeaderAliases::class.java)
                context.getBean(IdentityHeaderAliases::class.java).assert().isSameAs(CoSecIdentityHeaders.ALIASES)
            }
    }

    private fun abac(vararg properties: String) = contextRunner
        .enableWow()
        .withPropertyValues(*properties)
        .withBean(AbacQueryOptions::class.java, { AbacQueryOptions.DEFAULT })
        .withUserConfiguration(CoSecAutoConfiguration::class.java)

    @Test
    fun `reads the principal's ABAC tags from CoSec only when asked`() {
        abac().run { context -> context.assert().doesNotHaveBean(AbacQueryPolicy::class.java) }
        abac("wow.cosec.abac.enabled=true", "wow.cosec.abac.attribute-prefix=tag:").run { context ->
            context.assert().hasSingleBean(AbacQueryPolicy::class.java)
            val policy = context.getBean(AbacQueryPolicy::class.java)
            policy.assert().isInstanceOf(CoSecAbacQueryPolicy::class.java)
            (policy as CoSecAbacQueryPolicy).attributePrefix.assert().isEqualTo("tag:")
            policy.options.assert().isSameAs(AbacQueryOptions.DEFAULT)
        }
    }

    @Test
    fun `gives way to the application's policy and to a host without CoSec`() {
        val own = CoSecAbacQueryPolicy(attributePrefix = "own.")
        abac("wow.cosec.abac.enabled=true")
            .withBean(AbacQueryPolicy::class.java, { own })
            .run { context -> context.getBean(AbacQueryPolicy::class.java).assert().isSameAs(own) }
        abac("wow.cosec.abac.enabled=true")
            .withClassLoader(FilteredClassLoader("me.ahoo.cosec.webflux"))
            .run { context -> context.assert().doesNotHaveBean(AbacQueryPolicy::class.java) }
    }
}
