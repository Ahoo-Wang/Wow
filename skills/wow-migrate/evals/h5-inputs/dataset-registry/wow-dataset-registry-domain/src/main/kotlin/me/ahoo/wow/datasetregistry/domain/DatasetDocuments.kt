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

package me.ahoo.wow.datasetregistry.domain

import me.ahoo.wow.datasetregistry.api.FieldSensitivity
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasetProperties
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** Reads and writes the declarations the registry keeps, in the format of a dataset file. */
internal object DatasetDocuments {
    private const val STRING = "STRING"

    /** The declaration [document] names [id]; what is wrong with it is a [DatasetRegistryException.invalid]. */
    fun parse(id: String, document: ObjectNode): QueryDataset = try {
        QueryDataset.parse(QueryDataset.nameOf(id), document.toString())
    } catch (error: IllegalArgumentException) {
        throw DatasetRegistryException.invalid("Dataset [$id] is not a valid declaration: ${error.message}", error)
    }

    fun requireName(id: String) {
        try {
            QueryDataset.nameOf(id)
        } catch (error: IllegalArgumentException) {
            throw DatasetRegistryException.invalid(error.message ?: "Invalid dataset name [$id].", error)
        }
    }

    /** Sensitivity is reviewed field by field, never written in a draft. */
    fun requireNoSensitivity(id: String, document: ObjectNode) {
        val declared = fields(document).filter { (_, field) -> field.has(QueryDatasetProperties.SENSITIVE) }.keys
        if (declared.isNotEmpty()) {
            throw DatasetRegistryException.invalid(
                "Dataset [$id] declares the sensitivity of $declared in its draft; review it instead.",
            )
        }
    }

    fun fields(document: ObjectNode): Map<String, JsonNode> =
        document.get(QuerySchemaDeclarationProperties.FIELDS)
            ?.takeIf { it.isObject }
            ?.properties()?.associate { it.key to it.value }
            .orEmpty()

    /** The fields that hold strings, or arrays of strings: each needs a reviewed sensitivity to be published. */
    fun stringFields(document: ObjectNode): Set<String> = fields(document).filterValues { field ->
        field.types().contains(STRING) || field.get(QuerySchemaDeclarationProperties.ITEMS)?.types()?.contains(STRING) == true
    }.keys

    private fun JsonNode.types(): List<String> =
        get(
            QuerySchemaDeclarationProperties.TYPES
        )?.values()?.mapNotNull { it.takeIf(JsonNode::isString)?.stringValue() }
            .orEmpty()

    /** [document] with each field a review found sensitive declared so, as the dataset is served. */
    fun withSensitivity(document: ObjectNode, reviews: Map<String, FieldSensitivity>): ObjectNode {
        val published = document.deepCopy()
        val fields = published.get(QuerySchemaDeclarationProperties.FIELDS) as? ObjectNode ?: return published
        reviews.forEach { (name, review) ->
            val level = review.level ?: return@forEach
            val field = fields.get(name) as? ObjectNode ?: return@forEach
            field.putObject(QueryDatasetProperties.SENSITIVE)
                .put(QueryDatasetProperties.LEVEL, level.name)
                .put(QueryDatasetProperties.KEEP_PREFIX, review.keepPrefix)
                .put(QueryDatasetProperties.KEEP_SUFFIX, review.keepSuffix)
        }
        return published
    }
}
