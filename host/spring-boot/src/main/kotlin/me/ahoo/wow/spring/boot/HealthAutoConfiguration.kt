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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.store.QueryStores
import me.ahoo.wow.runtime.health.ComponentHealth
import me.ahoo.wow.runtime.health.HealthStatus
import me.ahoo.wow.runtime.health.NodeHealth
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.config.configCheck
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.health.contributor.CompositeHealthContributor
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthContributor
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.context.annotation.Bean
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.util.ClassUtils

/**
 * The node's health in Spring Boot Actuator, when the application has Actuator's health support
 * (`spring-boot-starter-actuator`), unless `wow.health.enabled=false` ([WowProperties.Health]):
 *
 * - a [NodeHealth] of the node, sampling from the bean's start to its end (it samples nothing while the node does
 *   not run);
 * - the contributor `wow`: one indicator per component (`node`, `ownership`, `stores`, `topics`, `subscriptions`,
 *   `executor`, `liveness`), each `UP`, `DEGRADED` (Spring Boot's status aggregation leaves it out, so it does not
 *   change a group's status) or `DOWN`, the parts of each in its details; and `queries`, always `UP`, each store's
 *   query bulkhead (its limit and write pressure), which no probe group reads;
 * - `wowReadiness`, [NodeHealth.readiness], and `wowLiveness`, [NodeHealth.liveness], which
 *   [HealthGroupsEnvironmentPostProcessor] adds to Actuator's availability probe groups: `/actuator/health/readiness`
 *   is `readinessState` and `wowReadiness`, `/actuator/health/liveness` is `livenessState` and `wowLiveness`;
 *   `/actuator/health/wow` is the `wow` contributor (a group cannot share its name).
 *
 * What a response shows beyond the status follows Actuator's own rules (`management.endpoint.health.show-details`,
 * `never` by default, and the web exposure): the host exposes no detail on the public port the application did not
 * ask for, and the details hold no message or address, only counts, states and exception class names.
 */
