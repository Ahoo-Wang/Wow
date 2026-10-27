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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.admission.QueryOperation
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.PagingSupport
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SnapshotQueryModelProfile
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.query.schema.systemField
import me.ahoo.wow.query.spec.SystemField
import me.ahoo.wow.spi.json.query.SnapshotFields
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class QueryResolverTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "deleted" to scalarFixture(QueryValueType.BOOLEAN),
            "state" to objectFixture(
                "name" to scalarFixture(),
                "buyer" to QueryValueSchema(
                    kind = scalarFixture().kind,
                    valueTypes = scalarFixture().valueTypes,
                    aliases = setOf(QueryField("state.customer")),
                ),
                "items" to arrayFixture(objectFixture("qty" to scalarFixture(QueryValueType.INTEGER))),
            ),
        ),
    )
    private val text = "x"

    private fun rejection(block: () -> Unit): QueryViolation =
        checkNotNull(assertThrows<QuerySchemaValidationException> { block() }.violation)

    private fun sortBy(vararg fields: String): ListQuery =
        ListQuery(MatchAllFilter, sort = fields.map { Sort(QueryField(it), Sort.Direction.ASC) })

    @Test
    fun `every sort is limited to the storage-wide field count`() {
        val many = (0..Sort.MAX_FIELDS).map { Sort(QueryField("state.name$it"), Sort.Direction.ASC) }
        assertThrows<IllegalArgumentException> { ListQuery(MatchAllFilter, sort = many) }
            .message.assert().isEqualTo("sort must contain at most ${Sort.MAX_FIELDS} fields.")
        assertThrows<IllegalArgumentException> { SingleQuery(MatchAllFilter, sort = many) }
        assertThrows<IllegalArgumentException> { PagedQuery(MatchAllFilter, sort = many) }
    }

    @Test
    fun `two sort fields bound to one physical field are rejected for every query type`() {
        rejection { QueryAdmission.Trusted.list(sortBy("state.name", "state.name"), schema) }
            .assert().isEqualTo(QueryViolation.DuplicateSortField(QueryField("state.name")))
        // An alias names its canonical field, so it cannot sort by the same field twice either.
        rejection { QueryAdmission.Trusted.list(sortBy("state.buyer", "state.customer"), schema) }
            .assert().isEqualTo(QueryViolation.DuplicateSortField(QueryField("state.buyer")))
        QueryAdmission.Trusted.list(sortBy("state.name", "state.buyer"), schema).query.sort.map { it.field.path }
            .assert().containsExactly("state.name", "state.buyer")
    }

    @Test
    fun `violations name the absolute logical field, in element scopes too`() {
        val inside = ElementMatchFilter(QueryField("state.items"), EqualFilter(QueryField("qty"), text))
        rejection { QueryAdmission.Trusted.count(CountQuery(inside), schema) }
            .assert().isEqualTo(QueryViolation.ValueMismatch(QueryField("state.items.qty")))
        rejection { QueryAdmission.Trusted.count(CountQuery(EqualFilter(QueryField("state.unknown"), text)), schema) }
            .assert().isEqualTo(QueryViolation.UnknownField(QueryField("state.unknown")))
        rejection { QueryAdmission.Trusted.count(CountQuery(EqualFilter(QueryField("state.items.qty"), 1)), schema) }
            .assert().isEqualTo(QueryViolation.ElementScopeRequired(QueryField("state.items.qty")))
    }

    @Test
    fun `lowered predicates keep the field they were resolved for`() {
        val admitted = QueryAdmission.Trusted.count(CountQuery(EqualFilter(QueryField("state.customer"), null)), schema)
        val lowered = admitted.query.filter as IsNullFilter
        lowered.field.assert().isEqualTo(QueryField("state.buyer"))
        admitted.field(lowered.field).logicalField.assert().isEqualTo(QueryField("state.buyer"))
    }

    @Test
    fun `a policy restriction must still fit the model`() {
        val policy = QueryPolicy { _, _ -> EqualFilter(QueryField("state.name"), text) }
        val admission = QueryAdmission(AggregateType("resolver", "order"), policies = listOf(policy))
        lateinit var admitted: AdmittedQuery<CountQuery>
        runTest {
            admitted = admission.admit(
                QueryOperation.COUNT,
                CountQuery(EqualFilter(QueryField("state.buyer"), text)) as CountQuery,
                QueryCaller.IN_PROCESS,
                { schema },
            )
        }
        admitted.query.toString().assert().contains("state.name")

        // Trust covers the protection gates only: a policy restriction must still fit the model.
        val unknown = QueryPolicy { _, _ -> EqualFilter(QueryField("state.unknown"), text) }
        val error = runCatching {
            runTest {
                QueryAdmission(AggregateType("resolver", "order"), policies = listOf(unknown)).admit(
                    QueryOperation.COUNT,
                    CountQuery(EqualFilter(QueryField("state.name"), text)) as CountQuery,
                    QueryCaller.IN_PROCESS,
                    { schema },
                )
            }
        }.exceptionOrNull() as QuerySchemaValidationException
        error.violation.assert().isEqualTo(QueryViolation.UnknownField(QueryField("state.unknown")))
    }

    @Test
    fun `one profile maps every system field`() {
        SnapshotQueryModelProfile.systemField(SystemField.IDENTITY).path.assert().isEqualTo(SnapshotFields.AGGREGATE_ID)
        SnapshotQueryModelProfile.systemField(SystemField.AGGREGATE_ID).path
            .assert().isEqualTo(SnapshotFields.AGGREGATE_ID)
        SnapshotQueryModelProfile.systemField(SystemField.TENANT_ID).path.assert().isEqualTo(SnapshotFields.TENANT_ID)
        SnapshotQueryModelProfile.systemField(SystemField.SPACE_ID).path.assert().isEqualTo(SnapshotFields.SPACE_ID)
        SnapshotQueryModelProfile.systemField(SystemField.DELETED).path.assert().isEqualTo(SnapshotFields.DELETED)
        SnapshotQueryModelProfile.requiredScope.assert().isEqualTo(
            QueryModelProfile.metadataField(SystemField.TENANT_ID)
        )
        SnapshotQueryModelProfile.eventTimeField.path.assert().isEqualTo(SnapshotFields.LAST_EVENT_TIME)
        SnapshotQueryModelProfile.firstEventTimeField.path.assert().isEqualTo(SnapshotFields.FIRST_EVENT_TIME)
        QueryModelProfile.of(QueryModel("custom")).assert().isNull()
        val custom = boundSchemaFixture(objectFixture("tenantId" to scalarFixture()), model = QueryModel("custom"))
        custom.systemField(SystemField.OWNER_ID).path.assert().isEqualTo(SnapshotFields.OWNER_ID)
        assertThrows<QuerySchemaValidationException> { custom.systemField(SystemField.IDENTITY) }
            .violation.assert().isEqualTo(QueryViolation.IdentityUndefined(QueryModel("custom")))
        assertThrows<IllegalStateException> { QueryModelProfile.metadataField(SystemField.IDENTITY) }
    }

    @Test
    fun `a cursor needs a model identity`() {
        val custom = boundSchemaFixture(objectFixture("name" to scalarFixture()), model = QueryModel("custom"))
        rejection { QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), custom) }
            .assert().isEqualTo(QueryViolation.IdentityUndefined(QueryModel("custom")))
    }

    @Test
    fun `storage paging support bounds what a query may ask for`() {
        val limited = schema.withStorage(
            StorageSupport(
                paging = PagingSupport(
                    keyset = SupportMode.NONE,
                    unboundedStream = SupportMode.NONE,
                    maxOffsetWindow = 100,
                ),
            ),
        )
        rejection { QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), limited) }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("listing without a limit"))
        QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, limit = 10), limited).query.limit.assert().isEqualTo(10)
        rejection { QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), limited) }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("cursor queries"))
        QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, pagination = Pagination(10, 10)), limited)
        assertThrows<QueryRequestException> {
            QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, pagination = Pagination(11, 10)), limited)
        }.message.assert().isEqualTo("Storage page window[110] must not exceed 100.")
    }

    @Test
    fun `projection resolves its fields and needs a projectable source`() {
        val admitted = QueryAdmission.Trusted.list(
            ListQuery(
                MatchAllFilter,
                projection = Projection(
                    include = listOf(QueryField("state.customer")),
                    exclude = listOf(QueryField("state.name")),
                ),
                limit = 1,
            ),
            schema,
        )
        admitted.query.projection.include.assert().containsExactly(QueryField("state.buyer"))
        admitted.physicalPath(admitted.query.projection.exclude.single()).assert().isEqualTo("native.state.name")
        rejection {
            QueryAdmission.Trusted.list(
                ListQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("state.unknown")))),
                schema,
            )
        }.assert().isEqualTo(QueryViolation.NotProjectable(QueryField("state.unknown")))
        val partial = QueryModelSchema(
            schema.model,
            schema.capabilities,
            schema.definition,
            schema.bindings,
            fullProjectionAvailable = false,
        )
        rejection { QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, limit = 1), partial) }
            .assert().isEqualTo(QueryViolation.IncompleteProjection)
    }

    @Test
    fun `a model-wide search needs the model capability, a field search the field's`() {
        rejection { QueryAdmission.Trusted.count(CountQuery(SearchFilter("text")), schema) }
            .assert().isEqualTo(QueryViolation.ModelSearchUnsupported)
        val searchable = boundSchemaFixture(schema.root, capabilities = setOf(QueryCapability.FULL_TEXT_TERMS))
        QueryAdmission.Trusted.count(CountQuery(SearchFilter("text")), searchable).query.filter
            .assert().isEqualTo(SearchFilter("text"))
        val admitted = QueryAdmission.Trusted.count(
            CountQuery(SearchFilter("text", setOf(QueryField("state.customer")))),
            schema,
        )
        val search = admitted.query.filter as SearchFilter
        search.fields.assert().containsExactly(QueryField("state.buyer"))
        admitted.field(search.fields.single()).capability.assert().isEqualTo(QueryCapability.FULL_TEXT_TERMS)
    }

    private val epoch = scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS))
    private val rules = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "ownerId" to scalarFixture(),
            "spaceId" to scalarFixture(),
            "deleted" to scalarFixture(QueryValueType.BOOLEAN),
            "name" to scalarFixture(),
            "count" to scalarFixture(QueryValueType.INTEGER),
            "at" to epoch,
            "tags" to arrayFixture(scalarFixture()),
            "nums" to arrayFixture(scalarFixture(QueryValueType.INTEGER)),
            "lines" to arrayFixture(objectFixture("qty" to scalarFixture(QueryValueType.INTEGER))),
        ),
    )

    private fun count(filter: FilterExpression) = QueryAdmission.Trusted.count(CountQuery(filter), rules).query.filter

    private fun aggregate(query: AggregationQuery) = QueryAdmission.Trusted.aggregate(query, rules)

    @Test
    fun `each operator checks its values by its value rule`() {
        val name = QueryField("name")
        val number = QueryField("count")
        val nums = QueryField("nums")
        rejection { count(ContainsAllFilter(name, listOf("a"))) }.assert().isEqualTo(QueryViolation.NotCollection(name))
        rejection { count(ContainsAllFilter(nums, listOf("a"))) }.assert().isEqualTo(QueryViolation.ValueMismatch(nums))
        count(ContainsAllFilter(QueryField("tags"), listOf("a"))).assert().isInstanceOf(ContainsAllFilter::class.java)
        rejection { count(IsEmptyFilter(name)) }.assert().isEqualTo(QueryViolation.NotCollection(name))
        count(IsEmptyFilter(QueryField("tags"))).assert().isInstanceOf(IsEmptyFilter::class.java)
        rejection { count(IsEmptyStringFilter(number)) }.assert().isEqualTo(QueryViolation.NotSingleString(number))
        listOf(
            GreaterThanFilter(number, "x"),
            GreaterThanOrEqualFilter(number, "x"),
            LessThanFilter(number, "x"),
            LessThanOrEqualFilter(number, "x"),
            BetweenFilter(number, 1, "x"),
            InFilter(number, listOf("x")),
        ).forEach { filter ->
            rejection { count(filter) }.assert().isEqualTo(QueryViolation.ValueMismatch(number))
        }
        count(BetweenFilter(number, 1, 2)).assert().isEqualTo(BetweenFilter(number, 1, 2))
    }

    @Test
    fun `relative time needs the field to store time, as the filter says`() {
        rejection { count(TodayFilter(QueryField("count"))) }
            .assert().isEqualTo(QueryViolation.TemporalRepresentationRequired(QueryField("count")))
        rejection { count(TodayFilter(QueryField("at"), datePattern = "yyyy-MM-dd")) }
            .assert().isEqualTo(QueryViolation.TemporalConfigurationConflict(QueryField("at")))
        val today = count(TodayFilter(QueryField("at"), "UTC")) as AndFilter
        (today.operands.first() as GreaterThanOrEqualFilter).value.assert().isInstanceOf(Number::class.java)
    }

    @Test
    fun `logical and system filters resolve every operand`() {
        val name = QueryField("name")
        count(OrFilter(listOf(EqualFilter(name, "a"), EqualFilter(name, "b")))).assert()
            .isEqualTo(OrFilter(listOf(EqualFilter(name, "a"), EqualFilter(name, "b"))))
        count(NorFilter(listOf(EqualFilter(name, "a")))).assert().isEqualTo(NorFilter(listOf(EqualFilter(name, "a"))))
        listOf(
            IdsFilter(listOf("a")),
            AggregateIdFilter("a"),
            AggregateIdsFilter(listOf("a")),
            OwnerIdFilter("o"),
            SpaceIdFilter("s"),
        ).forEach { filter ->
            val admitted = QueryAdmission.Trusted.count(CountQuery(filter), rules)
            admitted.query.filter.assert().isEqualTo(filter)
            admitted.systemField(admitted.query.filter).logicalField.path.assert().isNotEmpty()
        }
    }

    @Test
    fun `a cursor sorts only by single top-level values`() {
        rejection {
            QueryAdmission.Trusted.cursor(
                CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("tags"), Sort.Direction.ASC))),
                rules,
            )
        }.assert().isEqualTo(QueryViolation.CursorNotAllowed(QueryField("tags")))
    }

    @Test
    fun `every group resolves its field and checks what it needs`() {
        val metrics = listOf(AggregationMetric.Count("c"))
        val at = QueryField("at")
        val admitted = aggregate(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Histogram(QueryField("count"), "bucket", 10.0),
                    AggregationGroup.DateHistogram(at, "day", AggregationDateUnit.DAY),
                    AggregationGroup.DatePart(at, "hour", AggregationDatePart.HOUR_OF_DAY),
                    AggregationGroup.Terms(QueryField("name"), "name", missingKey = "none"),
                    AggregationGroup.Terms(
                        alias = "doubled",
                        expression = AggregationExpression.Binary(
                            AggregationExpressionOperator.MULTIPLY,
                            AggregationExpression.Field(QueryField("count")),
                            AggregationExpression.Constant(2.0),
                        ),
                    ),
                ),
                metrics = metrics,
            ),
        )
        admitted.query.groupBy.map { group -> group.field?.let { admitted.field(it).capability } }
            .assert().containsExactly(
                QueryCapability.AGGREGATE_NUMERIC,
                QueryCapability.AGGREGATE_TEMPORAL,
                QueryCapability.AGGREGATE_TEMPORAL,
                QueryCapability.AGGREGATE_TERMS,
                null,
            )
        rejection {
            aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.Terms(QueryField("count"), "count", missingKey = "none")),
                    metrics = metrics,
                ),
            )
        }.assert().isEqualTo(QueryViolation.MissingKeyRequiresString(QueryField("count")))
        rejection {
            aggregate(
                AggregationQuery(
                    groupBy = listOf(
                        AggregationGroup.DateHistogram(QueryField("count"), "day", AggregationDateUnit.DAY)
                    ),
                    metrics = metrics,
                ),
            )
        }.assert().isEqualTo(QueryViolation.TemporalAggregationUnsupported(QueryField("count")))
    }

    @Test
    fun `a ROOT group reads the record the elements belong to`() {
        val admitted = aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("lines"))),
                groupBy = listOf(
                    AggregationGroup.Terms(QueryField("name"), "name", scope = AggregationGroupScope.ROOT),
                    AggregationGroup.Terms(QueryField("qty"), "qty"),
                ),
                metrics = listOf(AggregationMetric.Count("c")),
            ),
        )
        val (root, element) = admitted.query.groupBy
        admitted.field(checkNotNull(root.field)).logicalField.assert().isEqualTo(QueryField("name"))
        admitted.field(checkNotNull(element.field)).logicalField.assert().isEqualTo(QueryField("lines.qty"))
    }

    @Test
    fun `every metric resolves its inputs`() {
        val sum = AggregationExpression.Binary(
            AggregationExpressionOperator.ADD,
            AggregationExpression.Field(QueryField("count")),
            AggregationExpression.Constant(1.0),
        )
        val admitted = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(
                    AggregationMetric.Count("c"),
                    AggregationMetric.Percentile(sum, 50.0, "p50"),
                    AggregationMetric.DistinctCount(sum, "distinct"),
                    AggregationMetric.Derived("double", DerivedExpression.MetricRef("c")),
                ),
            ),
        )
        val percentile = admitted.query.metrics[1] as AggregationMetric.Percentile
        val field = ((percentile.expression as AggregationExpression.Binary).left as AggregationExpression.Field).field
        admitted.field(field).capability.assert().isEqualTo(QueryCapability.AGGREGATE_NUMERIC)
        admitted.query.metrics[3].assert()
            .isEqualTo(AggregationMetric.Derived("double", DerivedExpression.MetricRef("c")))
    }

    @Test
    fun `an expression in a metric filter reads only scalar fields`() {
        fun metricFilter(field: String) = AggregationQuery(
            metrics = listOf(
                AggregationMetric.Count(
                    "c",
                    ExpressionFilter(AggregationExpression.Field(QueryField(field)), ComparisonOperator.GT, 1.0),
                ),
            ),
        )
        aggregate(metricFilter("count")).query.metrics.single().filter.assert()
            .isInstanceOf(ExpressionFilter::class.java)
        rejection { aggregate(metricFilter("nums")) }
            .assert().isEqualTo(QueryViolation.MetricFilterArrayField(QueryField("nums")))
    }

    @Test
    fun `an expression a policy appends is resolved as trusted`() {
        val expression = ExpressionFilter(AggregationExpression.Field(QueryField("count")), ComparisonOperator.GT, 1.0)
        val policy = QueryPolicy { _, _ -> expression }
        lateinit var admitted: AdmittedQuery<CountQuery>
        runTest {
            admitted = QueryAdmission(AggregateType("resolver", "order"), policies = listOf(policy)).admit(
                QueryOperation.COUNT,
                CountQuery(MatchAllFilter) as CountQuery,
                QueryCaller.IN_PROCESS,
                { rules },
            )
        }
        (admitted.query.filter as AndFilter).operands.first().assert().isInstanceOf(ExpressionFilter::class.java)
    }

    @Test
    fun `a previous window needs a filter whose range it can shift`() {
        val at = QueryField("at")
        fun previous(filter: FilterExpression) = AggregationQuery(
            filter = filter,
            groupBy = listOf(AggregationGroup.DateHistogram(at, "day", AggregationDateUnit.DAY)),
            metrics = listOf(
                AggregationMetric.Count("c"),
                AggregationMetric.Derived("prev", DerivedExpression.Previous("c", "day")),
            ),
        )
        aggregate(previous(GreaterThanFilter(at, 1_000L))).query.filter.assert()
            .isInstanceOf(GreaterThanFilter::class.java)
        rejection { aggregate(previous(OrFilter(listOf(GreaterThanFilter(at, 1_000L), LessThanFilter(at, 0L))))) }
            .assert().isEqualTo(QueryViolation.PreviousUnshiftable(at))
    }

    private fun QueryModelSchema.withStorage(storage: StorageSupport) =
        QueryModelSchema(model, capabilities, definition, bindings, storage = storage)
}
