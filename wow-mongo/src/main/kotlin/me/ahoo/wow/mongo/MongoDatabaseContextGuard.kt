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

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

/**
 * Enforces the default deployment invariant that one MongoDB database belongs to one bounded context.
 *
 * Physical aggregate and prepare collection names intentionally omit the bounded context. A durable ownership marker
 * prevents independently deployed bounded contexts from silently sharing the same database. Existing unmarked
 * databases are checked for foreign-context documents in every aggregate collection before the marker is claimed.
 */
class MongoDatabaseContextGuard(private val database: MongoDatabase) {

    fun ensureContext(contextName: String) {
        require(contextName.isNotBlank()) {
            "MongoDB database contextName must not be blank."
        }
        findContextClaim()?.let { marker ->
            validateMarker(marker, contextName)
            return
        }
        validateExistingCollections(contextName)
        val marker = checkNotNull(claimContext(contextName)) {
            "MongoDB database [${database.name}] did not return its bounded context ownership marker."
        }
        validateMarker(marker, contextName)
    }

    private fun validateMarker(
        marker: Document,
        contextName: String,
    ) {
        val ownerContextName = marker.getString(MessageRecords.CONTEXT_NAME)
        val layoutVersion = marker.getInteger(LAYOUT_VERSION_FIELD)
        check(layoutVersion == CURRENT_LAYOUT_VERSION) {
            "MongoDB database [${database.name}] has unsupported context ownership layout version " +
                "[$layoutVersion]; expected [$CURRENT_LAYOUT_VERSION]."
        }
        check(ownerContextName == contextName) {
            "MongoDB database [${database.name}] is owned by bounded context [$ownerContextName], but bounded context " +
                "[$contextName] attempted to use it. The contextless physical collection layout requires one bounded " +
                "context per MongoDB database. Configure a separate MongoDB database for each bounded context."
        }
    }

    private fun validateExistingCollections(contextName: String) {
        database.listCollectionNames()
            .toFlux()
            .filter { collectionName ->
                AGGREGATE_COLLECTION_SUFFIXES.any(collectionName::endsWith)
            }.concatMap { collectionName ->
                database.getCollection(collectionName)
                    .find(Filters.ne(MessageRecords.CONTEXT_NAME, contextName))
                    .projection(Projections.include(MessageRecords.CONTEXT_NAME))
                    .limit(1)
                    .first()
                    .toMono()
                    .map { document ->
                        ExistingCollectionContext(
                            collectionName = collectionName,
                            contextName = document.getString(MessageRecords.CONTEXT_NAME),
                        )
                    }
            }.collectList()
            .block()
            .orEmpty()
            .forEach { existing ->
                check(existing.contextName == contextName) {
                    "MongoDB database [${database.name}] collection [${existing.collectionName}] belongs to bounded " +
                        "context [${existing.contextName}], but bounded context [$contextName] attempted to use the " +
                        "same aggregate-name-only layout. Use one bounded context per MongoDB database."
                }
            }
    }

    private fun findContextClaim(): Document? =
        database.getCollection(METADATA_COLLECTION_NAME)
            .find(Filters.eq(Documents.ID_FIELD, CONTEXT_OWNERSHIP_ID))
            .first()
            .toMono()
            .block()

    private fun claimContext(contextName: String): Document? =
        database.getCollection(METADATA_COLLECTION_NAME)
            .findOneAndUpdate(
                Filters.eq(Documents.ID_FIELD, CONTEXT_OWNERSHIP_ID),
                Updates.combine(
                    Updates.setOnInsert(MessageRecords.CONTEXT_NAME, contextName),
                    Updates.setOnInsert(LAYOUT_VERSION_FIELD, CURRENT_LAYOUT_VERSION),
                ),
                FindOneAndUpdateOptions()
                    .upsert(true)
                    .returnDocument(ReturnDocument.AFTER),
            ).toMono()
            .block()

    private data class ExistingCollectionContext(
        val collectionName: String,
        val contextName: String?,
    )

    companion object {
        const val METADATA_COLLECTION_NAME = "wow_database_metadata"
        const val LAYOUT_VERSION_FIELD = "layoutVersion"
        private const val CONTEXT_OWNERSHIP_ID = "boundedContext"
        private const val CURRENT_LAYOUT_VERSION = 1
        private val AGGREGATE_COLLECTION_SUFFIXES = listOf(
            "_event_stream",
            "_snapshot",
            "_snapshot_checkpoint",
        )
    }
}
