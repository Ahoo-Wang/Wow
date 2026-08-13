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
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchQueryBackendFactory @JvmOverloads constructor(
    private val client: ReactiveElasticsearchClient,
    private val nativeTemplates: ElasticsearchNativeQueryTemplateRegistry =
        ElasticsearchNativeQueryTemplateRegistry(),
    private val maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED,
) : QueryBackendFactory {
    private val binder = ElasticsearchQueryBackendBinder(client, nativeTemplates, maxBudget)

    override fun bind(context: QueryBackendResolutionContext): QueryBackend = binder.bind(context)

    companion object {
        const val BACKEND_ID: String = "elasticsearch"
        const val FULL_TEXT_CAPABILITY: String = "full-text"
        const val NATIVE_CAPABILITY: String = "x-wow:elasticsearch-native"
    }
}

internal class ElasticsearchQueryBackendBinder(
    private val client: ReactiveElasticsearchClient,
    private val nativeTemplates: ElasticsearchNativeQueryTemplateRegistry,
    private val maxBudget: QueryBudgetLimit,
) {
    fun bind(context: QueryBackendResolutionContext): QueryBackend = bind(context, null)

    fun bind(
        context: QueryBackendResolutionContext,
        preparedMappingSnapshot: ElasticsearchQueryMappingSnapshot?,
    ): QueryBackend {
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
            mappingGuard = ElasticsearchQueryReadiness(
                client,
                index,
                requirements,
                preparedMappingSnapshot ?: ElasticsearchQueryMappingSnapshot(),
            ),
        )
    }

    private class ReadinessRequirementCollector(
        private val binding: ElasticsearchQueryFieldBinding,
        private val nativeTemplates: ElasticsearchNativeQueryTemplateRegistry,
    ) {
        private val fields = LinkedHashSet<ElasticsearchMappingFieldRequirement>()
        private val presenceFields = LinkedHashSet<String>()
        private val presence = ElasticsearchQueryPresenceBinding(binding)
        var valid = true

        fun collect(
            expression: QueryExpression,
            configurationValid: Boolean,
        ): ElasticsearchQueryReadinessRequirements {
            valid = configurationValid
            inspect(expression)
            return ElasticsearchQueryReadinessRequirements(
                configurationValid = valid,
                fields = fields,
                presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
                presenceFields = presenceFields,
            )
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
            collectPredicateFields(current, logical)
            val hasUnsupportedCollation = current.stringComparison == StringComparisonMode.DEFAULT &&
                current.operator.isStringOperator() && binding.schema(logical).stringOptions?.collation != null
            if (hasUnsupportedCollation) {
                valid = false
            }
        }

        private fun inspectElementMatch(current: ElementMatchExpression, relativeTo: LogicalField?) {
            val logical = resolve(relativeTo, current.field)
            addField(logical, QueryFieldUsage.NESTED, ElasticsearchMappingUsage.NESTED)
            if (binding.physical(logical, QueryFieldUsage.NESTED) != binding.source(logical)) {
                valid = false
            }
            inspect(current.predicate, logical)
        }

        private fun inspectFullText(current: FullTextExpression, relativeTo: LogicalField?) {
            if (current.capabilityId.value != ElasticsearchQueryBackendFactory.FULL_TEXT_CAPABILITY) {
                unsupported()
            }
            current.fields.forEach { field ->
                val logical = resolve(relativeTo, field)
                if (!binding.contains(logical) || current.capabilityId !in binding.schema(logical).capabilities) {
                    unsupported()
                }
                addField(logical, QueryFieldUsage.SEARCH, ElasticsearchMappingUsage.SEARCH)
            }
        }

        private fun inspectNative(current: NativeExpression, relativeTo: LogicalField?) {
            val capabilityMismatch =
                current.capabilityId.value != ElasticsearchQueryBackendFactory.NATIVE_CAPABILITY ||
                    current.backendId != ElasticsearchQueryBackendFactory.BACKEND_ID
            val undeclaredField = current.declaredFields.any { field -> !binding.contains(resolve(relativeTo, field)) }
            val missingTemplate = nativeTemplates.template(current.templateId) == null
            if (capabilityMismatch || undeclaredField || missingTemplate) {
                unsupported()
            }
            current.declaredFields.forEach { field ->
                addField(resolve(relativeTo, field), QueryFieldUsage.EXACT, ElasticsearchMappingUsage.EXACT)
            }
        }

        private fun collectPredicateFields(expression: PredicateExpression, logical: LogicalField) {
            val hasNull = QueryValue.NullValue in expression.values
            when (expression.operator) {
                PortableOperator.EQ -> if (hasNull) addNull(logical) else addExact(logical)
                PortableOperator.NE -> {
                    addPresent(logical)
                    if (hasNull) addNull(logical) else addExact(logical)
                }
                PortableOperator.IN,
                PortableOperator.NOT_IN,
                -> {
                    addPresent(logical)
                    if (hasNull) addNull(logical)
                    if (expression.values.any { it != QueryValue.NullValue }) addExact(logical)
                }
                PortableOperator.GT,
                PortableOperator.LT,
                PortableOperator.GTE,
                PortableOperator.LTE,
                PortableOperator.BETWEEN,
                -> {
                    addPresent(logical)
                    addExact(logical)
                }
                PortableOperator.NULL -> addNull(logical)
                PortableOperator.NOT_NULL -> {
                    addPresent(logical)
                    addNull(logical)
                }
                PortableOperator.EXISTS -> addPresent(logical)
                else -> addExact(logical)
            }
        }

        private fun addExact(logical: LogicalField) =
            addField(logical, QueryFieldUsage.EXACT, ElasticsearchMappingUsage.EXACT)

        private fun addPresent(logical: LogicalField) {
            presenceFields += presence.present(logical).field
        }

        private fun addNull(logical: LogicalField) {
            presenceFields += presence.explicitNull(logical).field
        }

        private fun addField(
            logical: LogicalField,
            physicalUsage: QueryFieldUsage,
            mappingUsage: ElasticsearchMappingUsage,
        ) {
            val schema = binding.schema(logical)
            fields += ElasticsearchMappingFieldRequirement(
                binding.physical(logical, physicalUsage),
                schema.valueKind,
                schema.collectionKind,
                schema.system,
                mappingUsage,
            )
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
}
