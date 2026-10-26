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

package me.ahoo.wow.cosid

import me.ahoo.cosid.provider.IdGeneratorProvider
import me.ahoo.wow.spi.id.GlobalIdGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Gives the Wow host CosId's id generators (decision D171) when the application runs CosId (it has CosId's
 * [IdGeneratorProvider] bean). This module lists it in
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; its ordering (after CosId's
 * auto-configuration, before the host's `WowAutoConfiguration`) is in
 * `META-INF/spring-autoconfigure-metadata.properties`, so that it imports no Spring Boot type.
 *
 * - [CosIdGlobalIdGenerator] is the host's global generator, of CosId's generator named `wow.cosid.global-generator`
 *   (`cosid` by default), unless the application has a [GlobalIdGenerator] of its own.
 * - [CosIdAggregateIdGenerator] is the host's `wowAggregateIdGenerator`, the generator of every aggregate type that
 *   `wow.aggregates.*.id-generator` does not name, unless the application has a bean of that name; as an
 *   [me.ahoo.wow.spi.id.AggregateIdGeneratorSource] it lets `id-generator` name CosId's generators. Its machine id is
 *   that of CosId's global generator, or of its shared one.
 */
@Configuration(proxyBeanMethods = false)
class WowCosIdAutoConfiguration {
    @Bean
    @Conditional(NoGlobalIdGenerator::class)
    fun wowCosIdGlobalIdGenerator(provider: IdGeneratorProvider, environment: Environment): CosIdGlobalIdGenerator =
        CosIdGlobalIdGenerator(provider, globalName(environment))

    @Bean(AGGREGATE_ID_GENERATOR)
    @Conditional(NoAggregateIdGenerator::class)
    fun wowCosIdAggregateIdGenerator(
        provider: IdGeneratorProvider,
        environment: Environment,
    ): CosIdAggregateIdGenerator {
        val global = CosIdGlobalIdGenerator(provider, globalName(environment))
        return CosIdAggregateIdGenerator(provider) { CosIdAggregateIdGenerator.machineIdOf(global.generator) }
    }

    /** The application runs CosId, and has no [GlobalIdGenerator] of its own. */
    internal class NoGlobalIdGenerator : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            return runsCosId(context) && beans.getBeanNamesForType(GlobalIdGenerator::class.java, true, false).isEmpty()
        }
    }

    /** The application runs CosId, and has no bean named [AGGREGATE_ID_GENERATOR]. */
    internal class NoAggregateIdGenerator : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            return runsCosId(context) && !beans.containsBean(AGGREGATE_ID_GENERATOR)
        }
    }

    companion object {
        /** The name of the CosId generator of the global ids, [CosIdProperties.GLOBAL_GENERATOR]. */
        const val GLOBAL_GENERATOR: String = CosIdProperties.GLOBAL_GENERATOR

        /** The host's bean of the aggregate types no `id-generator` names (`WowAutoConfiguration`'s). */
        const val AGGREGATE_ID_GENERATOR: String = "wowAggregateIdGenerator"

        /**
         * The CosId generator of the global ids: `wow.cosid.global-generator`, read from the environment since the
         * module imports no Spring Boot (decision D118), else [CosIdProperties]' default, its one default.
         */
        internal fun globalName(environment: Environment): String =
            environment.getProperty(CosIdProperties.GLOBAL_GENERATOR)?.takeIf { it.isNotBlank() }
                ?: CosIdProperties().globalGenerator

        private fun runsCosId(context: ConditionContext): Boolean =
            context.beanFactory?.getBeanNamesForType(IdGeneratorProvider::class.java, true, false)
                .orEmpty().isNotEmpty()
    }
}
