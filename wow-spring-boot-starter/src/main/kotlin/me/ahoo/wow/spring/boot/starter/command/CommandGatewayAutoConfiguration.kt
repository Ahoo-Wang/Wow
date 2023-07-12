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
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.ProjectedNotifierFilter
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.SnapshotNotifierFilter
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.commons.util.InetUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@AutoConfiguration
@ConditionalOnWowEnabled
class CommandGatewayAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun noOpValidator(): Validator {
        return NoOpValidator
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        value = [CommandProperties.Idempotency.PREFIX + ENABLED_SUFFIX_KEY],
        matchIfMissing = false,
        havingValue = "false",
    )
    fun noOpIdempotencyChecker(): IdempotencyChecker {
        return NoOpIdempotencyChecker
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        value = [CommandProperties.Idempotency.PREFIX + ENABLED_SUFFIX_KEY],
        matchIfMissing = true,
        havingValue = "true",
    )
    fun idempotencyChecker(commandProperties: CommandProperties): IdempotencyChecker {
        val bloomFilter = commandProperties.idempotency.bloomFilter
        return BloomFilterIdempotencyChecker(bloomFilter.ttl) {
            BloomFilter.create(
                Funnels.stringFunnel(Charsets.UTF_8),
                bloomFilter.expectedInsertions,
                bloomFilter.fpp,
            )
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun waitStrategyRegistrar(): WaitStrategyRegistrar {
        return SimpleWaitStrategyRegistrar
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandWaitEndpoint(inetUtils: InetUtils): CommandWaitEndpoint {
        return ServerCommandWaitEndpoint(inetUtils)
    }

    @Bean
    @ConditionalOnMissingClass("me.ahoo.wow.webflux.route.command.CommandHandlerFunction")
    fun commandWaitNotifier(waitStrategyRegistrar: WaitStrategyRegistrar): CommandWaitNotifier {
        return LocalCommandWaitNotifier(waitStrategyRegistrar)
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

    @Suppress("LongParameterList")
    @Bean
    @Primary
    @ConditionalOnMissingBean
    fun commandGateway(
        commandWaitEndpoint: CommandWaitEndpoint,
        commandBus: CommandBus,
        validator: Validator,
        idempotencyChecker: IdempotencyChecker,
        waitStrategyRegistrar: WaitStrategyRegistrar
    ): CommandGateway {
        return DefaultCommandGateway(
            commandWaitEndpoint = commandWaitEndpoint,
            commandBus = commandBus,
            idempotencyChecker = idempotencyChecker,
            waitStrategyRegistrar = waitStrategyRegistrar,
            validator = validator,
        )
    }
}
