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
import com.mongodb.client.model.CountOptions
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
import me.ahoo.wow.api.ledger.LedgerRequest
import me.ahoo.wow.api.ledger.Recoverable
import me.ahoo.wow.mongo.MongoDatabases
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoTimeouts
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.amendByRevision
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [FailureLedger] of one service on MongoDB (documentation/designs/04-subscriptions.md §2), in the service's
 * own database: one document per parked (subscription, aggregate), keyed by a unique index on (service, subscription
 * context, subscription, context, aggregate name, tenant, id). The key starts with [service], the service the ledger
 * is of, then the subscription's context and name ([SubscriptionId]): services that share a database, and
 * subscriptions of one name in two contexts, see and resolve only their own entries. The collection's layout is
 * recorded as format [FORMAT] ([MongoFormat]); a collection of another format is refused, not converted.
 *
 * Every write is one single-document operation. A park is a conditional upsert (see [conditionalUpsert]) whose filter
 * admits only a later stored from version, so concurrent or replayed parks converge on the earliest
 * without a transaction; a repark is one `updateOne` without upsert, both setting the source position of a
 * Kafka-sourced subscription's entry, null for the log's; a resolve is one `findOneAndDelete`. A park
 * writes the retry state only when it inserts the document, at revision 0 (one pipeline update); an amendment with an
 * update of its own is one `findOneAndUpdate` returning the amended document at the next revision, its filter carrying
 * the revision read when it claims, takes or schedules, and any other one ([amendByRevision]) a read and an
 * `updateOne` of the whole entry whose filter carries the revision it read. No collation is set: MongoDB then
 * compares strings by their UTF-8 bytes, which is code point order, so [list] pages in the order the contract asks for.
 *
 * Besides the key index, [TENANT_INDEX] on (service, subscription, tenant, then the key) answers a tenant's [list] in
 * its order, a subscription being its context and name. [due] reads by the key index ([LEDGER_KEY], a hint): a page of
 * what is due comes in the key's order, so an index on the partition and the next attempt would have each page read
 * and sort every due entry of the partitions again, a round's cost growing with the square of what is due. By the
 * key, a round reads each of the subscription's entries once. The store creates no such index; one an earlier build
 * created (`ledger_due`) is left in place, unread.
 *
 * Calls run on [dispatcher], virtual threads by default.
 */
