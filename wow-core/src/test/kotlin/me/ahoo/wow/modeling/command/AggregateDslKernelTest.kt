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

package me.ahoo.wow.modeling.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.test.AggregateVerifier.aggregateVerifier
import me.ahoo.wow.test.aggregate.GivenStage
import me.ahoo.wow.test.aggregate.whenCommand
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * V6: the aggregate test DSL drives the production command pipeline and kernel. Each case is a place where the DSL
 * used to answer differently from production.
 */
class AggregateDslKernelTest {
    private lateinit var eventStore: InMemoryEventStore
    private lateinit var verifier: GivenStage<OrderProbeAggregate>

    private fun storedVersions(): List<Int> =
        eventStore.load(aggregateMetadata<OrderProbeAggregate, OrderProbeAggregate>().aggregateId(AGGREGATE_ID))
            .map { it.version }.collectList().block()!!

    @BeforeEach
    fun reset() {
        OrderProbe.reset()
        eventStore = InMemoryEventStore()
        verifier = OrderProbeAggregate::class.java.aggregateVerifier<OrderProbeAggregate, OrderProbeAggregate>(
            aggregateId = AGGREGATE_ID,
            eventStore = eventStore,
        )
    }

    @Test
    fun `given events are appended to the event store at their real version`() {
        verifier.given(OrderProbeCreated(AGGREGATE_ID))
            .whenCommand(ChangeOrderProbe(AGGREGATE_ID))
            .expectNoError()
            .expectStateAggregate { version.assert().isEqualTo(2) }
            .verify()

        storedVersions().assert().isEqualTo(listOf(1, 2))
    }

    @Test
    fun `a command for an aggregate without history fails as in production`() {
        verifier.whenCommand(ChangeOrderProbe(AGGREGATE_ID))
            .expectErrorType(NotFoundResourceException::class)
            .verify()
    }

    @Test
    fun `a create command on an aggregate with history fails at the append`() {
        verifier.given(OrderProbeCreated(AGGREGATE_ID))
            .whenCommand(CreateOrderProbe(AGGREGATE_ID))
            .expectErrorType(DuplicateAggregateIdException::class)
            .expectStateAggregate { version.assert().isEqualTo(1) }
            .verify()
    }

    @Test
    fun `a command that decides nothing succeeds with no event stream`() {
        KernelCharacterizationAggregate::class.java
            .aggregateVerifier<KernelCharacterizationAggregate, KernelCharacterizationAggregate>()
            .whenCommand(ReturnNull("id"))
            .expectNoError()
            .expect { domainEventStream.assert().isNull() }
            .expectStateAggregate { initialized.assert().isFalse() }
            .verify()
    }

    @Test
    fun `the next step loads the committed state instead of reusing the instance`() {
        val created = verifier.whenCommand(CreateOrderProbe(AGGREGATE_ID)).expectNoError().verify()

        val changed = created.then().whenCommand(ChangeOrderProbe(AGGREGATE_ID))
            .expectNoError()
            .expectStateAggregate { version.assert().isEqualTo(2) }
            .verify()

        changed.stateRoot.assert().isNotSameAs(created.stateRoot)
        changed.stateRoot.sourced.assert()
            .isEqualTo(listOf(OrderProbeCreated(AGGREGATE_ID), OrderProbeChanged(AGGREGATE_ID)))
        created.stateRoot.sourced.assert().isEqualTo(listOf(OrderProbeCreated(AGGREGATE_ID)))
    }

    @Test
    fun `a command for another aggregate after a verified step does not reach the verified state`() {
        verifier.whenCommand(CreateOrderProbe(AGGREGATE_ID)).expectNoError().verify()
            .then()
            .whenCommand(ChangeOrderProbe("another-aggregate"))
            .expectErrorType(NotFoundResourceException::class)
            .verify()
    }

    @Test
    fun `a recoverable failure is reported once, without retries`() {
        verifier.given(OrderProbeCreated(AGGREGATE_ID))
            .whenCommand(FailRecoverably(AGGREGATE_ID))
            .expectErrorType(java.util.concurrent.TimeoutException::class)
            .verify()

        OrderProbe.recoverableFailures.assert().isEqualTo(1)
    }

    @Test
    fun `a sourcing function that cannot apply the committed events fails the step`() {
        verifier.whenCommand(CreateFailingSourcing(AGGREGATE_ID))
            .expectErrorType(IllegalStateException::class)
            .verify()

        storedVersions().assert().isEqualTo(listOf(1))
    }

    @Test
    fun `a forked step continues an independent copy of the history`() {
        val created = verifier.whenCommand(CreateOrderProbe(AGGREGATE_ID)).expectNoError().verify()

        created.fork().whenCommand(ChangeOrderProbe(AGGREGATE_ID)).expectNoError().verify()

        storedVersions().assert().isEqualTo(listOf(1))
    }

    private companion object {
        const val AGGREGATE_ID = "dsl-kernel-1"
    }

    @Test
    fun `sibling commands of one given each start from the given history`() {
        val given = verifier.given(OrderProbeCreated(AGGREGATE_ID))

        given.whenCommand(ChangeOrderProbe(AGGREGATE_ID))
            .expectNoError()
            .expectStateAggregate { version.assert().isEqualTo(2) }
            .verify()
        given.whenCommand(ChangeOrderProbe(AGGREGATE_ID))
            .expectNoError()
            .expectStateAggregate { version.assert().isEqualTo(2) }
            .verify()

        // The first sibling ran on the given store; the second on a copy of the history as it was given.
        storedVersions().assert().isEqualTo(listOf(1, 2))
    }

    @Test
    fun `a sibling create on an empty given is not a duplicate of its sibling`() {
        verifier.whenCommand(CreateOrderProbe(AGGREGATE_ID)).expectNoError().verify()
        verifier.whenCommand(CreateOrderProbe(AGGREGATE_ID)).expectNoError().verify()
    }
}
