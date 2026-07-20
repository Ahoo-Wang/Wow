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

package me.ahoo.wow.mongo

import com.mongodb.MongoException
import com.mongodb.client.model.IndexOptions
import com.mongodb.reactivestreams.client.MongoCollection
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.infra.accessor.function.reactive.toBlockable
import org.bson.Document
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

internal data class ManagedMongoIndex(
    val name: String,
    val keys: List<Pair<String, Any>>,
    val unique: Boolean = false,
) {
    fun keysDocument(): Document = Document().also { document ->
        keys.forEach { (field, direction) ->
            document[field] = direction
        }
    }

    fun options(): IndexOptions = IndexOptions()
        .name(name)
        .unique(unique)
}

internal class MongoIndexSchemaMismatchException(
    collectionName: String,
    indexName: String,
    expected: ManagedMongoIndex,
    actual: Document?,
    reason: String,
) : IllegalStateException(
    "Mongo index schema mismatch in [$collectionName] for [$indexName]: $reason. " +
        "Expected=$expected, actual=$actual. " +
        "Apply a controlled drop/recreate migration before startup.",
)

internal fun ascendingIndex(
    vararg fields: String,
    unique: Boolean = false,
): ManagedMongoIndex = ManagedMongoIndex(
    name = fields.joinToString("_") { field -> "${field}_1" },
    keys = fields.map { field -> field to 1 },
    unique = unique,
)

internal fun hashedIndex(field: String): ManagedMongoIndex = ManagedMongoIndex(
    name = "${field}_hashed",
    keys = listOf(field to "hashed"),
)

internal fun MongoCollection<Document>.reconcileIndexes(
    expectedIndexes: List<ManagedMongoIndex>,
    forbiddenIndexes: List<ManagedMongoIndex> = emptyList(),
) {
    val existingIndexes = listIndexes().toFlux().collectList().toBlockable().block().orEmpty()
    requireNoForbiddenIndexes(existingIndexes, forbiddenIndexes)
    findMissingIndexes(existingIndexes, expectedIndexes).forEach(::createManagedIndex)
}

private fun MongoCollection<Document>.requireNoForbiddenIndexes(
    existingIndexes: List<Document>,
    forbiddenIndexes: List<ManagedMongoIndex>,
) {
    forbiddenIndexes.forEach { forbidden ->
        existingIndexes.firstOrNull { actual ->
            MongoIndexCompatibility.hasSameKeyFields(actual, forbidden) &&
                MongoIndexCompatibility.isUnique(actual)
        }?.let { actual ->
            throw MongoIndexSchemaMismatchException(
                collectionName = namespace.fullName,
                indexName = actual.getString("name") ?: forbidden.name,
                expected = forbidden,
                actual = actual,
                reason = "index belongs to an incompatible managed index mode",
            )
        }
    }
}

private fun MongoCollection<Document>.findMissingIndexes(
    existingIndexes: List<Document>,
    expectedIndexes: List<ManagedMongoIndex>,
): List<ManagedMongoIndex> = expectedIndexes.filter { expected ->
    val sameName = existingIndexes.firstOrNull { actual ->
        actual.getString("name") == expected.name
    }
    if (sameName != null) {
        MongoIndexCompatibility.requireCompatible(sameName, namespace.fullName, expected)
        return@filter false
    }

    existingIndexes.firstOrNull { actual ->
        MongoIndexCompatibility.hasSameOrderedKeys(actual, expected)
    }?.let { sameKeys ->
        throw MongoIndexSchemaMismatchException(
            collectionName = namespace.fullName,
            indexName = expected.name,
            expected = expected,
            actual = sameKeys,
            reason = "an index with the same ordered keys uses a different managed definition",
        )
    }
    true
}

private fun MongoCollection<Document>.createManagedIndex(expected: ManagedMongoIndex) {
    mongoIndexLog.info {
        "Creating Mongo index [${expected.name}] in [${namespace.fullName}]."
    }
    try {
        createIndex(expected.keysDocument(), expected.options())
            .toMono()
            .toBlockable()
            .block()
    } catch (error: MongoException) {
        throw IllegalStateException(
            "Failed to create required Mongo index [${expected.name}] in [${namespace.fullName}]. " +
                "Verify existing data and DDL privileges before retrying.",
            error,
        )
    }
}

private object MongoIndexCompatibility {
    fun requireCompatible(
        actual: Document,
        collectionName: String,
        expected: ManagedMongoIndex,
    ) {
        if (isCompatible(actual, expected)) {
            return
        }
        throw MongoIndexSchemaMismatchException(
            collectionName = collectionName,
            indexName = expected.name,
            expected = expected,
            actual = actual,
            reason = "existing index definition differs from the managed definition",
        )
    }

    private fun isCompatible(actual: Document, expected: ManagedMongoIndex): Boolean {
        return hasSameOrderedKeys(actual, expected) &&
            isUnique(actual) == expected.unique &&
            actual["expireAfterSeconds"] == null &&
            ((actual["sparse"] as? Boolean) ?: false).not() &&
            actual["partialFilterExpression"] == null &&
            actual["collation"] == null &&
            ((actual["hidden"] as? Boolean) ?: false).not()
    }

    fun isUnique(actual: Document): Boolean = (actual["unique"] as? Boolean) ?: false

    fun hasSameOrderedKeys(actual: Document, expected: ManagedMongoIndex): Boolean {
        val actualKeys = actual["key"] as? Document ?: return false
        return normalizedKeys(actualKeys) == normalizedKeys(expected.keys)
    }

    fun hasSameKeyFields(actual: Document, expected: ManagedMongoIndex): Boolean {
        val actualKeys = actual["key"] as? Document ?: return false
        val expectedFields = expected.keys.mapTo(mutableSetOf()) { (field, _) -> field }
        return actualKeys.size == expectedFields.size && actualKeys.keys == expectedFields
    }

    private fun normalizedKeys(keys: Iterable<Pair<String, Any>>): List<Pair<String, Any>> =
        keys.map { (field, value) ->
            field to if (value is Number) value.toLong() else value
        }

    private fun normalizedKeys(keys: Document): List<Pair<String, Any>> =
        keys.entries.map { (field, value) ->
            field to if (value is Number) value.toLong() else checkNotNull(value)
        }
}

private val mongoIndexLog = KotlinLogging.logger {}
