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
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.bson.Document

class MongoQueryBackendFactory @JvmOverloads constructor(
    private val database: MongoDatabase,
    private val nativeTemplates: MongoNativeQueryTemplateRegistry = MongoNativeQueryTemplateRegistry(),
    private val maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED
) : QueryBackendFactory {
    override fun bind(context: QueryBackendResolutionContext): QueryBackend {
        val collectionName = when (context.target.documentKind) {
            QueryDocumentKind.SNAPSHOT -> context.target.namedAggregate.toSnapshotCollectionName()
            QueryDocumentKind.EVENT_STREAM -> context.target.namedAggregate.toEventStreamCollectionName()
        }
        val binding = MongoQueryFieldBinding.bind(context.schema)
        val systemBindingReady = QuerySystemFields.fields(context.target.documentKind)
            .all { systemField -> binding.contains(systemField.path) }
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
            publisherObserver = MongoQueryPublisherObservers.observer(database),
            descriptor = mongoQueryBackendDescriptor(maxBudget),
            readinessRequirements = requirements
        )
    }

    private fun inspectRequirements(
        expression: QueryExpression,
        binding: MongoQueryFieldBinding,
        configurationValid: Boolean
    ): MongoQueryReadinessRequirements {
        val textFields = LinkedHashSet<String>()
        fun inspect(current: QueryExpression) {
            when (current) {
                is FullTextExpression -> if (current.capabilityId.value == FULL_TEXT_CAPABILITY) {
                    current.fields.mapTo(textFields, binding::physical)
                }

                is NativeExpression -> {
                    if (current.capabilityId.value != NATIVE_CAPABILITY) {
                        unsupportedCapability()
                    }
                    if (current.backendId != BACKEND_ID) {
                        unsupportedCapability()
                    }
                    if (current.declaredFields.any { !binding.contains(it) }) {
                        unsupportedCapability()
                    }
                    if (nativeTemplates.template(current.templateId) == null) {
                        unsupportedCapability()
                    }
                }

                is ElementMatchExpression -> inspect(current.predicate)
                is LogicalExpression -> current.operands.forEach(::inspect)
                is PortableLogicalExpression -> current.operands.forEach(::inspect)
                else -> Unit
            }
        }
        inspect(expression)
        return MongoQueryReadinessRequirements(textFields, configurationValid)
    }

    private fun unsupportedCapability(): Nothing = throw QueryException(
        QueryErrorCode.UNSUPPORTED_CAPABILITY,
        QueryStage.PLANNING,
        QueryErrorReason.CAPABILITY_DENIED
    )

    companion object {
        const val BACKEND_ID: String = "mongo"
        const val FULL_TEXT_CAPABILITY: String = "full-text"
        const val NATIVE_CAPABILITY: String = "x-wow:mongo-native"
    }
}
