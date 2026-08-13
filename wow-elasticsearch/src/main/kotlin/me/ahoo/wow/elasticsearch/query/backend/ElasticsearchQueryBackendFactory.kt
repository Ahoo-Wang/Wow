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

package me.ahoo.wow.elasticsearch.query.backend

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchQueryBackendFactory @JvmOverloads constructor(
    private val client: ReactiveElasticsearchClient,
    private val nativeTemplates: ElasticsearchNativeQueryTemplateRegistry =
        ElasticsearchNativeQueryTemplateRegistry(),
    private val maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED,
) : QueryBackendFactory {
    override fun bind(context: QueryBackendResolutionContext): QueryBackend {
        val index = when (context.target.documentKind) {
            QueryDocumentKind.SNAPSHOT -> context.target.namedAggregate.toSnapshotIndexName()
            QueryDocumentKind.EVENT_STREAM -> context.target.namedAggregate.toEventStreamIndexName()
        }
        val binding = ElasticsearchQueryFieldBinding.bind(context.schema)
        val requirements = ReadinessRequirementCollector(binding, nativeTemplates).collect(
            context.securedExpression,
            binding.hasAuthoritativeSystemFields(context.target.documentKind),
        )
        return ElasticsearchQueryBackend(
            client,
            index,
            binding,
            nativeTemplates,
            elasticsearchQueryBackendDescriptor(maxBudget),
            requirements,
        )
    }

    private class ReadinessRequirementCollector(
        private val binding: ElasticsearchQueryFieldBinding,
        private val nativeTemplates: ElasticsearchNativeQueryTemplateRegistry,
    ) {
        val exact = LinkedHashSet<String>()
        val search = LinkedHashSet<String>()
        val sort = LinkedHashSet<String>()
        val nested = LinkedHashSet<String>()
        val presence = LinkedHashSet<String>()
        var valid = true

        fun collect(
            expression: QueryExpression,
            configurationValid: Boolean,
        ): ElasticsearchQueryReadinessRequirements {
            valid = configurationValid
            collectSchemaRequirements()
            inspect(expression)
            return ElasticsearchQueryReadinessRequirements(
                valid,
                exact,
                search,
                sort,
                nested,
                ElasticsearchQueryPresenceEncoder.VERSION,
                presence,
            )
        }

        private fun collectSchemaRequirements() = binding.schemas().forEach { (logical, schema) ->
            if (schema.queryable && schema.valueKind !in setOf(QueryFieldValueKind.OBJECT, QueryFieldValueKind.MAP)) {
                exact += binding.physical(logical, QueryFieldUsage.EXACT)
                presence += binding.presenceField(logical)
            }
            if (schema.sortable) {
                sort += binding.physical(logical, QueryFieldUsage.SORT)
            }
            if (me.ahoo.wow.api.query.expression.QueryCapabilityId(FULL_TEXT_CAPABILITY) in schema.capabilities) {
                search += binding.physical(logical, QueryFieldUsage.SEARCH)
            }
            if (schema.elementMatchEnabled) {
                nested += binding.physical(logical, QueryFieldUsage.NESTED)
            }
        }

        private fun inspect(current: QueryExpression, relativeTo: LogicalField? = null) {
            when (current) {
                is PredicateExpression -> inspectPredicate(current, relativeTo)
                is ElementMatchExpression -> inspectElementMatch(current, relativeTo)
                is FullTextExpression -> inspectFullText(current, relativeTo)
                is NativeExpression -> inspectNative(current, relativeTo)
                is LogicalExpression -> current.operands.forEach { inspect(it, relativeTo) }
                is PortableLogicalExpression -> current.operands.forEach { inspect(it, relativeTo) }
                else -> Unit
            }
        }

        private fun inspectPredicate(current: PredicateExpression, relativeTo: LogicalField?) {
            val logical = resolve(relativeTo, current.field)
            exact += binding.physical(logical)
            val hasUnsupportedCollation = current.stringComparison == StringComparisonMode.DEFAULT &&
                current.operator.isStringOperator() && binding.schema(logical).stringOptions?.collation != null
            if (hasUnsupportedCollation) {
                valid = false
            }
        }

        private fun inspectElementMatch(current: ElementMatchExpression, relativeTo: LogicalField?) {
            val logical = resolve(relativeTo, current.field)
            nested += binding.physical(logical, QueryFieldUsage.NESTED)
            inspect(current.predicate, logical)
        }

        private fun inspectFullText(current: FullTextExpression, relativeTo: LogicalField?) {
            if (current.capabilityId.value != FULL_TEXT_CAPABILITY) {
                unsupported()
            }
            current.fields.forEach { field ->
                val logical = resolve(relativeTo, field)
                if (!binding.contains(logical) || current.capabilityId !in binding.schema(logical).capabilities) {
                    unsupported()
                }
                search += binding.physical(logical, QueryFieldUsage.SEARCH)
            }
        }

        private fun inspectNative(current: NativeExpression, relativeTo: LogicalField?) {
            val capabilityMismatch = current.capabilityId.value != NATIVE_CAPABILITY || current.backendId != BACKEND_ID
            val undeclaredField = current.declaredFields.any { field -> !binding.contains(resolve(relativeTo, field)) }
            val missingTemplate = nativeTemplates.template(current.templateId) == null
            if (capabilityMismatch || undeclaredField || missingTemplate) {
                unsupported()
            }
        }

        private fun resolve(relativeTo: LogicalField?, field: LogicalField): LogicalField =
            if (relativeTo == null) field else LogicalField("${relativeTo.value}.${field.value}")

        private fun PortableOperator.isStringOperator(): Boolean = when (this) {
            PortableOperator.CONTAINS,
            PortableOperator.STARTS_WITH,
            PortableOperator.ENDS_WITH,
            -> true

            else -> false
        }

        private fun unsupported(): Nothing = throw QueryException(
            QueryErrorCode.UNSUPPORTED_CAPABILITY,
            QueryStage.PLANNING,
            QueryErrorReason.CAPABILITY_DENIED,
        )
    }

    companion object {
        const val BACKEND_ID: String = "elasticsearch"
        const val FULL_TEXT_CAPABILITY: String = "full-text"
        const val NATIVE_CAPABILITY: String = "x-wow:elasticsearch-native"
    }
}
