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

package me.ahoo.wow.mongo.ledger

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [FailureLedger] of one service on MongoDB (documentation/designs/04-subscriptions.md §2), in the service's
 * own database: one document per parked (subscription, aggregate), keyed by a unique index on (service, subscription,
 * context, aggregate name, tenant, id). The key starts with [service], the service the ledger is of (finding A2-13):
 * services that share a database, and subscriptions of one name, see and resolve only their own entries. The
 * collection's layout is recorded as format [FORMAT] ([MongoFormat], decision D59).
 *
 * Every write is one single-document operation. A park is a conditional upsert (see [conditionalUpsert]) whose filter
 * admits only a later stored from version, so concurrent or replayed parks converge on the earliest (decision D45)
 * without a transaction; a repark is one `updateOne` without upsert, both setting the source position of a
 * Kafka-sourced subscription's entry (decision D134), null for the log's; a resolve is one `findOneAndDelete`. A park
 * writes the retry state (roadmap P.1) only when it inserts the document (`$setOnInsert`); an amendment is one
 * `findOneAndUpdate` returning the amended document, its filter carrying the retries or the request read when it
 * claims or takes one. A document written before the retry state reads as never retried. No collation is set: MongoDB
 * then compares strings by their UTF-8 bytes, which is code point order, so [list] pages in the order the contract
 * asks for.
 *
 * Calls run on [dispatcher], virtual threads by default (decision D29).
 */
