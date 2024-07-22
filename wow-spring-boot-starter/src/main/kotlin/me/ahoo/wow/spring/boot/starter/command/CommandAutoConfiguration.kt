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

import jakarta.validation.Validator
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.command.factory.CommandBuilderRewriter
import me.ahoo.wow.command.factory.CommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@AutoConfiguration
@ConditionalOnWowEnabled
@EnableConfigurationProperties(CommandProperties::class)
class CommandAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        CommandProperties.BUS_TYPE,
        havingValue = BusType.IN_MEMORY_NAME,
    )
    fun inMemoryCommandBus(): LocalCommandBus {
        return InMemoryCommandBus()
    }

    @Bean
    @ConditionalOnMissingBean(LocalCommandBus::class)
    @ConditionalOnBean(value = [DistributedCommandBus::class])
    @ConditionalOnCommandLocalFirstEnabled
    fun localCommandBus(): LocalCommandBus {
        return InMemoryCommandBus()
    }

    @Bean
    @Primary
    @ConditionalOnBean(value = [LocalCommandBus::class, DistributedCommandBus::class])
    @ConditionalOnCommandLocalFirstEnabled
    fun localFirstCommandBus(
        localBus: LocalCommandBus,
        distributedBus: DistributedCommandBus
    ): LocalFirstCommandBus {
        return LocalFirstCommandBus(distributedBus, localBus)
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandOptionsExtractorRegistry(
        extractors: ObjectProvider<CommandBuilderRewriter>
    ): CommandBuilderRewriterRegistry {
        val registry = SimpleCommandBuilderRewriterRegistry()
        extractors.orderedStream().forEach {
            registry.register(it)
        }
        return registry
    }

    @Bean
    @ConditionalOnMissingBean
    fun noOpValidator(): Validator {
        return NoOpValidator
    }

    @Bean
    @ConditionalOnMissingBean
    fun commandMessageFactory(
        validator: Validator,
        commandBuilderRewriterRegistry: CommandBuilderRewriterRegistry
    ): CommandMessageFactory {
        return SimpleCommandMessageFactory(
            validator = validator,
            commandBuilderRewriterRegistry = commandBuilderRewriterRegistry
        )
    }
}
