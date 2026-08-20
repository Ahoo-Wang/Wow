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

package me.ahoo.wow.api.query.gateway

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap

class QueryRequestTest {
    @Test
    fun `request should snapshot target sort projection and page items`() {
        var contextName = "sales"
        val aggregate = object : NamedAggregate {
            override val contextName: String get() = contextName
            override val aggregateName: String = "order"
        }
        val target = QueryTarget(aggregate, QueryDocumentKind.SNAPSHOT)
        val sort = mutableListOf(QuerySort(LogicalField("amount"), QuerySortDirection.DESC))
        val projectedFields = mutableSetOf(LogicalField("amount"))
        val projection = QueryProjection.Include(projectedFields)
        val request = ListQueryRequest(
            target = target,
            resultShape = QueryResultShape.Typed(
                String::class.java,
                projection
            ),
            sort = sort,
            limit = 0
        )
        contextName = "changed"
        sort.clear()
        projectedFields.clear()

        request.target.namedAggregate.contextName.assert().isEqualTo("sales")
        request.sort.assert().hasSize(1)
        projection.fields.assert().containsExactly(LogicalField("amount"))
        request.limit.assert().isZero()
        assertThrows<UnsupportedOperationException> {
            (request.sort as MutableList).clear()
        }

        val items = mutableListOf("one")
        val page = QueryPage(items, 1, QueryConsistency.EXACT)
        items.clear()
        page.items.assert().containsExactly("one")
        assertThrows<UnsupportedOperationException> { (page.items as MutableList).clear() }
    }

    @Test
    fun `request and target should expose ordered data class source surface`() {
        val original = ListQueryRequest(
            target = target(),
            resultShape = QueryResultShape.Typed(String::class.java),
            sort = listOf(QuerySort(LogicalField("amount"), QuerySortDirection.ASC)),
            limit = 4
        )
        val (target, expression, shape) = original
        val scope = original.component4()
        val budget = original.component5()
        val sort = original.component6()
        val limit = original.component7()
        val replacementSort = mutableListOf(QuerySort(LogicalField("name"), QuerySortDirection.DESC))
        val copied = original.copy(sort = replacementSort, limit = 0)
        replacementSort.clear()

        target.assert().isEqualTo(original.target)
        expression.assert().isEqualTo(original.expression)
        shape.assert().isEqualTo(original.resultShape)
        scope.assert().isEqualTo(original.requestedScope)
        budget.assert().isEqualTo(original.budget)
        sort.assert().isEqualTo(original.sort)
        limit.assert().isEqualTo(4)
        copied.sort.assert().hasSize(1)
        copied.limit.assert().isZero()
        ListQueryRequest::class.java.getDeclaredMethod("component1").returnType.assert()
            .isEqualTo(QueryTarget::class.java)
        ListQueryRequest::class.java.getDeclaredMethod("component6").returnType.assert()
            .isEqualTo(List::class.java)
        ListQueryRequest::class.java.declaredMethods.any { it.name == "copy" }.assert().isTrue()

        val queryTarget = target()
        val (namedAggregate, documentKind) = queryTarget
        queryTarget.copy(documentKind = QueryDocumentKind.EVENT_STREAM).documentKind.assert()
            .isEqualTo(QueryDocumentKind.EVENT_STREAM)
        namedAggregate.contextName.assert().isEqualTo("sales")
        documentKind.assert().isEqualTo(QueryDocumentKind.SNAPSHOT)
        QueryTarget::class.java.getDeclaredMethod("component1").returnType.assert()
            .isEqualTo(NamedAggregate::class.java)
        QueryTarget::class.java.declaredMethods.any { it.name == "copy" }.assert().isTrue()
    }

    @Test
    fun `projection and page should expose ordered data class source surface`() {
        val projectionFields = mutableSetOf(LogicalField("amount"))
        val projection = QueryProjection.Include(projectionFields)
        val (fields) = projection
        val projectionCopy = projection.copy(fields = projectionFields)
        projectionFields.clear()
        fields.assert().containsExactly(LogicalField("amount"))
        projectionCopy.fields.assert().containsExactly(LogicalField("amount"))
        QueryProjection.Include::class.java.getDeclaredMethod("component1").returnType.assert()
            .isEqualTo(Set::class.java)
        QueryProjection.Include::class.java.declaredMethods.any { it.name == "copy" }.assert().isTrue()

        val pageItems = mutableListOf("one")
        val page = QueryPage(pageItems, 1, QueryConsistency.EXACT)
        val (items, total, consistency) = page
        val pageCopy = page.copy(items = pageItems)
        pageItems.clear()
        items.assert().containsExactly("one")
        total.assert().isEqualTo(1)
        consistency.assert().isEqualTo(QueryConsistency.EXACT)
        pageCopy.items.assert().containsExactly("one")
        QueryPage::class.java.getDeclaredMethod("component1").returnType.assert().isEqualTo(List::class.java)
        QueryPage::class.java.declaredMethods.any { it.name == "copy" }.assert().isTrue()
    }