@InternalWowApi
class MongoFailureLedger(
    client: MongoClient,
    databaseName: String,
    private val collection: String = DEFAULT_COLLECTION,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** The service whose ledger this is: its entries alone are seen. */
    private val service: String = DEFAULT_SERVICE,
    /** How the store's collections and indexes are managed: created when missing by default (review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
) : FailureLedger {
    private val database = client.getDatabase(databaseName)
    private val ledger: MongoCollection<Document> = database.getCollection(collection)

    /**
     * Claims the collection's format ([MongoFormat]) and creates the key index, which also serves [list]'s order,
     * when missing.
     */
    fun createSchema() {
        MongoFormat.claim(database, collection, FORMAT, ddl)
        MongoFormat.index(
            ledger,
            Indexes.ascending(SERVICE, SUBSCRIPTION, CONTEXT, AGGREGATE_NAME, TENANT_ID, AGGREGATE_ID),
            IndexOptions().unique(true).name(LEDGER_KEY),
            ddl,
        )
    }

    override suspend fun park(entry: ParkedAggregate): Boolean = dispatcher.mongo {
        val earlier = Filters.and(keyFilter(entry.subscription, entry.ref), Filters.gt(FROM_VERSION, entry.fromVersion))
        val update = Updates.combine(
            Updates.set(FROM_VERSION, entry.fromVersion),
            Updates.set(REASON, entry.reason),
            Updates.set(FAILED_AT, entry.failedAt),
            Updates.set(FROM_POSITION, entry.fromPosition),
            Updates.setOnInsert(RETRIES, entry.retries),
            Updates.setOnInsert(RETRY_AT, entry.retryAt),
            Updates.setOnInsert(RETRY_SPEC, specOf(entry.retrySpec)),
            Updates.setOnInsert(RECOVERABLE, entry.recoverable.name),
            Updates.setOnInsert(REQUEST, entry.request?.name),
        )
        conditionalUpsert { upsert ->
            val result = ledger.updateOne(earlier, update, UpdateOptions().upsert(upsert))
            result.matchedCount > 0 || result.upsertedId != null
        }
    }

    override suspend fun repark(entry: ParkedAggregate): Boolean = dispatcher.mongo {
        val update = Updates.combine(
            Updates.set(FROM_VERSION, entry.fromVersion),
            Updates.set(REASON, entry.reason),
            Updates.set(FAILED_AT, entry.failedAt),
            Updates.set(FROM_POSITION, entry.fromPosition),
        )
        ledger.updateOne(keyFilter(entry.subscription, entry.ref), update).matchedCount > 0
    }

    override suspend fun parked(subscription: String, ref: AggregateRef): ParkedAggregate? = dispatcher.mongo {
        ledger.find(keyFilter(subscription, ref)).firstOrNull()?.let { entryOf(it) }
    }

    override suspend fun list(subscription: String, after: AggregateRef?, limit: Int): List<ParkedAggregate> {
        require(limit >= 1) { "A page holds at least one entry." }
        val filter = if (after == null) {
            Filters.and(Filters.eq(SERVICE, service), Filters.eq(SUBSCRIPTION, subscription))
        } else {
            Filters.and(Filters.eq(SERVICE, service), Filters.eq(SUBSCRIPTION, subscription), after(after))
        }
        return dispatcher.mongo {
            ledger.find(filter)
                .sort(Sorts.ascending(CONTEXT, AGGREGATE_NAME, TENANT_ID, AGGREGATE_ID))
                .limit(limit)
                .map { entryOf(it) }
                .toList()
        }
    }

    override suspend fun resolve(subscription: String, ref: AggregateRef): ParkedAggregate? = dispatcher.mongo {
        ledger.findOneAndDelete(keyFilter(subscription, ref))?.let { entryOf(it) }
    }

    override suspend fun amend(
        subscription: String,
        ref: AggregateRef,
        amendment: ParkedAmendment,
    ): ParkedAggregate? {
        val key = keyFilter(subscription, ref)
        val (filter, update) = when (amendment) {
            is ParkedAmendment.ClaimRetry -> {
                val read = Filters.eq(RETRIES, amendment.retries)
                // A document written before the retry state has made no retry.
                val retries = if (amendment.retries == 0) Filters.or(read, Filters.exists(RETRIES, false)) else read
                Filters.and(key, retries) to
                    Updates.combine(Updates.inc(RETRIES, 1), Updates.set(RETRY_AT, amendment.at))
            }

            is ParkedAmendment.MarkRecoverable -> key to Updates.set(RECOVERABLE, amendment.recoverable.name)
            is ParkedAmendment.ApplyRetrySpec -> key to Updates.set(RETRY_SPEC, specOf(amendment.spec))
            is ParkedAmendment.Request -> key to Updates.set(REQUEST, amendment.request.name)
            is ParkedAmendment.TakeRequest ->
                Filters.and(key, Filters.eq(REQUEST, amendment.request.name)) to Updates.set(REQUEST, null)
        }
        return dispatcher.mongo {
            ledger.findOneAndUpdate(filter, update, FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))
                ?.let { entryOf(it) }
        }
    }

    private fun keyFilter(subscription: String, ref: AggregateRef): Bson = Filters.and(
        Filters.eq(SERVICE, service),
        Filters.eq(SUBSCRIPTION, subscription),
        Filters.eq(CONTEXT, ref.type.context),
        Filters.eq(AGGREGATE_NAME, ref.type.name),
        Filters.eq(TENANT_ID, ref.tenantId),
        Filters.eq(AGGREGATE_ID, ref.id),
    )

    /** The aggregates ordered after [ref]: the row comparison (context, name, tenant, id) > ref's, spelled out. */
    private fun after(ref: AggregateRef): Bson {
        val key = listOf(
            CONTEXT to ref.type.context,
            AGGREGATE_NAME to ref.type.name,
            TENANT_ID to ref.tenantId,
            AGGREGATE_ID to ref.id,
        )
        return Filters.or(
            key.indices.map { index ->
                val (field, value) = key[index]
                Filters.and(key.take(index).map { (equal, same) -> Filters.eq(equal, same) } + Filters.gt(field, value))
            },
        )
    }

    private fun entryOf(document: Document) = ParkedAggregate(
        subscription = document.getString(SUBSCRIPTION),
        ref = AggregateType(document.getString(CONTEXT), document.getString(AGGREGATE_NAME))
            .ref(document.getString(TENANT_ID), document.getString(AGGREGATE_ID)),
        fromVersion = document.getInteger(FROM_VERSION),
        reason = document.getString(REASON),
        failedAt = document.getLong(FAILED_AT),
        fromPosition = document.getLong(FROM_POSITION),
        retries = document.getInteger(RETRIES) ?: 0,
        retryAt = document.getLong(RETRY_AT),
        retrySpec = document.get(RETRY_SPEC, Document::class.java)?.let { spec ->
            ParkedRetrySpec(
                maxRetries = spec.getInteger(MAX_RETRIES),
                minBackoff = spec.getLong(MIN_BACKOFF).milliseconds,
                executionTimeout = spec.getLong(EXECUTION_TIMEOUT).milliseconds,
            )
        },
        recoverable = document.getString(RECOVERABLE)?.let { Recoverable.valueOf(it) } ?: Recoverable.UNKNOWN,
        request = document.getString(REQUEST)?.let { LedgerRequest.valueOf(it) },
    )

    /** [spec] as a document: its retries and its durations in milliseconds; null for the subscription's. */
    private fun specOf(spec: ParkedRetrySpec?): Document? = spec?.let {
        Document(MAX_RETRIES, it.maxRetries)
            .append(MIN_BACKOFF, it.minBackoff.inWholeMilliseconds)
            .append(EXECUTION_TIMEOUT, it.executionTimeout.inWholeMilliseconds)
    }

    private companion object {
        const val DEFAULT_COLLECTION = "wow_failure_ledger"

        /** The service of a ledger built without one: a database of one service. */
        const val DEFAULT_SERVICE = ""

        /** The layout version of the collection (decision D59); format 1 keys entries by service (finding A2-13). */
        const val FORMAT = 1
        const val LEDGER_KEY = "ledger_key"
        const val SERVICE = "service"
        const val SUBSCRIPTION = "subscription"
        const val CONTEXT = "context"
        const val AGGREGATE_NAME = "aggregate_name"
        const val TENANT_ID = "tenant_id"
        const val AGGREGATE_ID = "aggregate_id"
        const val FROM_VERSION = "from_version"
        const val REASON = "reason"
        const val FAILED_AT = "failed_at"
        const val FROM_POSITION = "from_position"
        const val RETRIES = "retries"
        const val RETRY_AT = "retry_at"
        const val RETRY_SPEC = "retry_spec"
        const val MAX_RETRIES = "max_retries"
        const val MIN_BACKOFF = "min_backoff_millis"
        const val EXECUTION_TIMEOUT = "execution_timeout_millis"
        const val RECOVERABLE = "recoverable"
        const val REQUEST = "request"
    }
}