@InternalWowApi
class MongoFailureLedger(
    client: MongoClient,
    databaseName: String,
    private val collection: String = DEFAULT_COLLECTION,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** The service whose ledger this is: its entries alone are seen. */
    private val service: String = DEFAULT_SERVICE,
    /** How the store's collections and indexes are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
    /** How long each of its calls may take (`wow.mongo.operation-timeout`, [MongoTimeouts]). */
    operationTimeout: Duration = MongoTimeouts.OPERATION,
) : FailureLedger {
    private val database = MongoDatabases.of(client, databaseName, operationTimeout)
    private val ledger: MongoCollection<Document> = database.getCollection(collection)

    /**
     * Claims the collection's format ([MongoFormat]) and creates its indexes when missing: the key index, which also
     * serves [list]'s order, [due] and [count], and [TENANT_INDEX].
     */
    fun createSchema() {
        MongoFormat.claim(database, collection, FORMAT, ddl)
        MongoFormat.index(
            ledger,
            Indexes.ascending(
                SERVICE,
                SUBSCRIPTION_CONTEXT,
                SUBSCRIPTION,
                CONTEXT,
                AGGREGATE_NAME,
                TENANT_ID,
                AGGREGATE_ID,
            ),
            IndexOptions().unique(true).name(LEDGER_KEY),
            ddl,
        )
        MongoFormat.index(
            ledger,
            Indexes.ascending(
                SERVICE,
                SUBSCRIPTION_CONTEXT,
                SUBSCRIPTION,
                TENANT_ID,
                CONTEXT,
                AGGREGATE_NAME,
                AGGREGATE_ID,
            ),
            IndexOptions().name(TENANT_INDEX),
            ddl,
        )
    }

    /**
     * One pipeline update, so that a document it inserts takes [entry]'s retry state at revision 0, and one it
     * replaces keeps its retry state, takes the next revision and is no longer scheduled: a document is inserted when
     * it has no [RECOVERABLE], which every stored entry has.
     */
    override suspend fun park(entry: ParkedAggregate): Boolean = dispatcher.mongo {
        val earlier = Filters.and(keyFilter(entry.subscription, entry.ref), Filters.gt(FROM_VERSION, entry.fromVersion))
        val inserting = Document("\$eq", listOf(Document("\$type", "\$$RECOVERABLE"), "missing"))
        fun either(onInsert: Any?, onReplace: Any?) = Document("\$cond", listOf(inserting, onInsert, onReplace))
        fun kept(field: String, onInsert: Any?) = either(literal(onInsert), "\$$field")
        val placed = Document()
            .append(PARTITION, literal(entry.partition))
            .append(FROM_VERSION, literal(entry.fromVersion))
            .append(REASON, literal(entry.reason))
            .append(FAILED_AT, literal(entry.failedAt))
            .append(FROM_POSITION, literal(entry.fromPosition))
            .append(SKIP_ONLY, literal(entry.skipOnly))
            .append(RETRIES, kept(RETRIES, entry.retries))
            .append(RETRY_AT, kept(RETRY_AT, entry.retryAt))
            .append(RETRY_SPEC, kept(RETRY_SPEC, specOf(entry.retrySpec)))
            .append(RECOVERABLE, kept(RECOVERABLE, entry.recoverable.name))
            // A request belongs to the parking it was made for: kept while the failure time is.
            .append(REQUEST, either(literal(entry.request?.name), sameParking(entry.failedAt, "\$$REQUEST")))
            .append(NEXT_ATTEMPT_AT, either(literal(entry.nextAttemptAt), null))
            .append(REVISION, either(0L, Document("\$add", listOf(STORED_REVISION, 1L))))
        conditionalUpsert { upsert ->
            val result = ledger.updateOne(earlier, listOf(Document("\$set", placed)), UpdateOptions().upsert(upsert))
            result.matchedCount > 0 || result.upsertedId != null
        }
    }

    override suspend fun repark(entry: ParkedAggregate, from: Int): Boolean = dispatcher.mongo {
        // A pipeline, so the request is kept only while the failure time is: one stage reads the entry as stored.
        val placed = Document()
            .append(PARTITION, literal(entry.partition))
            .append(FROM_VERSION, literal(entry.fromVersion))
            .append(REASON, literal(entry.reason))
            .append(FAILED_AT, literal(entry.failedAt))
            .append(FROM_POSITION, literal(entry.fromPosition))
            .append(SKIP_ONLY, literal(entry.skipOnly))
            .append(REQUEST, sameParking(entry.failedAt, "\$$REQUEST"))
            .append(NEXT_ATTEMPT_AT, null)
            .append(REVISION, Document("\$add", listOf(STORED_REVISION, 1L)))
        val filter = inPartition(keyFilter(entry.subscription, entry.ref), from)
        ledger.updateOne(filter, listOf(Document("\$set", placed))).matchedCount > 0
    }

    override suspend fun parked(subscription: SubscriptionId, ref: AggregateRef): ParkedAggregate? = dispatcher.mongo {
        ledger.find(keyFilter(subscription, ref)).first()?.let { entryOf(it) }
    }

    override suspend fun list(
        subscription: SubscriptionId,
        after: AggregateRef?,
        limit: Int,
        tenantId: String?,
    ): List<ParkedAggregate> = page(
        subscription,
        after,
        limit,
        listOfNotNull(tenantId?.let { Filters.eq(TENANT_ID, it) })
    )

    override suspend fun due(
        subscription: SubscriptionId,
        partitions: Collection<Int>,
        now: Long,
        after: AggregateRef?,
        limit: Int,
    ): List<ParkedAggregate> {
        if (partitions.isEmpty()) {
            return emptyList()
        }
        val due = listOf(
            Filters.`in`(PARTITION, partitions),
            Filters.or(Filters.eq(NEXT_ATTEMPT_AT, null), Filters.lte(NEXT_ATTEMPT_AT, now)),
        )
        return page(subscription, after, limit, due, index = LEDGER_KEY)
    }

    override suspend fun count(subscription: SubscriptionId, max: Int): Int {
        require(max >= 1) { "At least one parked entry is counted." }
        return dispatcher.mongo {
            ledger.countDocuments(Filters.and(of(subscription)), CountOptions().limit(max)).toInt()
        }
    }

    /**
     * Up to [limit] of [subscription]'s entries after [after] that [conditions] admit, in the ledger's order, read by
     * [index] when given, else by the index the server plans.
     */
    private suspend fun page(
        subscription: SubscriptionId,
        after: AggregateRef?,
        limit: Int,
        conditions: List<Bson>,
        index: String? = null,
    ): List<ParkedAggregate> {
        require(limit >= 1) { "A page holds at least one entry." }
        val filter = Filters.and(
            of(subscription) +
                conditions +
                listOfNotNull(after?.let(::after)),
        )
        return dispatcher.mongo {
            ledger.find(filter)
                .let { found -> index?.let(found::hintString) ?: found }
                .sort(Sorts.ascending(CONTEXT, AGGREGATE_NAME, TENANT_ID, AGGREGATE_ID))
                .limit(limit)
                .map { entryOf(it) }
                .toList()
        }
    }

    override suspend fun resolveAnywhere(subscription: SubscriptionId, ref: AggregateRef): ParkedAggregate? =
        dispatcher.mongo { ledger.findOneAndDelete(keyFilter(subscription, ref))?.let { entryOf(it) } }

    override suspend fun resolve(subscription: SubscriptionId, ref: AggregateRef, partition: Int): ParkedAggregate? =
        dispatcher.mongo {
            ledger.findOneAndDelete(inPartition(keyFilter(subscription, ref), partition))?.let { entryOf(it) }
        }

    /** [key] while its entry is parked in [partition]. */
    private fun inPartition(key: Bson, partition: Int): Bson = Filters.and(key, Filters.eq(PARTITION, partition))

    override suspend fun amend(
        subscription: SubscriptionId,
        ref: AggregateRef,
        amendment: ParkedAmendment,
    ): ParkedAggregate? {
        val key = keyFilter(subscription, ref)
        val (filter, update) = when (amendment) {
            is ParkedAmendment.ClaimRetry -> at(key, amendment.revision) to
                Updates.combine(Updates.inc(RETRIES, 1), Updates.set(RETRY_AT, amendment.at), UNSCHEDULED)

            is ParkedAmendment.MarkRecoverable -> ofParking(key, amendment.failedAt) to
                Updates.combine(Updates.set(RECOVERABLE, amendment.recoverable.name), UNSCHEDULED)

            is ParkedAmendment.Request -> ofParking(key, amendment.failedAt) to
                Updates.combine(Updates.set(REQUEST, amendment.request.name), UNSCHEDULED)
            is ParkedAmendment.Schedule -> at(key, amendment.revision) to Updates.set(NEXT_ATTEMPT_AT, amendment.at)
            is ParkedAmendment.TakeRequest -> at(key, amendment.revision) to Updates.set(REQUEST, null)
            is ParkedAmendment.Restate -> at(key, amendment.revision) to Updates.combine(
                Updates.set(REASON, amendment.reason),
                Updates.set(FAILED_AT, amendment.failedAt),
                Updates.set(RETRIES, amendment.retries),
                Updates.set(RETRY_AT, amendment.retryAt),
                Updates.set(RETRY_SPEC, specOf(amendment.retrySpec)),
                Updates.set(RECOVERABLE, amendment.recoverable.name),
                UNSCHEDULED,
            )
            // An operator's retry spec, sent for one entry at a time, and any amendment a later version adds.
            else -> return amendByRevision(amendment, { parked(subscription, ref) }, ::replace)
        }
        return dispatcher.mongo {
            ledger.findOneAndUpdate(
                filter,
                Updates.combine(update, NEXT_REVISION),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )?.let { entryOf(it) }
        }
    }

    /** Stores [amended] whole while the stored entry is at [revision]: the generic path's write. */
    private suspend fun replace(amended: ParkedAggregate, revision: Long): Boolean = dispatcher.mongo {
        val update = Updates.combine(
            Updates.set(PARTITION, amended.partition),
            Updates.set(FROM_VERSION, amended.fromVersion),
            Updates.set(REASON, amended.reason),
            Updates.set(FAILED_AT, amended.failedAt),
            Updates.set(FROM_POSITION, amended.fromPosition),
            Updates.set(SKIP_ONLY, amended.skipOnly),
            Updates.set(RETRIES, amended.retries),
            Updates.set(RETRY_AT, amended.retryAt),
            Updates.set(RETRY_SPEC, specOf(amended.retrySpec)),
            Updates.set(RECOVERABLE, amended.recoverable.name),
            Updates.set(REQUEST, amended.request?.name),
            Updates.set(NEXT_ATTEMPT_AT, amended.nextAttemptAt),
            Updates.set(REVISION, amended.revision),
        )
        ledger.updateOne(at(keyFilter(amended.subscription, amended.ref), revision), update).matchedCount > 0
    }

    /** [key] at [revision]. */
    private fun at(key: Bson, revision: Long): Bson = Filters.and(key, Filters.eq(REVISION, revision))

    /** The entries of the service's [subscription]: its context and its name. */
    private fun of(subscription: SubscriptionId): List<Bson> = listOf(
        Filters.eq(SERVICE, service),
        Filters.eq(SUBSCRIPTION_CONTEXT, subscription.context),
        Filters.eq(SUBSCRIPTION, subscription.name),
    )

    private fun keyFilter(subscription: SubscriptionId, ref: AggregateRef): Bson = Filters.and(
        of(subscription) + listOf(
            Filters.eq(CONTEXT, ref.type.context),
            Filters.eq(AGGREGATE_NAME, ref.type.name),
            Filters.eq(TENANT_ID, ref.tenantId),
            Filters.eq(AGGREGATE_ID, ref.id),
        ),
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
        subscription = SubscriptionId(document.getString(SUBSCRIPTION_CONTEXT), document.getString(SUBSCRIPTION)),
        ref = AggregateType(document.getString(CONTEXT), document.getString(AGGREGATE_NAME))
            .ref(document.getString(TENANT_ID), document.getString(AGGREGATE_ID)),
        partition = document.getInteger(PARTITION),
        fromVersion = document.getInteger(FROM_VERSION),
        reason = document.getString(REASON),
        failedAt = document.getLong(FAILED_AT),
        fromPosition = document.getLong(FROM_POSITION),
        skipOnly = document.getBoolean(SKIP_ONLY),
        retries = document.getInteger(RETRIES),
        retryAt = document.getLong(RETRY_AT),
        retrySpec = document.get(RETRY_SPEC, Document::class.java)?.let { spec ->
            ParkedRetrySpec(
                maxRetries = spec.getInteger(MAX_RETRIES),
                initialBackoff = spec.getLong(INITIAL_BACKOFF).milliseconds,
                claimTtl = spec.getLong(CLAIM_TTL).milliseconds,
            )
        },
        recoverable = Recoverable.valueOf(document.getString(RECOVERABLE)),
        request = document.getString(REQUEST)?.let { LedgerRequest.valueOf(it) },
        nextAttemptAt = document.getLong(NEXT_ATTEMPT_AT),
        revision = document.getLong(REVISION),
    )

    /** [spec] as a document: its retries and its durations in milliseconds; null for the subscription's. */
    private fun specOf(spec: ParkedRetrySpec?): Document? = spec?.let {
        Document(MAX_RETRIES, it.maxRetries)
            .append(INITIAL_BACKOFF, it.initialBackoff.inWholeMilliseconds)
            .append(CLAIM_TTL, it.claimTtl.inWholeMilliseconds)
    }

    private companion object {
        const val DEFAULT_COLLECTION = "wow_failure_ledger"

        /** The service of a ledger built without one: a database of one service. */
        const val DEFAULT_SERVICE = ""

        /**
         * The layout version of the collection: an entry is keyed by service and by its subscription's context and
         * name (`subscription_context`), in the key and both indexes; it holds its partition, its next attempt and its
         * revision; an applied spec's claim TTL is `claim_ttl_millis`, an operator's request to retry `RETRY`;
         * `skip_only` is whether the source no longer holds what the entry is parked from.
         */
        const val FORMAT = 1
        const val LEDGER_KEY = "ledger_key"
        const val TENANT_INDEX = "ledger_tenant"
        const val SERVICE = "service"
        const val SUBSCRIPTION_CONTEXT = "subscription_context"
        const val SUBSCRIPTION = "subscription"
        const val CONTEXT = "context"
        const val AGGREGATE_NAME = "aggregate_name"
        const val TENANT_ID = "tenant_id"
        const val AGGREGATE_ID = "aggregate_id"
        const val FROM_VERSION = "from_version"
        const val REASON = "reason"
        const val FAILED_AT = "failed_at"
        const val FROM_POSITION = "from_position"

        /** Whether the source no longer holds what the entry is parked from, a boolean every document has. */
        const val SKIP_ONLY = "skip_only"
        const val RETRIES = "retries"
        const val RETRY_AT = "retry_at"
        const val RETRY_SPEC = "retry_spec"
        const val MAX_RETRIES = "max_retries"
        const val INITIAL_BACKOFF = "initial_backoff_millis"
        const val CLAIM_TTL = "claim_ttl_millis"
        const val RECOVERABLE = "recoverable"
        const val REQUEST = "request"
        const val PARTITION = "partition"
        const val NEXT_ATTEMPT_AT = "next_attempt_at"

        /** The entry's revision ([ParkedAggregate.revision]), a long. */
        const val REVISION = "revision"

        /** Clears the schedule: the owner looks at the entry again on its next scan. */
        val UNSCHEDULED: Bson = Updates.set(NEXT_ATTEMPT_AT, null)

        /** Gives the entry the next revision. */
        val NEXT_REVISION: Bson = Updates.inc(REVISION, 1L)

        /** In a pipeline update, the revision stored. */
        const val STORED_REVISION: String = "\$$REVISION"

        /** [value] as a pipeline update takes it as it is, never as an expression or a field path. */
        fun literal(value: Any?): Document = Document("\$literal", value)

        /** In a pipeline update, [stored] while the entry stored fails at [failedAt], else null. */
        fun sameParking(failedAt: Long, stored: String): Document =
            Document("\$cond", listOf(Document("\$eq", listOf("\$$FAILED_AT", failedAt)), stored, null))

        /** [key], narrowed to the parking an operator's amendment names by its failure time; any parking for null. */
        fun ofParking(key: Bson, failedAt: Long?): Bson =
            failedAt?.let { Filters.and(key, Filters.eq(FAILED_AT, it)) } ?: key
    }
}
