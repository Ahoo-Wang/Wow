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

package me.ahoo.wow.infra.accessor.function.reactive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.mono
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.execution.KeyedExecutorContext
import me.ahoo.wow.infra.accessor.ensureAccessible
import me.ahoo.wow.infra.accessor.function.invokeFunction
import me.ahoo.wow.infra.accessor.function.invokeFunction1
import me.ahoo.wow.infra.invoker.FunctionInvoker
import me.ahoo.wow.infra.invoker.FunctionInvokerFactory
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toFlux
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

/**
 * How a message function's return value becomes one `Mono`, chosen once per function from its declaration (B10).
 *
 * Every shape shares one exception rule: what the function throws propagates as the error of the returned `Mono`,
 * unwrapped from reflection's `InvocationTargetException`, whether it is thrown by a plain call, while creating a
 * `Flow`, or by a `suspend` function.
 *
 * The empty-result rules are the shapes' own, unchanged:
 * - [SYNC]: `null` (or a `Unit`/`void` function) completes empty;
 * - [MONO]: the returned `Mono` as is; an empty `Mono` completes empty;
 * - [FLUX], [PUBLISHER], [FLOW]: all elements collected into one `List`, which may be empty;
 * - [SUSPEND]: the returned value, `Unit` included; `null` fails with a `NullPointerException`.
 *
 * [FLOW] and [SUSPEND] run their coroutine on the dispatcher's keyed executor when called by a message dispatcher
 * (decision B10: the coroutine resumes on the workers of the aggregate's mailbox, which does not start the aggregate's
 * next message before it returns), and on `Dispatchers.Default` otherwise.
 */
internal enum class ResultAdapter {
    SYNC {
        override fun adapt(call: FunctionCall): Mono<Any> = Mono.fromCallable { call.call() }
    },
    MONO {
        @Suppress("UNCHECKED_CAST")
        override fun adapt(call: FunctionCall): Mono<Any> = Mono.defer { call.call() as Mono<Any> }
    },
    FLUX {
        @Suppress("UNCHECKED_CAST")
        override fun adapt(call: FunctionCall): Mono<Any> =
            Mono.defer { (call.call() as Flux<Any>).collectList() }
    },
    PUBLISHER {
        @Suppress("UNCHECKED_CAST")
        override fun adapt(call: FunctionCall): Mono<Any> =
            Mono.defer { (call.call() as Publisher<Any>).toFlux().collectList() }
    },
    FLOW {
        @Suppress("UNCHECKED_CAST")
        override fun adapt(call: FunctionCall): Mono<Any> = coroutineMono { (call.call() as Flow<Any>).toList() }
    },
    SUSPEND {
        override fun adapt(call: FunctionCall): Mono<Any> =
            coroutineMono { call.callSuspend() ?: throw NullPointerException("The suspend function returned null.") }
    };

    abstract fun adapt(call: FunctionCall): Mono<Any>

    companion object {
        /** The adapter for [function]'s declared return type; a `suspend` function is [SUSPEND] whatever it returns. */
        fun of(function: KFunction<*>): ResultAdapter {
            if (function.isSuspend) {
                return SUSPEND
            }
            val returnType = function.returnType.jvmErasure
            return when {
                returnType.isSubclassOf(Flow::class) -> FLOW
                returnType.isSubclassOf(Mono::class) -> MONO
                returnType.isSubclassOf(Flux::class) -> FLUX
                returnType.isSubclassOf(Publisher::class) -> PUBLISHER
                else -> SYNC
            }
        }
    }
}

/**
 * A `mono` on the subscriber's keyed-executor dispatcher ([KeyedExecutorContext]), or on `Dispatchers.Default` when
 * the subscriber is not a message dispatcher.
 */
private fun coroutineMono(block: suspend CoroutineScope.() -> Any): Mono<Any> =
    Mono.deferContextual { context ->
        mono(KeyedExecutorContext.coroutineDispatcherOf(context) ?: Dispatchers.Default, block)
    }

/** One call of a function with its receiver and arguments, made when the adapted `Mono` is subscribed. */
internal class FunctionCall(
    private val function: KFunction<*>,
    private val invoker: FunctionInvoker,
    private val target: Any?,
    private val args: Array<Any?>?,
    private val arg: Any?
) {
    /** Calls the function; the invoker throws what the function threw, unwrapped. */
    fun call(): Any? =
        if (args == null) {
            invoker.invokeFunction1(function, target, arg)
        } else {
            invoker.invokeFunction(function, target, args)
        }

    /** Calls a `suspend` function, unwrapping what it threw like [call]. */
    suspend fun callSuspend(): Any? =
        try {
            function.callSuspend(target, *(args ?: arrayOf(arg)))
        } catch (invocationTargetException: InvocationTargetException) {
            throw invocationTargetException.targetException
        }
}

/**
 * The [MonoFunctionAccessor] of every message function: calls it through a [FunctionInvoker] and adapts its result
 * with the [ResultAdapter] chosen for it. A function annotated with [Blocking] is subscribed on [blockingScheduler]
 * when called from a non-blocking thread.
 */
internal class AdaptedMonoFunctionAccessor<T, D : Any>(
    override val function: KFunction<*>,
    val resultAdapter: ResultAdapter = ResultAdapter.of(function),
    private val blockingScheduler: Scheduler? =
        function.scanAnnotation<Blocking>()?.let { Schedulers.boundedElastic() }
) : MonoFunctionAccessor<T, Mono<D>> {
    init {
        function.ensureAccessible()
    }

    override val method: Method = function.javaMethod!!

    private val invoker: FunctionInvoker = FunctionInvokerFactory.create(method)

    /** Whether calls are moved off non-blocking threads. */
    val blocking: Boolean get() = blockingScheduler != null

    override fun invoke(target: T, args: Array<Any?>): Mono<D> = adapt(
        FunctionCall(function, invoker, target, args, null)
    )

    override fun invoke1(target: T, arg: Any?): Mono<D> = adapt(FunctionCall(function, invoker, target, null, arg))

    @Suppress("UNCHECKED_CAST")
    private fun adapt(call: FunctionCall): Mono<D> {
        val adapted = resultAdapter.adapt(call) as Mono<D>
        return blockingScheduler?.let { adapted.toBlockable(it) } ?: adapted
    }

    override fun toString(): String = "AdaptedMonoFunctionAccessor(function=$function, resultAdapter=$resultAdapter)"
}
