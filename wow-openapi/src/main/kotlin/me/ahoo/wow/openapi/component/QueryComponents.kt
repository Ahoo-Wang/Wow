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

package me.ahoo.wow.openapi.component

import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.QueryComponent
import me.ahoo.wow.openapi.component.CommonComponents.withErrorCodeHeader
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.InferredQuerySchemaSource
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.SystemQuerySchemaSource
import me.ahoo.wow.schema.query.JsonQueryModelSource
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

private val staticQuerySchemaSource = InferredQuerySchemaSource(JsonQueryModelSource())

/**
 * The `{aggregate}.{Aggregate}AggregatedFields` schema component: every field path a query on the aggregate's
 * snapshots can name. Inferring it generates the aggregate's JSON Schema, so it runs only when the document is
 * rendered.
 */
internal fun OpenAPIComponentContext.aggregatedFieldsSchema(
    aggregateMetadata: AggregateMetadata<*, *>,
    querySchemaSource: QuerySchemaSource = staticQuerySchemaSource,
): Schema<*> {
    val context = QuerySchemaContext(
        namedAggregate = aggregateMetadata.namedAggregate,
        model = QueryModel.SNAPSHOT,
    )
    val inferred = checkNotNull(querySchemaSource.load(context).blockFirst())
    val fields = buildSet {
        fun addFields(field: QueryField, declaration: QueryFieldDeclaration) {
            add(field)
            (declaration.properties as? DeclarationValue.Set)?.value.orEmpty().forEach { (name, child) ->
                addFields(field.append(QueryField(name)), child)
            }
            (declaration.items as? DeclarationValue.Set)?.value?.let { addFields(field, it) }
            (declaration.alternatives as? DeclarationValue.Set)?.value.orEmpty().forEach { addFields(field, it) }
        }
        SystemQuerySchemaSource.declaration(
            QueryModel.SNAPSHOT
        ).fields.forEach { (field, declaration) -> addFields(field, declaration) }
        inferred.fields.forEach { (field, declaration) -> addFields(field, declaration) }
    }.map(QueryField::path).sorted()
    val key = "${aggregateMetadata.toStringWithAlias()}." +
        "${aggregateMetadata.command.aggregateType.simpleName}${QueryComponent.AGGREGATED_FIELDS_SUFFIX}"
    return componentSchema(key, StringSchema()._enum(fields))
}

/** The components of the query routes: the query request bodies and the count response. */
internal object QueryComponents {
    val countQueryRequestBody = queryRequestBody(QueryComponent.COUNT_QUERY_KEY, FilterExpression::class.java)
    val listQueryRequestBody = queryRequestBody(QueryComponent.LIST_QUERY_KEY, ListQuery::class.java)
    val pagedQueryRequestBody = queryRequestBody(QueryComponent.PAGED_QUERY_KEY, PagedQuery::class.java)
    val cursorQueryRequestBody = queryRequestBody(QueryComponent.CURSOR_QUERY_KEY, CursorQuery::class.java)
    val singleQueryRequestBody = queryRequestBody(QueryComponent.SINGLE_QUERY_KEY, SingleQuery::class.java)
    val aggregationQueryRequestBody =
        queryRequestBody(QueryComponent.AGGREGATION_QUERY_KEY, AggregationQuery::class.java)

    val countQueryResponse = HttpResponse(
        statusCode = Https.Code.OK,
        component = HttpComponent.response(QueryComponent.COUNT_QUERY_KEY) { context ->
            withErrorCodeHeader(context)
            content(Https.MediaType.APPLICATION_JSON, schema = context.schema(Long::class.java))
        }
    )

    fun aggregatedCountQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): HttpRequestBody =
        aggregatedQueryRequestBody(aggregateMetadata, QueryComponent.COUNT_QUERY_SUFFIX, FilterExpression::class.java)

    fun aggregatedListQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): HttpRequestBody =
        aggregatedQueryRequestBody(aggregateMetadata, QueryComponent.LIST_QUERY_SUFFIX, ListQuery::class.java)

    fun aggregatedPagedQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): HttpRequestBody =
        aggregatedQueryRequestBody(aggregateMetadata, QueryComponent.PAGED_QUERY_SUFFIX, PagedQuery::class.java)

    fun aggregatedCursorQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): HttpRequestBody =
        aggregatedQueryRequestBody(aggregateMetadata, QueryComponent.CURSOR_QUERY_SUFFIX, CursorQuery::class.java)

    fun aggregatedSingleQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): HttpRequestBody =
        aggregatedQueryRequestBody(aggregateMetadata, QueryComponent.SINGLE_QUERY_SUFFIX, SingleQuery::class.java)

    fun aggregatedAggregationQueryRequestBody(aggregateMetadata: AggregateMetadata<*, *>): HttpRequestBody =
        aggregatedQueryRequestBody(
            aggregateMetadata,
            QueryComponent.AGGREGATION_QUERY_SUFFIX,
            AggregationQuery::class.java
        )

    private fun queryRequestBody(key: String, queryType: Type): HttpRequestBody = HttpRequestBody(
        component = HttpComponent.requestBody(key) { context ->
            content(schema = context.schema(queryType))
        }
    )

    /**
     * The `{aggregate}{suffix}` request body: the query schema plus the [QueryComponent.QUERY_FIELDS_EXTENSION]
     * extension naming the aggregate's queryable fields.
     */
    private fun aggregatedQueryRequestBody(
        aggregateMetadata: AggregateMetadata<*, *>,
        suffix: String,
        queryType: Type,
    ): HttpRequestBody = aggregatedQueryRequestBodies.computeIfAbsent(aggregateMetadata to suffix) {
        HttpRequestBody(
            component = HttpComponent.requestBody(aggregateMetadata.toStringWithAlias() + suffix) { context ->
                extension(QueryComponent.QUERY_FIELDS_EXTENSION, context.aggregatedFieldsSchema(aggregateMetadata))
                content(schema = context.schema(queryType))
            }
        )
    }

    /** One component per aggregate and query, shared by the routes that use it (its key must stay unique). */
    private val aggregatedQueryRequestBodies =
        ConcurrentHashMap<Pair<AggregateMetadata<*, *>, String>, HttpRequestBody>()
}
