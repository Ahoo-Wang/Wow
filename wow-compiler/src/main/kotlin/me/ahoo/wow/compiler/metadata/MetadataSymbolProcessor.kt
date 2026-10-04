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

package me.ahoo.wow.compiler.metadata

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.validate
import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.compiler.AggregateRootResolver.AGGREGATE_ROOT_NAME
import me.ahoo.wow.compiler.AggregateRootResolver.toName
import me.ahoo.wow.compiler.metadata.AggregatePolicyResolver.resolveAggregatePolicy
import me.ahoo.wow.compiler.metadata.BoundedContextResolver.resolveBoundedContext
import me.ahoo.wow.compiler.metadata.CommandAggregateRootResolver.resolveAggregateRoot
import me.ahoo.wow.configuration.WOW_METADATA_RESOURCE_NAME
import me.ahoo.wow.configuration.WowMetadata
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper

/**
 * @see me.ahoo.wow.configuration.WowMetadata
 */
class MetadataSymbolProcessor(
    environment: SymbolProcessorEnvironment
) : SymbolProcessor {
    companion object {
        val BOUNDED_CONTEXT_NAME = BoundedContext::class.qualifiedName!!
        const val WOW_METADATA_RESOURCE_PATH = WOW_METADATA_RESOURCE_NAME

        private val KSP_SAFE_OBJECT_MAPPER = jsonMapper {
            changeDefaultVisibility {
                it.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
            }
            configure(StreamReadFeature.IGNORE_UNDEFINED, true)
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        }
    }

    private var wowMetadataMerger: WowMetadataMerger = WowMetadataMerger()

    /**
     * Aggregate type → its policy, for the aggregates whose policy is not the default.
     */
    private val aggregatePolicies = mutableMapOf<String, AggregatePolicy>()

    private val logger = environment.logger
    private val codeGenerator = environment.codeGenerator

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("MetadataSymbolProcessor - process[$this]")
        val dependencyFiles = mutableSetOf<KSFile>()
        resolver
            .getSymbolsWithAnnotation(BOUNDED_CONTEXT_NAME)
            .filterIsInstance<KSClassDeclaration>()
            .filter {
                it.validate()
            }.forEach {
                it.containingFile?.let { file ->
                    dependencyFiles.add(file)
                }
                val boundedContextMetadata = it.resolveBoundedContext()
                reportConflict(it) {
                    wowMetadataMerger.merge(boundedContextMetadata)
                }
            }

        resolver
            .getSymbolsWithAnnotation(AGGREGATE_ROOT_NAME)
            .filterIsInstance<KSClassDeclaration>()
            .filter {
                it.validate()
            }.forEach {
                it.containingFile?.let { file ->
                    dependencyFiles.add(file)
                }
                val aggregateName = it.toName()
                val aggregate = it.resolveAggregateRoot(resolver)
                reportConflict(it) {
                    val policy = it.resolveAggregatePolicy()
                    if (!policy.isDefault) {
                        aggregatePolicies[aggregate.type!!] = policy
                    }
                    wowMetadataMerger.merge(aggregateName, aggregate)
                }
            }
        if (dependencyFiles.isEmpty()) {
            return emptyList()
        }
        val dependencies = Dependencies(aggregating = true, sources = dependencyFiles.toTypedArray())
        val file =
            codeGenerator
                .createNewFile(
                    dependencies = dependencies,
                    packageName = "",
                    fileName = WOW_METADATA_RESOURCE_PATH,
                    extensionName = "",
                )
        val metadataJson = KSP_SAFE_OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(wowMetadataMerger.metadata.withPolicies())
        file.write(metadataJson.toByteArray())
        file.close()
        return emptyList()
    }

    /**
     * Two declarations of one aggregate that disagree (its spaced flag or owner policy, or its static tenant) are a
     * compile error on the declaration, as they are a startup failure at runtime.
     */
    private fun reportConflict(symbol: KSClassDeclaration, block: () -> Unit) {
        try {
            block()
        } catch (conflict: IllegalStateException) {
            logger.error(conflict.message.orEmpty(), symbol)
        }
    }

    /**
     * The metadata as JSON, with `spaced` and `owner` recorded on each aggregate whose policy is not the default.
     * They are written beside the fields of [me.ahoo.wow.configuration.Aggregate], not as fields of it, so the
     * metadata the runtime reads and serves (`GET /wow/metadata`) keeps its 9.2 shape; readers ignore unknown fields.
     */
    private fun WowMetadata.withPolicies(): ObjectNode {
        val tree = KSP_SAFE_OBJECT_MAPPER.valueToTree<ObjectNode>(this)
        if (aggregatePolicies.isEmpty()) {
            return tree
        }
        contexts.forEach { (contextName, context) ->
            context.aggregates.forEach { (aggregateName, aggregate) ->
                val policy = aggregatePolicies[aggregate.type] ?: return@forEach
                val aggregateNode = tree.path("contexts").path(contextName).path("aggregates")
                    .path(aggregateName) as ObjectNode
                if (policy.spaced) {
                    aggregateNode.put(AggregatePolicy.SPACED, true)
                }
                if (policy.owner.owned) {
                    aggregateNode.put(AggregatePolicy.OWNER, policy.owner.name)
                }
            }
        }
        return tree
    }

    override fun finish() {
        logger.info("MetadataSymbolProcessor - finish[$this]")
    }

    override fun onError() {
        logger.info("MetadataSymbolProcessor - onError[$this]")
    }
}
