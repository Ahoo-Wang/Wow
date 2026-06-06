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

package me.ahoo.wow.hotpath

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class HeaderCreationBenchmark {
    private val fixture = HotPathFixture

    @Benchmark
    fun createEmptyHeader(blackhole: Blackhole) {
        val header = fixture.createHeader()
        blackhole.consume(header)
    }

    @Benchmark
    fun createAndMutateHeader(blackhole: Blackhole) {
        val header = fixture.createHeader()
        header["key1"] = "value1"
        header["key2"] = "value2"
        blackhole.consume(header)
    }

    @Benchmark
    fun headerReadAfterWrite(blackhole: Blackhole) {
        val header = fixture.createHeader()
        header["key"] = "value"
        val value = header["key"]
        blackhole.consume(value)
    }
}