    @Test
    fun `projection sets should reject field cardinality loss in direct and copy construction`() {
        val field = LogicalField("ordinaryField")
        val include = QueryProjection.Include(setOf(field))
        val exclude = QueryProjection.Exclude(setOf(field))
        val identityFields = identityFields()
        identityFields.assert().hasSize(2)

        assertAll(
            { assertSensitiveCardinalityRejected { QueryProjection.Include(identityFields) } },
            { assertSensitiveCardinalityRejected { include.copy(fields = identityFields) } },
            { assertSensitiveCardinalityRejected { QueryProjection.Exclude(identityFields) } },
            { assertSensitiveCardinalityRejected { exclude.copy(fields = identityFields) } }
        )
        include.fields.assert().containsExactly(field)
        exclude.fields.assert().containsExactly(field)
    }

    @Test
    fun `request boundaries should reject invalid budgets pages limits and totals`() {
        assertThrows<IllegalArgumentException> { QueryBudgetHint(timeout = Duration.ofNanos(-1)) }
        assertThrows<IllegalArgumentException> { QueryBudgetHint(maxResults = -1) }
        assertThrows<IllegalArgumentException> { QueryBudgetHint(maxCost = -1) }
        assertThrows<IllegalArgumentException> { QueryPageSpec(index = 0, size = 10) }
        assertThrows<IllegalArgumentException> { QueryPageSpec(index = 1, size = 0) }
        assertThrows<IllegalArgumentException> {
            ListQueryRequest(target(), MatchAll, QueryResultShape.Typed(String::class.java), limit = -1)
        }
        assertThrows<IllegalArgumentException> { QueryPage(emptyList<String>(), -1, QueryConsistency.EXACT) }
        assertThrows<IllegalArgumentException> { QueryPage(listOf("one"), 0, QueryConsistency.EXACT) }
    }

    @Test
    fun `requested scope should reject blank identifiers`() {
        assertThrows<IllegalArgumentException> { RequestedQueryScope(tenantId = "") }
        assertThrows<IllegalArgumentException> { RequestedQueryScope(ownerId = " ") }
        assertThrows<IllegalArgumentException> { RequestedQueryScope(spaceId = "") }
    }

    @Test
    fun `immutable dynamic document should deeply snapshot and reject mutation`() {
        val bytes = byteArrayOf(1, 2)
        val nested = mutableMapOf<String, Any?>("bytes" to bytes)
        val values = mutableListOf<Any?>(nested)
        val source = mutableMapOf<String, Any?>("values" to values)
        val document = ImmutableDynamicDocument.copyOf(source)

        bytes[0] = 9
        nested.clear()
        values.clear()
        source.clear()

        val nestedDocument = (document["values"] as List<*>).single() as ImmutableDynamicDocument
        assertArrayEquals(byteArrayOf(1, 2), nestedDocument["bytes"] as ByteArray)
        val exposed = nestedDocument["bytes"] as ByteArray
        exposed[0] = 7
        assertArrayEquals(byteArrayOf(1, 2), nestedDocument["bytes"] as ByteArray)
        assertThrows<UnsupportedOperationException> { document["new"] = "value" }
        assertThrows<UnsupportedOperationException> { document.clear() }
    }

    @Test
    fun `public query exception should contain only safe dimensions`() {
        val exception = QueryException(
            QueryErrorCode.POLICY_DENIED,
            QueryStage.POLICY,
            QueryErrorReason.TENANT_SCOPE_DENIED
        )

        exception.code.assert().isEqualTo(QueryErrorCode.POLICY_DENIED)
        exception.stage.assert().isEqualTo(QueryStage.POLICY)
        exception.reason.name.assert().isEqualTo("TENANT_SCOPE_DENIED")
        exception.cause.assert().isNull()
        exception.message.assert().isEqualTo("POLICY_DENIED:POLICY:TENANT_SCOPE_DENIED")
        assertThrows<IllegalArgumentException> {
            QueryErrorReason.valueOf("TENANT_0123456789ABCDEF0123456789ABCDEF")
        }
        assertThrows<IllegalStateException> {
            exception.initCause(IllegalStateException("backend secret"))
        }
        exception.addSuppressed(IllegalStateException("authority secret"))
        exception.suppressed.assert().isEmpty()
    }

    private fun target() = QueryTarget(
        object : NamedAggregate {
            override val contextName: String = "sales"
            override val aggregateName: String = "order"
        },
        QueryDocumentKind.SNAPSHOT
    )

    @Suppress("IDENTITY_SENSITIVE_OPERATIONS_WITH_VALUE_TYPE")
    private fun identityFields(): Set<LogicalField> =
        Collections.newSetFromMap(IdentityHashMap<LogicalField, Boolean>()).apply {
            add(LogicalField("sensitiveField"))
            add(LogicalField("sensitiveField"))
        }

    private fun assertSensitiveCardinalityRejected(factory: () -> Any) {
        val error = assertThrows<IllegalArgumentException> { factory() }
        error.message.assert().doesNotContain("sensitive")
    }
}
