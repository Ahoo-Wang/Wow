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

package me.ahoo.wow.spring.boot.starter.command

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import jakarta.validation.Validator
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.DefaultRequestIdChecker
import me.ahoo.wow.command.RequestIdChecker
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.EventHandledNotifierFilter
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.ProjectedNotifierFilter
import me.ahoo.wow.command.wait.SagaHandledNotifierFilter
import me.ahoo.wow.command.wait.SnapshotNotifierFilter
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.eventsourcing.NoopRequestIdExistenceChecker
import me.ahoo.wow.eventsourcing.RequestIdExistenceChecker
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@AutoConfiguration
@ConditionalOnWowEnabled
class CommandGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        value = [IdempotencyProperties.PREFIX + ENABLED_SUFFIX_KEY],
        matchIfMissing = false,
        havingValue = "false",
    )
    fun noOpIdempotencyCheckerProvider(): AggregateIdempotencyCheckerProvider {
        return DefaultAggregateIdempotencyCheckerProvider { NoOpIdempotencyChecker }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        value = [IdempotencyProperties.PREFIX + ENABLED_SUFFIX_KEY],
        matchIfMissing = true,
        havingValue = "true",
    )
    fun idempotencyChecker(commandProperties: CommandProperties): AggregateIdempotencyCheckerProvider {
        val bloomFilter = commandProperties.idempotency.bloomFilter
        return DefaultAggregateIdempotencyCheckerProvider {
            BloomFilterIdempotencyChecker(bloomFilter.ttl) {
                BloomFilter.create(
                    Funnels.stringFunnel(Charsets.UTF_8),
                    bloomFilter.expectedInsertions,
                    bloomFilter.fpp,
                )
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun requestIdChecker(
        idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider,
        requestIdExistenceCheckerProvider: ObjectProvider<RequestIdExistenceChecker>,
    ): RequestIdChecker {
        return DefaultRequestIdChecker(
            idempotencyCheckerProvider = idempotencyCheckerProvider,
            requestIdExistenceChecker = requestIdExistenceCheckerProvider.getIfAvailable {
                NoopRequestIdExistenceChecker
            },
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun waitCoordinator(): WaitCoordinator {
        return DefaultWaitCoordinator()
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandWaitEndpoint(hostAddressSupplier: HostAddressSupplier): CommandWaitEndpoint {
        return ServerCommandWaitEndpoint(hostAddressSupplier)
    }

    @Bean
    @ConditionalOnMissingClass("me.ahoo.wow.webflux.route.command.CommandHandlerFunction")
    fun commandWaitNotifier(waitCoordinator: WaitCoordinator): CommandWaitNotifier {
        return LocalCommandWaitNotifier(waitCoordinator)
    }

    @Bean
    fun processedNotifierFilter(commandWaitNotifier: CommandWaitNotifier): ProcessedNotifierFilter {
        return ProcessedNotifierFilter(commandWaitNotifier)
    }

    @Bean
    fun snapshotNotifierFilter(commandWaitNotifier: CommandWaitNotifier): SnapshotNotifierFilter {
        return SnapshotNotifierFilter(commandWaitNotifier)
    }

    @Bean
    fun projectedNotifierFilter(commandWaitNotifier: CommandWaitNotifier): ProjectedNotifierFilter {
        return ProjectedNotifierFilter(commandWaitNotifier)
    }

    @Bean
    fun eventHandledNotifierFilter(commandWaitNotifier: CommandWaitNotifier): EventHandledNotifierFilter {
        return EventHandledNotifierFilter(commandWaitNotifier)
    }

    @Bean
    fun sagaHandledNotifierFilter(commandWaitNotifier: CommandWaitNotifier): SagaHandledNotifierFilter {
        return SagaHandledNotifierFilter(commandWaitNotifier)
    }

    @Suppress("LongParameterList")
    @Bean
    @Primary
    @ConditionalOnMissingBean
    fun commandGateway(
        commandWaitEndpoint: CommandWaitEndpoint,
        commandBus: CommandBus,
        validator: Validator,
        requestIdChecker: RequestIdChecker,
        waitCoordinator: WaitCoordinator,
        commandWaitNotifier: CommandWaitNotifier,
    ): CommandGateway {
        return DefaultCommandGateway(
            commandWaitEndpoint = commandWaitEndpoint,
            commandBus = commandBus,
            validator = validator,
            requestIdChecker = requestIdChecker,
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = commandWaitNotifier,
        )
    }
}
