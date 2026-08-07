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

package me.ahoo.wow.spring.boot.starter.query

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Primary
import org.springframework.core.type.AnnotatedTypeMetadata

internal const val QUERY_GATEWAY_LEGACY_WIRING_ROLLBACK_KEY =
    "wow.query.gateway.legacy-wiring-rollback"

/**
 * One-version emergency rollback for the application query facade wiring.
 *
 * The switch is deliberately explicit and observable. It only bypasses the Gateway facade; authorization, policy,
 * schema, lowering, or mapping failures never select this path automatically.
 */
@AutoConfiguration(after = [QueryGatewayAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnLegacyQueryGatewayWiringRollback
internal class QueryGatewayLegacyWiringRollbackAutoConfiguration {
    @Bean
    internal fun queryLegacyWiringRollback(
        rawServiceSource: StorageBindingQueryRawServiceRegistry,
        meterRegistries: ObjectProvider<MeterRegistry>,
    ): QueryLegacyWiringRollback = QueryLegacyWiringRollback(rawServiceSource, meterRegistries.getIfAvailable())

    @Bean
    @Primary
    fun legacyWiringSnapshotQueryServiceFactory(
        rollback: QueryLegacyWiringRollback,
    ): SnapshotQueryServiceFactory = rollback.snapshotFactory

    @Bean
    @Primary
    fun legacyWiringEventStreamQueryServiceFactory(
        rollback: QueryLegacyWiringRollback,
    ): EventStreamQueryServiceFactory = rollback.eventStreamFactory
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(OnQueryGatewayWiringCondition::class)
internal annotation class ConditionalOnQueryGatewayWiring

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(OnLegacyQueryGatewayWiringRollbackCondition::class)
internal annotation class ConditionalOnLegacyQueryGatewayWiringRollback

internal class OnQueryGatewayWiringCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: org.springframework.context.annotation.ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome = wiringOutcome(!context.rollbackEnabled(), "Query Gateway")
}

internal class OnLegacyQueryGatewayWiringRollbackCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: org.springframework.context.annotation.ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome = wiringOutcome(context.rollbackEnabled(), "legacy query wiring rollback")
}

private fun org.springframework.context.annotation.ConditionContext.rollbackEnabled(): Boolean {
    val configured = environment.getProperty(QUERY_GATEWAY_LEGACY_WIRING_ROLLBACK_KEY) ?: return false
    return when (configured.lowercase()) {
        "true" -> true
        "false" -> false
        else -> error(
            "Property[$QUERY_GATEWAY_LEGACY_WIRING_ROLLBACK_KEY] must be exactly true or false, but was [$configured].",
        )
    }
}

private fun wiringOutcome(matched: Boolean, mode: String): ConditionOutcome =
    if (matched) {
        ConditionOutcome.match("$mode is selected.")
    } else {
        ConditionOutcome.noMatch("$mode is not selected.")
    }

internal class QueryLegacyWiringRollback(
    rawServiceSource: StorageBindingQueryRawServiceRegistry,
    meterRegistry: MeterRegistry?,
) {
    val snapshotFactory: SnapshotQueryServiceFactory = object : SnapshotQueryServiceFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> =
            rawServiceSource.snapshot(namedAggregate) as SnapshotQueryService<S>
    }

    val eventStreamFactory: EventStreamQueryServiceFactory = EventStreamQueryServiceFactory { namedAggregate ->
        rawServiceSource.eventStream(namedAggregate)
    }

    init {
        LOG.warn {
            "Query Gateway wiring rollback is enabled by [$QUERY_GATEWAY_LEGACY_WIRING_ROLLBACK_KEY]. " +
                "Framework-managed query services are bypassing admission, policy, and lifecycle enforcement. " +
                "This emergency switch is supported for one migration version only."
        }
        meterRegistry?.counter(ROLLBACK_METRIC)?.increment()
    }

    private companion object {
        const val ROLLBACK_METRIC = "wow.query.gateway.legacy.wiring.rollback"
        val LOG = KotlinLogging.logger {}
    }
}
