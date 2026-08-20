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

package me.ahoo.wow.query.mask

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.result.ResultPolicyContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reactor.kotlin.test.test
import java.time.Instant
import java.time.ZoneOffset

class MaskingResultPolicyTest {
    private val namedAggregate = "mask.order".toNamedAggregate()
    private val stateRegistry = StateDataMaskerRegistry()
    private val eventRegistry = EventStreamMaskerRegistry()
    private val policy = MaskingResultPolicy(stateRegistry, eventRegistry)

    @ParameterizedTest
    @EnumSource(value = QueryOperation::class, names = ["SINGLE", "LIST", "PAGE"])
    fun `dynamic snapshot items are masked for every result operation`(operation: QueryOperation) {
        val masker = ReplacingStateMasker(namedAggregate)
        stateRegistry.register(masker)

        policy.apply(
            context(QueryDocumentKind.SNAPSHOT, operation, QueryPlanResultShape.Dynamic(emptySet())),
            originalDocument()
        )
            .test()
            .assertNext { result ->
                (result as DynamicDocument).getValue<String>(SECRET).assert().isEqualTo(MASKED)
            }
            .verifyComplete()

        masker.invocations.assert().isOne()
    }

    @Test
    fun `typed DynamicDocument uses the live snapshot registry`() {
        val masker = ReplacingStateMasker(namedAggregate)
        val shape = QueryPlanResultShape.Typed(DynamicDocument::class.java, emptySet())
        val beforeRegistration = originalDocument()

        policy.apply(context(QueryDocumentKind.SNAPSHOT, resultShape = shape), beforeRegistration)
            .test()
            .assertNext { result -> result.assert().isSameAs(beforeRegistration) }
            .verifyComplete()

        stateRegistry.register(masker)
        policy.apply(context(QueryDocumentKind.SNAPSHOT, resultShape = shape), originalDocument())
            .test()
            .assertNext { result ->
                (result as DynamicDocument).getValue<String>(SECRET).assert().isEqualTo(MASKED)
            }
            .verifyComplete()

        stateRegistry.unregister(masker)
        val afterUnregistration = originalDocument()
        policy.apply(context(QueryDocumentKind.SNAPSHOT, resultShape = shape), afterUnregistration)
            .test()
            .assertNext { result -> result.assert().isSameAs(afterUnregistration) }
            .verifyComplete()
    }

    @Test
    fun `typed DynamicDocument uses the event registry for event targets`() {
        eventRegistry.register(ReplacingEventMasker(namedAggregate))
        val shape = QueryPlanResultShape.Typed(DynamicDocument::class.java, emptySet())

        policy.apply(context(QueryDocumentKind.EVENT_STREAM, resultShape = shape), originalDocument())
            .test()
            .assertNext { result ->
                (result as DynamicDocument).getValue<String>(SECRET).assert().isEqualTo(EVENT_MASKED)
            }
            .verifyComplete()
    }

    @Test
    fun `direct typed immutable DynamicDocument preserves dynamic registry semantics`() {
        val masker = ReplacingStateMasker(namedAggregate)
        stateRegistry.register(masker)
        val shape = QueryPlanResultShape.Typed(ImmutableDynamicDocument::class.java, emptySet())

        policy.apply(
            context(QueryDocumentKind.SNAPSHOT, resultShape = shape),
            ImmutableDynamicDocument.copyOf(mapOf(SECRET to ORIGINAL)),
        ).test()
            .assertNext { result ->
                (result as DynamicDocument).getValue<String>(SECRET).assert().isEqualTo(MASKED)
            }
            .verifyComplete()

        masker.invocations.assert().isOne()
    }

    @Test
    fun `typed snapshot preserves DataMasking behavior while typed event remains unchanged`() {
        val original = snapshot(MaskableState(ORIGINAL))
        val typedShape = QueryPlanResultShape.Typed(MaterializedSnapshot::class.java, emptySet())

        policy.apply(context(QueryDocumentKind.SNAPSHOT, resultShape = typedShape), original)
            .test()
            .assertNext { result ->
                val masked = result as MaterializedSnapshot<*>
                (masked.state as MaskableState).secret.assert().isEqualTo(MASKED)
            }
            .verifyComplete()
        policy.apply(context(QueryDocumentKind.EVENT_STREAM, resultShape = typedShape), original)
            .test()
            .assertNext { result -> result.assert().isSameAs(original) }
            .verifyComplete()
    }

    @Test
    fun `count is never passed to a masker`() {
        stateRegistry.register(ReplacingStateMasker(namedAggregate))

        policy.apply(context(QueryDocumentKind.SNAPSHOT, resultShape = QueryPlanResultShape.Count), 1L)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun `masker error never emits the unmasked value`() {
        stateRegistry.register(ThrowingStateMasker(namedAggregate))

        policy.apply(context(QueryDocumentKind.SNAPSHOT), originalDocument())
            .test()
            .expectErrorMatches { it is IllegalStateException && it.message == "mask failed" }
            .verify()
    }

    private fun context(
        documentKind: QueryDocumentKind,
        operation: QueryOperation = QueryOperation.SINGLE,
        resultShape: QueryPlanResultShape = QueryPlanResultShape.Dynamic(emptySet())
    ): ResultPolicyContext = ResultPolicyContext(
        target = QueryTarget(namedAggregate, documentKind),
        operation = operation,
        resultShape = resultShape,
        invocationScope = QueryInvocationScope(
            QueryAuthorityView(null, null, null, emptySet(), emptySet()),
            me.ahoo.wow.api.query.gateway.RequestedQueryScope(),
            "mask-test"
        ),
        frozenInstant = Instant.EPOCH,
        zoneId = ZoneOffset.UTC,
        backendId = "test"
    )

    private fun originalDocument(): DynamicDocument = mutableMapOf<String, Any?>(SECRET to ORIGINAL).toDynamicDocument()

    private fun snapshot(state: MaskableState): MaterializedSnapshot<MaskableState> = MaterializedSnapshot(
        contextName = namedAggregate.contextName,
        aggregateName = namedAggregate.aggregateName,
        tenantId = "tenant",
        aggregateId = "aggregate",
        version = 1,
        eventId = "event",
        firstOperator = "operator",
        operator = "operator",
        firstEventTime = 1,
        eventTime = 1,
        state = state,
        snapshotTime = 1,
        deleted = false
    )

    private class ReplacingStateMasker(
        override val namedAggregate: NamedAggregate
    ) : StateDynamicDocumentMasker {
        var invocations: Int = 0
            private set

        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument {
            invocations++
            return mutableMapOf<String, Any?>(SECRET to MASKED).toDynamicDocument()
        }
    }

    private class ReplacingEventMasker(
        override val namedAggregate: NamedAggregate
    ) : EventStreamDynamicDocumentMasker {
        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument =
            mutableMapOf<String, Any?>(SECRET to EVENT_MASKED).toDynamicDocument()
    }

    private class ThrowingStateMasker(
        override val namedAggregate: NamedAggregate
    ) : StateDynamicDocumentMasker {
        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument = error("mask failed")
    }

    private data class MaskableState(val secret: String) : DataMasking<MaskableState> {
        override fun mask(): MaskableState = copy(secret = MASKED)
    }

    private companion object {
        const val SECRET: String = "secret"
        const val ORIGINAL: String = "original"
        const val MASKED: String = "masked"
        const val EVENT_MASKED: String = "event-masked"
    }
}
