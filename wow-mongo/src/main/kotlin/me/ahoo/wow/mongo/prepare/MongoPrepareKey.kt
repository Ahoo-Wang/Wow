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

package me.ahoo.wow.mongo.prepare

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.infra.accessor.function.reactive.toBlockable
import me.ahoo.wow.infra.prepare.PrepareKey
import me.ahoo.wow.infra.prepare.PreparedValue
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.TTL_FOREVER
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toForever
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toTtlAt
import me.ahoo.wow.mongo.AggregateSchemaInitializer.ensureCollection
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.prepare.PrepareRecords.TTL_AT_FIELD
import me.ahoo.wow.mongo.prepare.PrepareRecords.toDocument
import me.ahoo.wow.mongo.prepare.PrepareRecords.toPreparedValue
import me.ahoo.wow.serialization.convert
import me.ahoo.wow.serialization.toMap
import org.bson.Document
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.util.*
import java.util.concurrent.TimeUnit

const val VALUE_FIELD = "value"

class MongoPrepareKey<V : Any>(
    override val name: String,
    private val valueType: Class<V>,
    database: MongoDatabase
) : PrepareKey<V> {
    companion object {
        val DEFAULT_REPLACE_OPTIONS: ReplaceOptions = ReplaceOptions().upsert(true).bypassDocumentValidation(true)
    }

    private val prepareCollectionName = "prepare_$name"
    private val prepareCollection: MongoCollection<Document> = database.getCollection(prepareCollectionName)

    init {
        if (database.ensureCollection(prepareCollectionName)) {
            prepareCollection.createIndex(
                Indexes.ascending(TTL_AT_FIELD),
                IndexOptions().expireAfter(0, TimeUnit.SECONDS),
            ).toMono().toBlockable().block()
        }
    }

    override fun prepare(key: String, value: PreparedValue<V>): Mono<Boolean> {
        val document = value.toDocument()
        return prepareCollection
            .replaceOne(
                Filters.and(
                    Filters.eq(Documents.ID_FIELD, key),
                    Filters.lt(TTL_AT_FIELD, Date()),
                ),
                document,
                DEFAULT_REPLACE_OPTIONS,
            )
            .toMono()
            .map { true }
            .onErrorResume(MongoWriteException::class.java) {
                if (ErrorCategory.fromErrorCode(it.code) != ErrorCategory.DUPLICATE_KEY) {
                    it.toMono()
                } else {
                    Mono.just(false)
                }
            }
    }

    override fun getValue(key: String): Mono<PreparedValue<V>> {
        return prepareCollection
            .find(Filters.eq(Documents.ID_FIELD, key))
            .first()
            .toMono()
            .map {
                it.toPreparedValue(valueType)
            }
    }

    override fun rollback(key: String): Mono<Boolean> {
        return prepareCollection
            .deleteOne(
                Filters.and(
                    Filters.eq(Documents.ID_FIELD, key),
                    Filters.gt(TTL_AT_FIELD, Date()),
                ),
            )
            .toMono()
            .map {
                it.deletedCount > 0
            }
    }

    override fun rollback(key: String, value: V): Mono<Boolean> {
        val document = value.toForever().toDocument()
        return prepareCollection.deleteOne(
            Filters.and(
                Filters.eq(Documents.ID_FIELD, key),
                Filters.eq(VALUE_FIELD, document[VALUE_FIELD]),
            ),
        )
            .toMono()
            .map {
                it.deletedCount > 0
            }
    }

    override fun reprepare(key: String, value: PreparedValue<V>): Mono<Boolean> {
        val valueDocument = value.toDocument()
        return prepareCollection
            .updateOne(
                Filters.eq(Documents.ID_FIELD, key),
                Updates.combine(
                    Updates.set(VALUE_FIELD, valueDocument[VALUE_FIELD]),
                    Updates.set(TTL_AT_FIELD, valueDocument[TTL_AT_FIELD]),
                ),
            )
            .toMono()
            .map {
                it.matchedCount > 0
            }
    }

    override fun reprepare(key: String, oldValue: V, newValue: PreparedValue<V>): Mono<Boolean> {
        val oldValueDocument = oldValue.toForever().toDocument()
        val newValueDocument = newValue.toDocument()
        return prepareCollection
            .updateOne(
                Filters.and(
                    Filters.eq(Documents.ID_FIELD, key),
                    Filters.eq(VALUE_FIELD, oldValueDocument[VALUE_FIELD]),
                ),
                Updates.combine(
                    Updates.set(VALUE_FIELD, newValueDocument[VALUE_FIELD]),
                    Updates.set(TTL_AT_FIELD, newValueDocument[TTL_AT_FIELD]),
                ),
            )
            .toMono()
            .map {
                it.matchedCount > 0
            }
    }
}

object PrepareRecords {
    const val VALUE_FIELD = "value"
    const val TTL_AT_FIELD = "ttlAt"

    fun <V> PreparedValue<V>.toDocument(): Document {
        val valueMap = toMap()
        val document = Document(valueMap)
        document.replace(TTL_AT_FIELD, Date(ttlAt))
        return document
    }

    fun <V> Document.toPreparedValue(valueType: Class<V>): PreparedValue<V> {
        val rawValue = checkNotNull(this[VALUE_FIELD])
        val value = if (valueType.isInstance(rawValue)) {
            valueType.cast(rawValue)
        } else if (rawValue is Document) {
            rawValue.convert(valueType)
        } else {
            @Suppress("UseRequire")
            throw IllegalArgumentException("valueType: $valueType is not assignable from rawValue: $rawValue")
        }

        val ttlAt = this.getDate(TTL_AT_FIELD)?.time ?: TTL_FOREVER
        return value.toTtlAt(ttlAt)
    }
}
