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

package me.ahoo.wow.benchmark.component

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkHeaders
import me.ahoo.wow.benchmark.fixture.BenchmarkIds
import me.ahoo.wow.example.api.cart.AddCartItem
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
open class CommandMessageComponentBenchmark {
    private lateinit var commandMessage: CommandMessage<AddCartItem>

    @Setup
    fun setup() {
        BenchmarkIds.installDeterministicGlobalIdGenerator()
        commandMessage = BenchmarkCommands.commandPathAddCartItem()
    }

    @Benchmark
    fun createEmptyHeader(blackhole: Blackhole) {
        blackhole.consume(BenchmarkHeaders.emptyHeader())
    }

    @Benchmark
    fun createAndMutateHeader(blackhole: Blackhole) {
        val header = BenchmarkHeaders.emptyHeader()
        header["key1"] = "value1"
        header["key2"] = "value2"
        blackhole.consume(header)
    }

    @Benchmark
    fun createCommandMessage(blackhole: Blackhole) {
        blackhole.consume(BenchmarkCommands.commandPathAddCartItem())
    }

    @Benchmark
    fun readCommandMessageProperties(blackhole: Blackhole) {
        blackhole.consume(commandMessage.id)
        blackhole.consume(commandMessage.aggregateId)
        blackhole.consume(commandMessage.requestId)
        blackhole.consume(commandMessage.body)
    }
}
