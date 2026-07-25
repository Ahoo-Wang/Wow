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

package me.ahoo.wow.infra.sink

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConcurrentManySinkTest {

    @Test
    fun `should preserve the public decorator API and avoid duplicate wrapping`() {
        val delegate = Sinks.unsafe().many().multicast().onBackpressureBuffer<Int>()
        val sink = ConcurrentManySink(delegate)
        val extensionSink = delegate.concurrent()

        sink.delegate.assert().isSameAs(delegate)
        sink.concurrent().assert().isSameAs(sink)
        sink.prepareConcurrentSink().assert().isSameAs(sink)
        extensionSink.concurrent().assert().isSameAs(extensionSink)
        extensionSink.delegate.assert().isSameAs(delegate)
        sink.asFlux().assert().isSameAs(delegate.asFlux())
        sink.scanUnsafe(Scannable.Attr.CAPACITY)
            .assert()
            .isEqualTo(delegate.scanUnsafe(Scannable.Attr.CAPACITY))
    }

    @Test
    fun `should return each physical result on its originating caller thread`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val delegate = RecordingManySink { operation ->
            if (operation == Operation.Next(1)) {
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                Sinks.EmitResult.OK
            } else {
                Sinks.EmitResult.FAIL_OVERFLOW
            }
        }
        val sink = delegate.concurrent()
        val executor = Executors.newFixedThreadPool(2)
        val secondStarted = CountDownLatch(1)
        val secondCaller = AtomicReference<Thread>()
        try {
            val first = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
            firstEntered.await(5, TimeUnit.SECONDS).assert().isTrue()
            val second = executor.submit<Sinks.EmitResult> {
                secondCaller.set(Thread.currentThread())
                secondStarted.countDown()
                sink.tryEmitNext(2)
            }
            secondStarted.await(5, TimeUnit.SECONDS).assert().isTrue()
            awaitContendingCaller(checkNotNull(secondCaller.get()), second)

            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
            second.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.FAIL_OVERFLOW)
            delegate.operations.assert().containsExactly(Operation.Next(1), Operation.Next(2))
            delegate.calls.last().thread.assert().isSameAs(secondCaller.get())
            delegate.maxConcurrentCalls.get().assert().isEqualTo(1)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `should preserve multicast delivery and producer order`() {
        val producerCount = 4
        val valuesPerProducer = 8
        val total = producerCount * valuesPerProducer
        val sink = Sinks.unsafe().many().multicast().onBackpressureBuffer<Int>().concurrent()
        val first = sink.asFlux().take(total.toLong()).collectList().toFuture()
        val second = sink.asFlux().take(total.toLong()).collectList().toFuture()
        val executor = Executors.newFixedThreadPool(producerCount)
        val ready = CountDownLatch(producerCount)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<Sinks.EmitResult>()
        try {
            val producers = (0 until producerCount).map { producer ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    repeat(valuesPerProducer) { index ->
                        results.add(sink.tryEmitNext(producer * valuesPerProducer + index))
                    }
                }
            }
            ready.await(5, TimeUnit.SECONDS).assert().isTrue()
            start.countDown()
            producers.forEach {
                it.get(5, TimeUnit.SECONDS)
            }

            results.size.assert().isEqualTo(total)
            results.all { it == Sinks.EmitResult.OK }.assert().isTrue()
            assertProducerOrder(checkNotNull(first.get(5, TimeUnit.SECONDS)), producerCount, valuesPerProducer)
            assertProducerOrder(checkNotNull(second.get(5, TimeUnit.SECONDS)), producerCount, valuesPerProducer)
        } finally {
            start.countDown()
            first.cancel(true)
            second.cancel(true)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `should allow downstream reentrant emission`() {
        val sink = Sinks.unsafe().many().unicast().onBackpressureBuffer<Int>().concurrent()
        val received = mutableListOf<Int>()
        val terminal = CountDownLatch(1)
        val subscription = sink.asFlux().subscribe(
            {
                received.add(it)
                if (it == 1) {
                    sink.tryEmitNext(2).assert().isEqualTo(Sinks.EmitResult.OK)
                }
            },
            { throw it },
            terminal::countDown,
        )
        try {
            sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.OK)
            sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)

            terminal.await(5, TimeUnit.SECONDS).assert().isTrue()
            received.assert().containsExactly(1, 2)
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun `should serialize an active next before complete`() {
        val nextEntered = CountDownLatch(1)
        val releaseNext = CountDownLatch(1)
        val delegate = RecordingManySink { operation ->
            if (operation is Operation.Next) {
                nextEntered.countDown()
                check(releaseNext.await(5, TimeUnit.SECONDS))
            }
            Sinks.EmitResult.OK
        }
        val sink = delegate.concurrent()

        runContended(
            firstCall = { sink.tryEmitNext(1) },
            awaitFirst = { nextEntered.await(5, TimeUnit.SECONDS).assert().isTrue() },
            secondCall = sink::tryEmitComplete,
            releaseFirst = releaseNext::countDown,
        ) { first, second ->
            first.assert().isEqualTo(Sinks.EmitResult.OK)
            second.assert().isEqualTo(Sinks.EmitResult.OK)
        }
        delegate.operations.assert().containsExactly(Operation.Next(1), Operation.Complete)
        delegate.maxConcurrentCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `should serialize complete before a contending error`() {
        val completeEntered = CountDownLatch(1)
        val releaseComplete = CountDownLatch(1)
        val terminated = AtomicBoolean()
        val failure = IllegalStateException("loser")
        val delegate = RecordingManySink { operation ->
            if (operation == Operation.Complete) {
                completeEntered.countDown()
                check(releaseComplete.await(5, TimeUnit.SECONDS))
            }
            if (terminated.compareAndSet(false, true)) {
                Sinks.EmitResult.OK
            } else {
                Sinks.EmitResult.FAIL_TERMINATED
            }
        }
        val sink = delegate.concurrent()

        runContended(
            firstCall = sink::tryEmitComplete,
            awaitFirst = { completeEntered.await(5, TimeUnit.SECONDS).assert().isTrue() },
            secondCall = { sink.tryEmitError(failure) },
            releaseFirst = releaseComplete::countDown,
        ) { first, second ->
            first.assert().isEqualTo(Sinks.EmitResult.OK)
            second.assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
        }
        delegate.operations.assert().containsExactly(Operation.Complete, Operation.Error(failure))
        delegate.maxConcurrentCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `should serialize emit methods and preserve their arguments`() {
        val nextEntered = CountDownLatch(1)
        val releaseNext = CountDownLatch(1)
        val failure = IllegalStateException("failure")
        val failureHandler = Sinks.EmitFailureHandler { _, _ -> false }
        val delegate = RecordingManySink { operation ->
            if (operation == Operation.Next(1)) {
                nextEntered.countDown()
                check(releaseNext.await(5, TimeUnit.SECONDS))
            }
            Sinks.EmitResult.OK
        }
        val sink = delegate.concurrent()

        val errorCaller = runContended(
            firstCall = { sink.emitNext(1, failureHandler) },
            awaitFirst = { nextEntered.await(5, TimeUnit.SECONDS).assert().isTrue() },
            secondCall = { sink.emitError(failure, failureHandler) },
            releaseFirst = releaseNext::countDown,
        ) { _, _ -> }
        sink.emitComplete(failureHandler)

        delegate.operations.assert()
            .containsExactly(Operation.Next(1), Operation.Error(failure), Operation.Complete)
        delegate.calls.elementAt(1).thread.assert().isSameAs(errorCaller)
        delegate.calls.map(RecordedCall::failureHandler)
            .assert()
            .containsExactly(failureHandler, failureHandler, failureHandler)
        delegate.maxConcurrentCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `should release a failed owner and preserve failures by identity`() {
        val ownerEntered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val ownerFailure = AssertionError("owner")
        val waiterFailure = AssertionError("waiter")
        val delegate = RecordingManySink { operation ->
            if (operation == Operation.Next(1)) {
                ownerEntered.countDown()
                check(releaseOwner.await(5, TimeUnit.SECONDS))
                throw ownerFailure
            }
            throw waiterFailure
        }
        val sink = delegate.concurrent()
        val executor = Executors.newFixedThreadPool(2)
        val waiterStarted = CountDownLatch(1)
        val waiterThread = AtomicReference<Thread>()
        try {
            val owner = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
            ownerEntered.await(5, TimeUnit.SECONDS).assert().isTrue()
            val waiter = executor.submit<Sinks.EmitResult> {
                waiterThread.set(Thread.currentThread())
                waiterStarted.countDown()
                sink.tryEmitNext(2)
            }
            waiterStarted.await(5, TimeUnit.SECONDS).assert().isTrue()
            awaitContendingCaller(checkNotNull(waiterThread.get()), waiter)

            releaseOwner.countDown()
            assertFutureFailure(owner).assert().isSameAs(ownerFailure)
            assertFutureFailure(waiter).assert().isSameAs(waiterFailure)
            delegate.operations.assert().containsExactly(Operation.Next(1), Operation.Next(2))
        } finally {
            releaseOwner.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `should preserve a rejected terminal for retry`() {
        val completeCalls = AtomicInteger()
        val delegate = RecordingManySink { operation ->
            if (operation == Operation.Complete && completeCalls.incrementAndGet() == 1) {
                Sinks.EmitResult.FAIL_NON_SERIALIZED
            } else {
                Sinks.EmitResult.OK
            }
        }
        val sink = delegate.concurrent()

        sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.FAIL_NON_SERIALIZED)
        sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
        delegate.operations.assert().containsExactly(Operation.Complete, Operation.Complete)
    }

    private fun <F, S> runContended(
        firstCall: () -> F,
        awaitFirst: () -> Unit,
        secondCall: () -> S,
        releaseFirst: () -> Unit,
        assertions: (F, S) -> Unit,
    ): Thread {
        val executor = Executors.newFixedThreadPool(2)
        val secondStarted = CountDownLatch(1)
        val secondThread = AtomicReference<Thread>()
        try {
            val first = executor.submit<F>(firstCall)
            awaitFirst()
            val second = executor.submit<S> {
                secondThread.set(Thread.currentThread())
                secondStarted.countDown()
                secondCall()
            }
            secondStarted.await(5, TimeUnit.SECONDS).assert().isTrue()
            awaitContendingCaller(checkNotNull(secondThread.get()), second)

            releaseFirst()
            assertions(
                first.get(5, TimeUnit.SECONDS),
                second.get(5, TimeUnit.SECONDS),
            )
        } finally {
            releaseFirst()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
        return checkNotNull(secondThread.get())
    }

    private fun awaitContendingCaller(thread: Thread, future: Future<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (
            !thread.isWaitingForLock() &&
            !future.isDone &&
            System.nanoTime() < deadline
        ) {
            Thread.yield()
        }
        future.isDone.assert().isFalse()
        thread.isWaitingForLock().assert().isTrue()
    }

    private fun assertFutureFailure(future: Future<*>): Throwable =
        assertThrows<ExecutionException> {
            future.get(5, TimeUnit.SECONDS)
        }.cause ?: error("Future failed without a cause.")

    private fun assertProducerOrder(
        values: List<Int>,
        producerCount: Int,
        valuesPerProducer: Int,
    ) {
        values.toSet().assert().isEqualTo((0 until producerCount * valuesPerProducer).toSet())
        repeat(producerCount) { producer ->
            val expected = (0 until valuesPerProducer).map { producer * valuesPerProducer + it }
            values.filter { it / valuesPerProducer == producer }
                .assert()
                .containsExactly(*expected.toTypedArray())
        }
    }
}

private fun Thread.isWaitingForLock(): Boolean =
    when (state) {
        Thread.State.BLOCKED,
        Thread.State.WAITING,
        Thread.State.TIMED_WAITING,
        -> true

        else -> false
    }

private sealed interface Operation {
    data class Next(val value: Int) : Operation
    data object Complete : Operation
    data class Error(val error: Throwable) : Operation
}

private data class RecordedCall(
    val operation: Operation,
    val thread: Thread,
    val failureHandler: Sinks.EmitFailureHandler?,
)

private class RecordingManySink(
    private val handler: (Operation) -> Sinks.EmitResult,
) : StubManySink() {
    val calls = ConcurrentLinkedQueue<RecordedCall>()
    val operations: List<Operation>
        get() = calls.map(RecordedCall::operation)
    val maxConcurrentCalls = AtomicInteger()
    private val activeCalls = AtomicInteger()

    override fun tryEmitNext(t: Int): Sinks.EmitResult = record(Operation.Next(t))

    override fun tryEmitComplete(): Sinks.EmitResult = record(Operation.Complete)

    override fun tryEmitError(error: Throwable): Sinks.EmitResult = record(Operation.Error(error))

    override fun emitNext(t: Int, failureHandler: Sinks.EmitFailureHandler) {
        record(Operation.Next(t), failureHandler)
    }

    override fun emitComplete(failureHandler: Sinks.EmitFailureHandler) {
        record(Operation.Complete, failureHandler)
    }

    override fun emitError(error: Throwable, failureHandler: Sinks.EmitFailureHandler) {
        record(Operation.Error(error), failureHandler)
    }

    private fun record(
        operation: Operation,
        failureHandler: Sinks.EmitFailureHandler? = null,
    ): Sinks.EmitResult {
        val active = activeCalls.incrementAndGet()
        maxConcurrentCalls.accumulateAndGet(active, ::maxOf)
        calls.add(RecordedCall(operation, Thread.currentThread(), failureHandler))
        return try {
            handler(operation)
        } finally {
            activeCalls.decrementAndGet()
        }
    }
}

private open class StubManySink : Sinks.Many<Int> {
    override fun tryEmitNext(t: Int): Sinks.EmitResult = Sinks.EmitResult.OK

    override fun tryEmitComplete(): Sinks.EmitResult = Sinks.EmitResult.OK

    override fun tryEmitError(error: Throwable): Sinks.EmitResult = Sinks.EmitResult.OK

    override fun emitNext(t: Int, failureHandler: Sinks.EmitFailureHandler) = Unit

    override fun emitComplete(failureHandler: Sinks.EmitFailureHandler) = Unit

    override fun emitError(error: Throwable, failureHandler: Sinks.EmitFailureHandler) = Unit

    override fun currentSubscriberCount(): Int = 0

    override fun asFlux(): Flux<Int> = Flux.never()

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? = null
}
