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
package me.ahoo.wow.eventsourcing

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.exception.RecoverableException
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class AppendOutcomeResolutionTest {
    private val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")

    @Test
    fun `a recoverable failure after the write committed resolves as committed`() {
        val stream = eventStream("request-1")
        val store = ScriptedEventStore({ commitThenFail(it, TransientStoreException()) })

        StepVerifier.create(store.appendResolvingOutcome(stream)).verifyComplete()

        store.appendCalls.assert().isEqualTo(1)
        store.storedIds().assert().containsExactly(stream.id)
    }

    @Test
    fun `an unrecoverable failure after the write committed resolves as committed`() {
        val stream = eventStream("request-1")
        val store = ScriptedEventStore({ commitThenFail(it, IllegalStateException("acknowledgement lost")) })

        StepVerifier.create(store.appendResolvingOutcome(stream)).verifyComplete()

        store.storedIds().assert().containsExactly(stream.id)
    }

    @Test
    fun `a recoverable failure before the write rewrites the same stream once`() {
        val stream = eventStream("request-1")
        val store = ScriptedEventStore({ Mono.error(TransientStoreException()) }, { it.commit() })

        StepVerifier.create(store.appendResolvingOutcome(stream)).verifyComplete()

        store.appendCalls.assert().isEqualTo(2)
        store.storedIds().assert().containsExactly(stream.id)
    }

    @Test
    fun `a rewrite that loses to the write still in flight resolves as committed`() {
        val stream = eventStream("request-1")
        val store = ScriptedEventStore(
            { Mono.error(TransientStoreException()) },
            // The first write lands first, so the rewrite hits the unique version.
            { commitThenFail(it, EventVersionConflictException(it)) },
        )

        StepVerifier.create(store.appendResolvingOutcome(stream)).verifyComplete()

        store.storedIds().assert().containsExactly(stream.id)
    }

    @Test
    fun `a rewrite that fails with the slot still empty returns the original failure`() {
        val stream = eventStream("request-1")
        val original = TransientStoreException()
        val rewriteFailure = TransientStoreException()
        val store = ScriptedEventStore({ Mono.error(original) }, { Mono.error(rewriteFailure) })

        StepVerifier.create(store.appendResolvingOutcome(stream))
            .expectErrorSatisfies {
                it.assert().isSameAs(original)
                it.suppressed.toList().assert().containsExactly(rewriteFailure)
            }
            .verify()
        store.storedIds().assert().isEmpty()
    }

    @Test
    fun `an unrecoverable failure before the write returns the failure without a rewrite`() {
        val stream = eventStream("request-1")
        val failure = IllegalStateException("rejected")
        val store = ScriptedEventStore({ Mono.error(failure) })

        StepVerifier.create(store.appendResolvingOutcome(stream))
            .expectErrorSatisfies { it.assert().isSameAs(failure) }
            .verify()
        store.appendCalls.assert().isEqualTo(1)
    }

    @Test
    fun `a slot held by another stream returns the original conflict`() {
        val other = eventStream("request-0")
        val stream = eventStream("request-1")
        val store = ScriptedEventStore({ it.commit() }, { it.commit() })
        StepVerifier.create(store.append(other)).verifyComplete()

        StepVerifier.create(store.appendResolvingOutcome(stream))
            .expectError(DuplicateAggregateIdException::class.java)
            .verify()
        store.storedIds().assert().containsExactly(other.id)
        store.appendCalls.assert().isEqualTo(2)
    }

    @Test
    fun `a failed slot read returns the original failure`() {
        val stream = eventStream("request-1")
        val failure = TransientStoreException()
        val readFailure = IllegalStateException("read failed")
        val store = ScriptedEventStore({ Mono.error(failure) }, loadFailure = readFailure)

        StepVerifier.create(store.appendResolvingOutcome(stream))
            .expectErrorSatisfies {
                it.assert().isSameAs(failure)
                it.suppressed.toList().assert().containsExactly(readFailure)
            }
            .verify()
        store.appendCalls.assert().isEqualTo(1)
    }

    private fun eventStream(requestId: String): DomainEventStream =
        MockAggregateChanged(requestId).toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId = aggregateId, requestId = requestId),
            aggregateVersion = 0,
        )

    private class TransientStoreException : RuntimeException("transient"), RecoverableException

    private class ScriptedEventStore(
        vararg appends: ScriptedEventStore.(DomainEventStream) -> Mono<Void>,
        private val loadFailure: Throwable? = null,
        private val delegate: InMemoryEventStore = InMemoryEventStore(),
    ) : EventStore by delegate {
        private val appends = appends.toList()
        var appendCalls = 0
            private set

        fun DomainEventStream.commit(): Mono<Void> = delegate.append(this)

        fun commitThenFail(eventStream: DomainEventStream, failure: Throwable): Mono<Void> =
            eventStream.commit().then(Mono.error(failure))

        fun storedIds(): List<String> =
            delegate.load(MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")).map { it.id }.collectList().block()!!

        override fun append(eventStream: DomainEventStream): Mono<Void> = appends[appendCalls++](eventStream)

        override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> =
            loadFailure?.let { Flux.error(it) } ?: delegate.load(aggregateId, headVersion, tailVersion)
    }
}
