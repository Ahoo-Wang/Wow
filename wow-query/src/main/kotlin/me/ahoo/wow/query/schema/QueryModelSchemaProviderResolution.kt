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

@file:JvmName("QuerySchemaResolverKt")
@file:JvmMultifileClass
@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.*
import me.ahoo.wow.serialization.state.StateAggregateRecords
import reactor.core.publisher.Mono

fun QueryModelSchemaProvider.resolve(
    query: ISingleQuery,
    mode: QuerySchemaValidationMode,
): Mono<ISingleQuery> = schemaForQuery()
    .map { it.resolve(query).requireAccepted(mode) }
    .fallbackUnavailable(mode, query, query.filter)

fun QueryModelSchemaProvider.resolve(
    query: IListQuery,
    mode: QuerySchemaValidationMode,
): Mono<IListQuery> = schemaForQuery()
    .map { it.resolve(query).requireAccepted(mode) }
    .fallbackUnavailable(mode, query, query.filter)

fun QueryModelSchemaProvider.resolve(
    query: IPagedQuery,
    mode: QuerySchemaValidationMode,
): Mono<IPagedQuery> = schemaForQuery()
    .map { it.resolve(query).requireAccepted(mode) }
    .fallbackUnavailable(mode, query, query.filter)

fun QueryModelSchemaProvider.resolve(
    query: ICursorQuery,
    mode: QuerySchemaValidationMode,
): Mono<ICursorQuery> = schemaForQuery().map { it.resolve(query).requireAccepted(mode) }

fun QueryModelSchemaProvider.resolve(
    filter: FilterExpression,
    mode: QuerySchemaValidationMode,
): Mono<FilterExpression> = schemaForQuery()
    .map { it.resolve(filter).requireAccepted(mode) }
    .fallbackUnavailable(mode, filter, filter)

fun QueryModelSchemaProvider.resolve(
    query: AggregationQuery,
    mode: QuerySchemaValidationMode,
): Mono<ResolvedAggregationQuery> = schemaForQuery()
    .map { schema ->
        ResolvedAggregationQuery(
            schema.resolve(query).requireAccepted(mode),
            schema,
        )
    }

@Suppress("CyclomaticComplexMethod")
private fun FilterExpression.referencesSystemTags(logicalParent: QueryField? = null): Boolean = when (this) {
    MatchAllFilter,
    MatchNoneFilter,
    is IdFilter,
    is IdsFilter,
    is AggregateIdFilter,
    is AggregateIdsFilter,
    is TenantIdFilter,
    is OwnerIdFilter,
    is SpaceIdFilter,
    is DeletionFilter,
    -> false
    is AndFilter -> operands.any { it.referencesSystemTags(logicalParent) }
    is OrFilter -> operands.any { it.referencesSystemTags(logicalParent) }
    is NorFilter -> operands.any { it.referencesSystemTags(logicalParent) }
    is ElementMatchFilter -> field.absoluteTo(logicalParent).let { element ->
        element.isSystemTags() || predicate.referencesSystemTags(element)
    }
    is SearchFilter -> fields.any { it.absoluteTo(logicalParent).isSystemTags() }
    is RelativeTimeFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is EqualFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is NotEqualFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is InFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is NotInFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is ContainsAllFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is ContainsFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is StartsWithFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is EndsWithFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is GreaterThanFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is GreaterThanOrEqualFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is LessThanFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is LessThanOrEqualFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is BetweenFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is IsEmptyFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is IsEmptyStringFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is IsNotEmptyStringFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is IsNullFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is IsNotNullFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is ExistsFilter -> field.absoluteTo(logicalParent).isSystemTags()
    is NotExistsFilter -> field.absoluteTo(logicalParent).isSystemTags()
}

private fun QueryField.isSystemTags(): Boolean =
    path == StateAggregateRecords.TAGS || path.startsWith("${StateAggregateRecords.TAGS}.")

private fun <T : Any> Mono<T>.fallbackUnavailable(
    mode: QuerySchemaValidationMode,
    fallback: T,
    filter: FilterExpression,
): Mono<T> = onErrorResume(QuerySchemaUnavailableException::class.java) { error ->
    if (mode == QuerySchemaValidationMode.COMPATIBLE && !filter.referencesSystemTags()) {
        Mono.just(fallback)
    } else {
        Mono.error(error)
    }
}
