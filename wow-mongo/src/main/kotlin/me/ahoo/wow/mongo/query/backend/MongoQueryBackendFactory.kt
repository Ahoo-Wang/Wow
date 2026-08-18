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

package me.ahoo.wow.mongo.query.backend

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.bson.Document

class MongoQueryBackendFactory @JvmOverloads constructor(
    private val database: MongoDatabase,
    private val nativeTemplates: MongoNativeQueryTemplateRegistry = MongoNativeQueryTemplateRegistry(),
    private val maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED,
) : QueryBackendFactory {
    private val binder = MongoQueryBackendBinder(database, nativeTemplates, maxBudget)

    override fun bind(context: QueryBackendResolutionContext): QueryBackend = binder.bind(context)

    companion object {
        const val BACKEND_ID: String = "mongo"
        const val FULL_TEXT_CAPABILITY: String = "full-text"
        const val NATIVE_CAPABILITY: String = "x-wow:mongo-native"
    }
}

internal class MongoQueryBackendBinder(
    private val database: MongoDatabase,
    private val nativeTemplates: MongoNativeQueryTemplateRegistry,
    private val maxBudget: QueryBudgetLimit,
    private val publisherObserver: MongoQueryPublisherObserver = MongoQueryPublisherObserver.NONE,
) {
    fun bind(context: QueryBackendResolutionContext): QueryBackend {
        val collectionName = when (context.target.documentKind) {
            QueryDocumentKind.SNAPSHOT -> context.target.namedAggregate.toSnapshotCollectionName()
            QueryDocumentKind.EVENT_STREAM -> context.target.namedAggregate.toEventStreamCollectionName()
        }
        val binding = MongoQueryFieldBinding.bind(context.schema)
        val systemBindingReady = binding.hasAuthoritativeSystemFields(context.target.documentKind)
        val documentCodecReady = runCatching {
            database.codecRegistry.get(Document::class.java)
        }.isSuccess
        val requirements = inspectRequirements(
            context.securedExpression,
            binding,
            configurationValid = systemBindingReady && documentCodecReady
        )
        return MongoQueryBackend(
            database = database,
            collectionName = collectionName,
            binding = binding,
            nativeTemplates = nativeTemplates,
            publisherObserver = publisherObserver,
            descriptor = mongoQueryBackendDescriptor(maxBudget),
            readinessRequirements = requirements
        )
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun inspectRequirements(
        expression: QueryExpression,
        binding: MongoQueryFieldBinding,
        configurationValid: Boolean
    ): MongoQueryReadinessRequirements {
        val textFields = LinkedHashSet<String>()
        var fullTextCount = 0
        var routeConfigurationValid = configurationValid
        fun inspect(current: QueryExpression, relativeTo: LogicalField? = null, fullTextAllowed: Boolean = true) {
            when (current) {
                is FullTextExpression -> {
                    if (!fullTextAllowed ||
                        current.capabilityId.value != MongoQueryBackendFactory.FULL_TEXT_CAPABILITY ||
                        ++fullTextCount > 1 || current.fields.any { field -> !binding.contains(resolve(relativeTo, field)) }
                    ) {
                        unsupportedCapability()
                    }
                    current.fields.mapTo(textFields) { field ->
                        val logical = resolve(relativeTo, field)
                        if (QueryCapabilityId(MongoQueryBackendFactory.FULL_TEXT_CAPABILITY) !in
                            binding.schema(logical).capabilities
                        ) {
                            unsupportedCapability()
                        }
                        binding.physical(logical, QueryFieldUsage.SEARCH)
                    }
                }

                is NativeExpression -> {
                    if (current.capabilityId.value != MongoQueryBackendFactory.NATIVE_CAPABILITY) {
                        unsupportedCapability()
                    }
                    if (current.backendId != MongoQueryBackendFactory.BACKEND_ID) {
                        unsupportedCapability()
                    }
                    if (current.declaredFields.any { !binding.contains(it) }) {
                        unsupportedCapability()
                    }
                    if (nativeTemplates.template(current.templateId) == null) {
                        unsupportedCapability()
                    }
                }

                is PredicateExpression -> {
                    val stringOptions = binding.schema(resolve(relativeTo, current.field)).stringOptions
                    if (current.stringComparison == StringComparisonMode.DEFAULT &&
                        supportsStringComparison(current.operator) &&
                        (stringOptions == null || stringOptions.collation != null)
                    ) {
                        routeConfigurationValid = false
                    }
                }

                is ElementMatchExpression -> {
                    val nested = resolve(relativeTo, current.field)
                    inspect(current.predicate, nested, fullTextAllowed = false)
                }
                is LogicalExpression -> current.operands.forEach { operand ->
                    inspect(
                        operand,
                        relativeTo,
                        fullTextAllowed && current.operator == LogicalOperator.AND
                    )
                }
                is PortableLogicalExpression -> current.operands.forEach { operand ->
                    inspect(operand, relativeTo, fullTextAllowed)
                }
                else -> Unit
            }
        }
        inspect(expression)
        return MongoQueryReadinessRequirements(textFields, routeConfigurationValid)
    }

    private fun resolve(relativeTo: LogicalField?, field: LogicalField): LogicalField =
        if (relativeTo == null) field else LogicalField("${relativeTo.value}.${field.value}")

    private fun supportsStringComparison(operator: PortableOperator): Boolean = when (operator) {
        PortableOperator.CONTAINS,
        PortableOperator.STARTS_WITH,
        PortableOperator.ENDS_WITH,
        -> true
        else -> false
    }

    private fun unsupportedCapability(): Nothing = throw QueryException(
        QueryErrorCode.UNSUPPORTED_CAPABILITY,
        QueryStage.PLANNING,
        QueryErrorReason.CAPABILITY_DENIED
    )
}
