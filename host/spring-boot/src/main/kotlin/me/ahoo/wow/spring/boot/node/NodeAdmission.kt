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

package me.ahoo.wow.spring.boot.node

import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.command.JakartaCommandValidator
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.WritePolicies
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.AggregateIdGeneratorSource
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.spring.boot.WowAutoConfiguration
import me.ahoo.wow.spring.boot.WowAutoConfiguration.Companion.WOW_AGGREGATE_ID_GENERATOR
import me.ahoo.wow.spring.boot.WowProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import jakarta.validation.Validator as JakartaValidator

/**
 * What the node admits a command by: its ids, the node's global ones and each aggregate type's ([GlobalIdGenerator],
 * [AggregateIdGenerator]), the Jakarta validator when there is a Bean Validation provider, the fixed tenants
 * ([TenantPolicy]) and the access policies its commands are held to ([WritePolicies]). Imported by
 * [WowAutoConfiguration], whose order and conditions it shares.
 */
@Configuration(proxyBeanMethods = false)
internal class NodeAdmission {
    /** The node's global ids: command, request and wait ids, and the ids domain code takes. */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowGlobalIdGenerator(): GlobalIdGenerator = GlobalIdGenerator.UUID_V7

    /** The ids of new aggregates of every type no `wow.aggregates.*.id-generator` names. */
    @Bean(WOW_AGGREGATE_ID_GENERATOR)
    @ConditionalOnMissingBean(name = [WOW_AGGREGATE_ID_GENERATOR])
    @InternalWowApi
    fun wowAggregateIdGenerator(global: GlobalIdGenerator): AggregateIdGenerator = AggregateIdGenerator.of(global)

    /** Activated when the application has a Bean Validation provider. */
    @Configuration(proxyBeanMethods = false)
    @InternalWowApi
    @ConditionalOnClass(name = [JAKARTA_VALIDATOR])
    class JakartaValidation {
        @Bean
        @ConditionalOnBean(type = [JAKARTA_VALIDATOR])
        @ConditionalOnMissingBean
        @InternalWowApi
        fun wowJakartaCommandValidator(validator: ObjectProvider<JakartaValidator>): JakartaCommandValidator =
            JakartaCommandValidator(validator.getObject())
    }

    internal companion object {
        private const val JAKARTA_VALIDATOR = "jakarta.validation.Validator"

        private fun servedFix(served: Set<String>): String = "Name one of the aggregate types this node serves: $served."

        /**
         * The fixed tenants of `wow.aggregates.[<context>.<aggregate>].tenant`, each of an aggregate
         * type of [models]: a key that names no such type, or a blank tenant, is refused.
         */
        internal fun tenants(properties: WowProperties, models: List<AggregateModel<*>>): TenantPolicy {
            val served = models.associateBy { "${it.type.context}.${it.type.name}" }
            val fixed = properties.aggregates.mapNotNull { (key, aggregate) ->
                aggregate.tenant?.let { tenant ->
                    val model = configNotNull(served[key], "wow.aggregates.[$key]", servedFix(served.keys)) {
                        "wow.aggregates.[$key].tenant names no aggregate type this node serves."
                    }
                    configCheck(tenant.isNotBlank(), "wow.aggregates.[$key].tenant", tenant, "Name the tenant.") {
                        "wow.aggregates.[$key].tenant is blank."
                    }
                    model.type to tenant
                }
            }.toMap()
            return TenantPolicy.fixed(fixed)
        }

        /**
         * The generator of each aggregate type's new ids: for each type of [models] that
         * `wow.aggregates.[<context>.<aggregate>].id-generator` names one for, the one [lookup] finds by that name;
         * [default] for the others. A key that names no such type, a blank name, or a name [lookup] finds nothing by
         * is refused.
         */
        internal fun aggregateIdGenerators(
            properties: WowProperties,
            models: List<AggregateModel<*>>,
            default: AggregateIdGenerator,
            lookup: (String) -> AggregateIdGenerator?,
        ): AggregateIdGenerator {
            val served = models.associateBy { "${it.type.context}.${it.type.name}" }
            val selected = properties.aggregates.mapNotNull { (key, aggregate) ->
                aggregate.idGenerator?.let { name ->
                    val model = configNotNull(served[key], "wow.aggregates.[$key]", servedFix(served.keys)) {
                        "wow.aggregates.[$key].id-generator names no aggregate type this node serves."
                    }
                    val idGenerator = "wow.aggregates.[$key].id-generator"
                    val generatorFix =
                        "Name an AggregateIdGenerator bean, or a generator an AggregateIdGeneratorSource bean finds."
                    configCheck(name.isNotBlank(), idGenerator, name, generatorFix) { "$idGenerator is blank." }
                    val generator = configNotNull(lookup(name), idGenerator, generatorFix, name) {
                        "$idGenerator names $name, which is no AggregateIdGenerator bean and no generator an " +
                            "AggregateIdGeneratorSource bean finds."
                    }
                    model.type to generator
                }
            }.toMap()
            return AggregateIdGenerator.of(default, selected)
        }

        /** The [AggregateIdGenerator] bean of a name, else the generator the first of [sources] finds by it. */
        internal fun idGeneratorLookup(
            beanFactory: ConfigurableListableBeanFactory,
            sources: List<AggregateIdGeneratorSource>,
        ): (String) -> AggregateIdGenerator? = { name ->
            if (name in beanFactory.getBeanNamesForType(AggregateIdGenerator::class.java)) {
                beanFactory.getBean(name, AggregateIdGenerator::class.java)
            } else {
                sources.firstNotNullOfOrNull { it.find(name) }
            }
        }

        /**
         * The access policies the node's commands are held to: the ones its queries read by, resolved alike. A node of
         * some of the service's contexts leaves the policies of the others' aggregates to theirs.
         */
        internal fun writePolicies(
            properties: WowProperties,
            models: WowModels,
            access: ObjectProvider<AccessPolicy<*>>,
        ): WritePolicies =
            WritePolicies.of(
                access.orderedStream().toList(),
                models.models,
                strict = properties.contexts.selection().isAll
            )
    }
}
