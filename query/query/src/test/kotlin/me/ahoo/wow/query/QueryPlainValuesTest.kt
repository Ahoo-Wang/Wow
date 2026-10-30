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

package me.ahoo.wow.query

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetChange
import me.ahoo.wow.query.dataset.QueryDatasetScope
import me.ahoo.wow.query.dataset.QueryDatasetTest
import me.ahoo.wow.query.error.QueryBindingError
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryMask
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.Temporal
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The query engine's public types are plain values (finding R5.A-24): each is rebuilt equal, with the same hash and
 * text, by its public factory from its own properties, and one property changed makes another value.
 */
class QueryPlainValuesTest {
    @TestFactory
    fun `every query engine value should be rebuilt equal by its factory`(): List<DynamicTest> =
        SAMPLES.map { sample ->
            DynamicTest.dynamicTest(sample.javaClass.name.substringAfterLast('.')) {
                // A type whose companion's `of` builds otherwise is rebuilt by its (Wow-internal) constructor.
                val factories: List<Executable> = sample.javaClass.methods
                    .filter { Modifier.isStatic(it.modifiers) && it.name == "of" }
                    .filter { of -> of.parameters.all { hasProperty(sample, it.name) } }
                    .ifEmpty { sample.javaClass.constructors.filter { !it.isSynthetic } }
                val factory = factories.maxBy { it.parameterCount }
                val values = factory.parameters.map { property(sample, it.name) }.toTypedArray()
                val rebuilt = build(factory, values)
                rebuilt.assert().isEqualTo(sample)
                (sample == sample).assert().isTrue()
                sample.equals(Any()).assert().isFalse()
                rebuilt.hashCode().assert().isEqualTo(sample.hashCode())
                rebuilt.toString().assert().isEqualTo(sample.toString())
                other(factory, values, sample)?.assert()?.isNotEqualTo(sample)
                // Every overload of the factory, the rest of the arguments their defaults, which a type may refuse.
                factories.forEach { of ->
                    runCatching { build(of, of.parameters.map { property(sample, it.name) }.toTypedArray()) }
                }
            }
        }

    private fun build(factory: Executable, values: Array<Any?>): Any? = when (factory) {
        is Method -> factory.invoke(null, *values)
        is Constructor<*> -> factory.newInstance(*values)
        else -> error("Not a factory: $factory")
    }

    /** A value of the type that differs from the one [values] build: one property changed, the first that can be. */
    private fun other(factory: Executable, values: Array<Any?>, sample: Any): Any? =
        values.indices.firstNotNullOfOrNull {
            val changed = when (val value = values[it]) {
                is String -> "${value}x"
                is Int -> value + 1
                is Long -> value + 1
                is Boolean -> !value
                is QueryField<*> -> QueryField("${value.path}x")
                else -> return@firstNotNullOfOrNull null
            }
            runCatching { build(factory, values.copyOf().also { copy -> copy[it] = changed }) }.getOrNull()
                ?.takeIf { rebuilt -> rebuilt != sample }
        }

    private fun getter(target: Any, name: String): Method? {
        val capitalized = name.replaceFirstChar { it.uppercase() }
        return target.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf("get$capitalized", name, "is$capitalized")
        }
    }

    private fun hasProperty(target: Any, name: String): Boolean = getter(target, name) != null

    private fun property(target: Any, name: String): Any? = getter(target, name)!!.invoke(target)

    private companion object {
        val FIELD = QueryField("a")
        val ORDERS: QueryDataset = QueryDatasetTest.orders()

        val SAMPLES: List<Any> = listOf(
            PageWindow.Offset.of(offset = 1, limit = 2, withTotal = true, totalLimit = 3),
            PageWindow.Keyset.of(after = CursorPosition(listOf("a")), limit = 2),
            GroupWindow.First.of(limit = 2),
            QueryBackendBinding.of(mockk<QueryBackend>(), mockk<QueryStorageAdapter>()),
            QueryBindingError.of(name = "a", msg = "b", code = "c"),
            QueryEntryPolicy.of(requireExplicitEntry = true),
            QueryScope.of(authenticated = MatchAllFilter),
            ORDERS,
            QueryDatasetScope.of(tenant = FIELD),
            QueryDatasetChange.Added.of(ORDERS),
            QueryDatasetChange.Removed.of(ORDERS),
            QueryDatasetChange.Replaced.of(ORDERS, ORDERS),
            QueryContext.of(
                ListQuery(MatchAllFilter),
                AggregateType("a", "b"),
                mockk<QueryModelSchema>(),
                QueryType.entries.first(),
                QueryEntry.UNSPECIFIED,
            ),
            QueryIndexDeclaration("a", listOf("b")),
            DeclarationValue.Set.of("a"),
            QueryFieldDeclaration.of(title = DeclarationValue.Set.of("a")),
            QuerySchemaDeclaration.of(mapOf(FIELD to QueryFieldDeclaration.of())),
            QueryMask.Keep.of(prefix = 1, suffix = 2),
            QueryMask.Strategy.of("a"),
            Temporal.Epoch.of(),
            Temporal.Formatted.of("yyyy"),
            QueryViolation.UnknownField.of(FIELD),
            QueryViolation.UnsupportedCapability.of(FIELD, setOf(QueryCapability.entries.first())),
            QueryViolation.ElementScopeRequired.of(FIELD),
            QueryViolation.ValueMismatch.of(FIELD),
            QueryViolation.NotCollection.of(FIELD),
            QueryViolation.NotSingleString.of(FIELD),
            QueryViolation.CursorNotAllowed.of(FIELD),
            QueryViolation.ProtectedAggregation.of(FIELD),
            QueryViolation.ProtectedComparison.of(FIELD),
            QueryViolation.MissingKeyRequiresString.of(FIELD),
            QueryViolation.MetricFilterArrayField.of(FIELD),
            QueryViolation.NotProjectable.of(FIELD),
            QueryViolation.EventProjectionTypeRequired.of(FIELD),
            QueryViolation.TemporalRepresentationRequired.of(FIELD),
            QueryViolation.TemporalConfigurationConflict.of(FIELD),
            QueryViolation.TemporalAggregationUnsupported.of(FIELD),
            QueryViolation.FirstLastRequiresSingleValue.of(FIELD),
            QueryViolation.FirstLastRequiresOrderBy.of(FIELD),
            QueryViolation.ParallelArraySort.of(FIELD, QueryField("b")),
            QueryViolation.ArrayEquality.of(FIELD),
            QueryViolation.SortTooMany.of(1),
            QueryViolation.DuplicateSortField.of(FIELD),
            QueryViolation.IdentityUndefined.of(QueryModel.SNAPSHOT),
            QueryViolation.StorageUnsupported.of("a", FIELD),
            QueryViolation.PreviousUnshiftable.of(FIELD),
            QueryViolation.SizeOutOfRange.of("a", "b", 1, 0, 2, "c"),
            QueryViolation.FilterTooLarge.of("a", "b", 2, 1, "c"),
            QueryViolation.ExpensiveOperatorDisabled.of("a", "b", true, "c"),
            QueryViolation.ExactCountDisabled.of("a"),
            QueryViolation.CountRequiresFilter.of("a"),
            QueryViolation.ResidualGroupsExceeded.of("a", 1),
            QueryViolation.CursorSortDuplicate.of(FIELD),
            QueryViolation.CursorSortTooMany.of(1),
        )
    }
}