@AutoConfiguration(after = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [HEALTH_INDICATOR])
@ConditionalOnBooleanProperty(name = [HealthGroupsEnvironmentPostProcessor.ENABLED], matchIfMissing = true)
class HealthAutoConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowNodeHealth(node: ServiceNode, properties: WowProperties, sampler: NodeSampler): NodeHealth {
        val parkedDegradedAt = properties.health.subscriptions.parkedDegradedAt
        configCheck(
            parkedDegradedAt <= sampler.maxParked,
            "wow.health.subscriptions.parked-degraded-at",
            parkedDegradedAt,
            "Set it to at most wow.node.max-parked (${sampler.maxParked}), which the node counts parked entries up to.",
        ) { "wow.health.subscriptions.parked-degraded-at is $parkedDegradedAt, past wow.node.max-parked." }
        return NodeHealth(node, sampler, properties.health.options(properties.node.sampleInterval))
    }

    /**
     * `wow`: every component of the node's health, and `queries`, the stores' query bulkheads, when
     * the node queries its stores: always `UP`, its details each store's limit and write pressure.
     */
    @Bean(WOW_CONTRIBUTOR)
    @InternalWowApi
    fun wowHealthContributor(health: NodeHealth, queries: ObjectProvider<QueryStores>): HealthContributor {
        val components: MutableMap<String, HealthContributor> = COMPONENTS.associateWithTo(LinkedHashMap()) { name ->
            HealthIndicator { toHealth(health.health().components.first { it.name == name }) }
        }
        queries.ifAvailable?.takeIf { it.all.isNotEmpty() }?.let { stores ->
            components[QUERIES] = HealthIndicator { queriesHealth(stores) }
        }
        return CompositeHealthContributor.fromMap(components)
    }

    /** `wowReadiness`: whether the node can serve traffic. */
    @Bean(READINESS_INDICATOR)
    @InternalWowApi
    fun wowReadinessHealthIndicator(health: NodeHealth): HealthIndicator =
        HealthIndicator { toHealth(health.readiness()) }

    /** `wowLiveness`: whether the node is stuck. */
    @Bean(LIVENESS_INDICATOR)
    @InternalWowApi
    fun wowLivenessHealthIndicator(health: NodeHealth): HealthIndicator =
        HealthIndicator { toHealth(health.liveness()) }

    @InternalWowApi
    companion object {
        const val WOW_CONTRIBUTOR: String = "wowHealthContributor"
        const val READINESS_INDICATOR: String = "wowReadinessHealthIndicator"
        const val LIVENESS_INDICATOR: String = "wowLivenessHealthIndicator"

        /** Wow's status between `UP` and `DOWN`, which Actuator's default aggregation does not order. */
        val DEGRADED: Status = Status("DEGRADED")

        private val COMPONENTS = listOf(
            NodeHealth.NODE,
            NodeHealth.OWNERSHIP,
            NodeHealth.STORES,
            NodeHealth.TOPICS,
            NodeHealth.SUBSCRIPTIONS,
            NodeHealth.EXECUTOR,
            NodeHealth.LIVENESS,
        )

        /** The `queries` component of `wow`; informative, so it never takes a group down. */
        const val QUERIES: String = "queries"

        /** Each store's query bulkhead: its limit, what runs and waits, and, when it adapts, its write pressure. */
        fun queriesHealth(stores: QueryStores): Health {
            val limits = stores.limits.associateBy { it.bulkhead.store }
            val details = stores.all.associateBy { it.store }.toSortedMap().mapValues { (name, bulkhead) ->
                val details = linkedMapOf<String, Any>(
                    "limit" to bulkhead.limit,
                    "ceiling" to bulkhead.ceiling,
                    "running" to bulkhead.running,
                    "queued" to bulkhead.queued,
                    "adaptive" to (name in limits),
                )
                limits[name]?.let { limit ->
                    details["floor"] = limit.floor
                    details["pressure"] = limit.pressed
                    details["decreases"] = limit.decreases
                    limit.lastSample.appendP90?.let { details["appendP90Millis"] = it.inWholeMilliseconds }
                    limit.lastSample.baseline?.let { details["appendBaselineMillis"] = it.inWholeMilliseconds }
                    details["waitingForConnection"] = limit.lastSample.waitingForConnection
                }
                details
            }
            return Health.up().withDetails(details).build()
        }

        /** [component] as Actuator's health: its status, its details, and each part's status and details under its name. */
        fun toHealth(component: ComponentHealth): Health {
            val details = LinkedHashMap<String, Any?>(component.details)
            component.components.forEach { details[it.name] = detailsOf(it) }
            return Health.status(statusOf(component.status)).withDetails(details.filterValues { it != null }).build()
        }

        private fun detailsOf(component: ComponentHealth): Map<String, Any?> {
            val details = linkedMapOf<String, Any?>("status" to component.status.name)
            details += component.details.filterValues { it != null }
            component.components.forEach { details[it.name] = detailsOf(it) }
            return details
        }

        fun statusOf(status: HealthStatus): Status = when (status) {
            HealthStatus.UP -> Status.UP
            HealthStatus.DEGRADED -> DEGRADED
            HealthStatus.DOWN -> Status.DOWN
        }
    }
}

/**
 * Adds the node's health to Actuator's groups, as defaults the application's own settings replace:
 * `management.endpoint.health.group.readiness.include=readinessState,wowReadiness`,
 * and `management.endpoint.health.group.liveness.include=livenessState,wowLiveness`. Only when [HealthAutoConfiguration] contributes them: Actuator
 * and its health support on the class path, `wow.health.enabled` not false, and the availability probes not turned
 * off (`management.endpoint.health.probes.enabled`), whose `readinessState` and `livenessState` the groups name.
 */
internal class HealthGroupsEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val classLoader = application.classLoader
        if (!ClassUtils.isPresent(HEALTH_INDICATOR, classLoader) || !ClassUtils.isPresent(ENDPOINT, classLoader)) {
            return
        }
        if (!WowProperties.of(environment).health.enabled ||
            environment.getProperty(PROBES, Boolean::class.java, true) == false
        ) {
            return
        }
        val defaults = DEFAULTS.filterKeys { !environment.containsProperty(it) }
        if (defaults.isNotEmpty()) {
            environment.propertySources.addLast(MapPropertySource(SOURCE, defaults))
        }
    }

    companion object {
        const val SOURCE: String = "wowHealthGroups"
        const val ENABLED: String = "wow.health.enabled"
        const val PROBES: String = "management.endpoint.health.probes.enabled"
        private const val ENDPOINT = "org.springframework.boot.actuate.endpoint.annotation.Endpoint"
        private const val GROUP = "management.endpoint.health.group"

        /** The members of the probe groups, unless the application names them. */
        val DEFAULTS: Map<String, Any> = linkedMapOf(
            "$GROUP.readiness.include" to "readinessState,wowReadiness",
            "$GROUP.liveness.include" to "livenessState,wowLiveness",
        )
    }
}

/** Actuator's health support, present only when the application has it. */
internal const val HEALTH_INDICATOR = "org.springframework.boot.health.contributor.HealthIndicator"
