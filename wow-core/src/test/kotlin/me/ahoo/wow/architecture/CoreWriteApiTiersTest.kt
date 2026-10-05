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

package me.ahoo.wow.architecture

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.command.kernel.AggregateModel
import me.ahoo.wow.command.kernel.BuiltInCommandEntry
import me.ahoo.wow.command.kernel.CommandEntry
import me.ahoo.wow.command.kernel.CompiledFunction
import me.ahoo.wow.command.kernel.FunctionCommandEntry
import me.ahoo.wow.command.kernel.InjectedParameter
import me.ahoo.wow.command.kernel.SourcingFunction
import me.ahoo.wow.event.dispatcher.AbstractAggregateEventDispatcher
import me.ahoo.wow.event.dispatcher.AbstractEventDispatcher
import me.ahoo.wow.event.dispatcher.AggregateEventDispatcher
import me.ahoo.wow.event.dispatcher.AggregateStateEventDispatcher
import me.ahoo.wow.event.dispatcher.EventStreamDispatcher
import me.ahoo.wow.event.dispatcher.StateEventDispatcher
import me.ahoo.wow.messaging.function.FunctionMetadataParser
import me.ahoo.wow.messaging.function.InjectableMessageFunctionAccessor
import me.ahoo.wow.messaging.function.LogResumeErrorMessageHandler
import me.ahoo.wow.messaging.function.MessageFunctionAccessor
import me.ahoo.wow.messaging.function.SimpleMessageFunctionAccessor
import me.ahoo.wow.modeling.command.AggregateProcessor
import me.ahoo.wow.modeling.command.CommandAggregate
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.RetryableAggregateProcessor
import me.ahoo.wow.modeling.command.SimpleCommandAggregate
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import org.junit.jupiter.api.Test
import kotlin.reflect.KVisibility

/**
 * The fence around wow-core's write interior (design 9.3.0 §5 T1): the command aggregate SPI carries [WowSpi], and
 * the processing interior that the kernel rewrite replaces is `internal`. Types that other Wow modules wire carry
 * `@InternalWowApi`, which `checkKotlinAbi` guards by keeping them out of `api/wow-core.api`.
 */
class CoreWriteApiTiersTest {
    @Test
    fun `the command aggregate SPI is marked`() {
        val spi = listOf(
            AggregateProcessor::class,
            CommandAggregate::class,
            CommandAggregateFactory::class,
            SimpleCommandAggregateFactory::class,
        )
        spi.filterNot { it.java.isAnnotationPresent(WowSpi::class.java) }
            .map { it.simpleName }
            .assert()
            .describedAs("Mark these command aggregate SPI types @WowSpi")
            .isEmpty()
    }

    @Test
    fun `the processing interior is internal`() {
        val interior = listOf(
            AggregateModel::class,
            CommandEntry::class,
            FunctionCommandEntry::class,
            BuiltInCommandEntry::class,
            CompiledFunction::class,
            InjectedParameter::class,
            SourcingFunction::class,
            SimpleCommandAggregate::class,
            RetryableAggregateProcessor::class,
            MessageFunctionAccessor::class,
            SimpleMessageFunctionAccessor::class,
            InjectableMessageFunctionAccessor::class,
            FunctionMetadataParser::class,
            LogResumeErrorMessageHandler::class,
            AbstractEventDispatcher::class,
            AbstractAggregateEventDispatcher::class,
            AggregateEventDispatcher::class,
            AggregateStateEventDispatcher::class,
            EventStreamDispatcher::class,
            StateEventDispatcher::class,
        )
        interior.filterNot { it.visibility == KVisibility.INTERNAL }
            .map { it.simpleName }
            .assert()
            .describedAs("Keep these processing types internal")
            .isEmpty()
    }
}
