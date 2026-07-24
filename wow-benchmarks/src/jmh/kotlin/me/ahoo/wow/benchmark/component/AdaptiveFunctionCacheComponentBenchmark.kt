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

import me.ahoo.wow.benchmark.runtime.FunctionCacheVariant
import me.ahoo.wow.benchmark.runtime.MapFirstFunctionCache
import me.ahoo.wow.benchmark.runtime.SingleEntryFunctionCache
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

private enum class FunctionCachePolicy {
    MAP_FIRST,
    SINGLE_ENTRY,
}

@State(Scope.Thread)
open class AdaptiveFunctionCacheComponentBenchmark {
    @Param("MAP_FIRST", "SINGLE_ENTRY")
    lateinit var cachePolicy: String

    private val firstType = String::class.java
    private val secondType = Int::class.java
    private val missingType = Long::class.java
    private val firstFunction = Any()
    private val secondFunction = Any()

    private lateinit var cacheFactory: () -> FunctionCacheVariant<Any>
    private lateinit var resolver: (Class<*>) -> Any?
    private lateinit var missingResolver: (Class<*>) -> Any?
    private lateinit var singleTypeCache: FunctionCacheVariant<Any>
    private lateinit var twoTypeCache: FunctionCacheVariant<Any>
    private var sequence: Int = 0

    @Setup
    fun setup() {
        cacheFactory = when (FunctionCachePolicy.valueOf(cachePolicy)) {
            FunctionCachePolicy.MAP_FIRST -> {
                { MapFirstFunctionCache() }
            }

            FunctionCachePolicy.SINGLE_ENTRY -> {
                { SingleEntryFunctionCache() }
            }
        }
        resolver = {
            when (it) {
                firstType -> firstFunction
                secondType -> secondFunction
                else -> null
            }
        }
        missingResolver = { null }
        singleTypeCache = cacheFactory()
        check(singleTypeCache.get(firstType, resolver) === firstFunction)
        twoTypeCache = cacheFactory()
        check(twoTypeCache.get(firstType, resolver) === firstFunction)
        check(twoTypeCache.get(secondType, resolver) === secondFunction)
    }

    @Benchmark
    fun createEmptyCache(blackhole: Blackhole) {
        blackhole.consume(cacheFactory())
    }

    @Benchmark
    fun createAndResolveFirst(): Any {
        val cache = cacheFactory()
        return checkNotNull(cache.get(firstType, resolver))
    }

    @Benchmark
    fun createAndResolveSecond(): Any {
        val cache = cacheFactory()
        check(cache.get(firstType, resolver) === firstFunction)
        return checkNotNull(cache.get(secondType, resolver))
    }

    @Benchmark
    fun singleTypeHit(): Any = checkNotNull(singleTypeCache.get(firstType, missingResolver))

    @Benchmark
    fun twoTypeAlternatingHit(): Any {
        val functionType = if (sequence++ and 1 == 0) {
            firstType
        } else {
            secondType
        }
        return checkNotNull(twoTypeCache.get(functionType, missingResolver))
    }

    @Benchmark
    fun missingAfterSingleEntry(blackhole: Blackhole) {
        blackhole.consume(singleTypeCache.get(missingType, missingResolver))
    }
}
