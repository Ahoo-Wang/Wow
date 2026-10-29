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

import me.ahoo.cosid.runtime.CosIdRuntime
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Gives the Wow host CosId's aggregate id generators when the application runs CosId (it has CosId's
 * [CosIdRuntime] bean, which CosId's starter starts from `cosid.*`). This module lists it in
 * `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; its ordering (after CosId's
 * auto-configuration, before the host's `WowAutoConfiguration`) is in
 * `META-INF/spring-autoconfigure-metadata.properties`, so that it imports no Spring Boot type.
 *
 * [CosIdAggregateIdGenerator] is the host's `wowAggregateIdGenerator`, unless the application has a bean of that name:
 * the generator of every aggregate type that `wow.aggregates.*.id-generator` does not name, CosId's generator named
 * after the aggregate, else `wow.cosid.aggregate-generator`. As an [me.ahoo.wow.spi.id.AggregateIdGeneratorSource] it
 * lets `id-generator` name CosId's generators. The node's own ids stay the host's (UUIDv7): a CosId id is unique only
 * within its namespace, and request ids from several services meet in one context's idempotency key.
 */
@Configuration(proxyBeanMethods = false)
class WowCosIdAutoConfiguration {
    @Bean(AGGREGATE_ID_GENERATOR)
    @Conditional(NoAggregateIdGenerator::class)
    fun wowCosIdAggregateIdGenerator(runtime: CosIdRuntime, environment: Environment): CosIdAggregateIdGenerator =
        CosIdAggregateIdGenerator(runtime, aggregateGeneratorOf(environment))

    /** The application runs CosId, and has no bean named [AGGREGATE_ID_GENERATOR]. */
    internal class NoAggregateIdGenerator : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val beans = context.beanFactory ?: return false
            return runsCosId(context) && !beans.containsBean(AGGREGATE_ID_GENERATOR)
        }
    }

    companion object {
        /** The key of the CosId generator of the aggregate types that have none of their own. */
        const val AGGREGATE_GENERATOR: String = CosIdProperties.AGGREGATE_GENERATOR

        /** The host's bean of the aggregate types no `id-generator` names (`WowAutoConfiguration`'s). */
        const val AGGREGATE_ID_GENERATOR: String = "wowAggregateIdGenerator"

        /**
         * The CosId generator of the aggregate types that have none of their own: `wow.cosid.aggregate-generator`,
         * read from the environment since the module imports no Spring Boot, else [CosIdProperties]' default.
         */
        internal fun aggregateGeneratorOf(environment: Environment): String =
            environment.getProperty(CosIdProperties.AGGREGATE_GENERATOR)?.takeIf { it.isNotBlank() }
                ?: CosIdProperties().aggregateGenerator

        private fun runsCosId(context: ConditionContext): Boolean =
            context.beanFactory?.getBeanNamesForType(CosIdRuntime::class.java, true, false)
                .orEmpty().isNotEmpty()
    }
}
