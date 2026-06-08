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

import me.ahoo.wow.infra.accessor.constructor.DefaultConstructorAccessor
import me.ahoo.wow.infra.accessor.function.SimpleFunctionAccessor
import me.ahoo.wow.infra.invoker.FunctionInvokerFactory
import me.ahoo.wow.infra.invoker.InstanceFunctionInvoker
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.lang.reflect.Method

@State(Scope.Thread)
open class AccessorComponentBenchmark {
    private lateinit var target: AccessorTarget
    private lateinit var argument: String
    private lateinit var singleMethod: Method
    private lateinit var singleInvoker: InstanceFunctionInvoker
    private lateinit var functionAccessor: SimpleFunctionAccessor<AccessorTarget, String>
    private lateinit var constructorAccessor0: DefaultConstructorAccessor<ConstructorTarget0>
    private lateinit var constructorAccessor1: DefaultConstructorAccessor<ConstructorTarget1>
    private lateinit var constructorAccessor2: DefaultConstructorAccessor<ConstructorTarget2>

    @Setup
    fun setup() {
        target = AccessorTarget("hello")
        argument = "wow"
        singleMethod = AccessorTarget::class.java.getDeclaredMethod("single", String::class.java)
        singleMethod.trySetAccessible()
        singleInvoker = FunctionInvokerFactory.create(singleMethod) as InstanceFunctionInvoker
        functionAccessor = SimpleFunctionAccessor(AccessorTarget::single)
        constructorAccessor0 = DefaultConstructorAccessor(ConstructorTarget0::class.java.getDeclaredConstructor())
        constructorAccessor1 = DefaultConstructorAccessor(
            ConstructorTarget1::class.java.getDeclaredConstructor(String::class.java)
        )
        constructorAccessor2 = DefaultConstructorAccessor(
            ConstructorTarget2::class.java.getDeclaredConstructor(String::class.java, String::class.java)
        )
    }

    @Benchmark
    @Throws(Throwable::class)
    fun reflectionInvokeVarargs(blackhole: Blackhole) {
        blackhole.consume(singleMethod.invoke(target, argument))
    }

    @Benchmark
    @Throws(Throwable::class)
    fun methodHandleArray(blackhole: Blackhole) {
        blackhole.consume(singleInvoker.invoke(target, arrayOf(argument)))
    }

    @Benchmark
    @Throws(Throwable::class)
    fun methodHandleSingle(blackhole: Blackhole) {
        blackhole.consume(singleInvoker.invoke1(target, argument))
    }

    @Benchmark
    fun functionAccessorInvoke(blackhole: Blackhole) {
        blackhole.consume(functionAccessor.invoke(target, arrayOf(argument)))
    }

    @Benchmark
    fun functionAccessorInvoke1(blackhole: Blackhole) {
        blackhole.consume(functionAccessor.invoke1(target, argument))
    }

    @Benchmark
    fun constructorInvokeArray(blackhole: Blackhole) {
        blackhole.consume(constructorAccessor1.invoke(arrayOf(argument)))
    }

    @Benchmark
    fun constructorInvoke0(blackhole: Blackhole) {
        blackhole.consume(constructorAccessor0.newInstance0())
    }

    @Benchmark
    fun constructorInvoke1(blackhole: Blackhole) {
        blackhole.consume(constructorAccessor1.newInstance1(argument))
    }

    @Benchmark
    fun constructorInvoke2(blackhole: Blackhole) {
        blackhole.consume(constructorAccessor2.newInstance2("hello", argument))
    }

    private class AccessorTarget(
        private val greeting: String
    ) {
        fun single(argument: String): String = "$greeting $argument"
    }

    private class ConstructorTarget0

    private class ConstructorTarget1(
        val argument: String
    )

    private class ConstructorTarget2(
        val greeting: String,
        val argument: String
    )
}
