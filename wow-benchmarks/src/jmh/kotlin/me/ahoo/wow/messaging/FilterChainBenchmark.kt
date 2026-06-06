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

package me.ahoo.wow.messaging

import me.ahoo.wow.filter.Filter
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono

@State(Scope.Benchmark)
open class FilterChainBenchmark {
    @Param("1", "5", "10")
    var filterCount: Int = 1

    private lateinit var chain: FilterChain<String>
    private val context = "benchmark-context"

    @Setup
    fun setup() {
        chain = FilterChainBuilder<String>()
            .apply {
                repeat(filterCount) {
                    addFilter(NoopFilter)
                }
            }
            .build()
    }

    @Benchmark
    fun executeChain(blackhole: Blackhole) {
        val result = chain.filter(context).block()
        blackhole.consume(result)
    }
}

private object NoopFilter : Filter<String> {
    override fun filter(context: String, chain: FilterChain<String>): Mono<Void> {
        return chain.filter(context)
    }
}
