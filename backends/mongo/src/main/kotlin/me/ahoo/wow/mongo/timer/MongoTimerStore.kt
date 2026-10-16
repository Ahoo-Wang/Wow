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

package me.ahoo.wow.mongo.timer

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoOperators
import me.ahoo.wow.mongo.MongoRules
import me.ahoo.wow.mongo.VirtualThreads
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import org.bson.Document
import org.bson.conversions.Bson

/**
 * The [TimerStore] of one bounded context on MongoDB (decisions D166, D198): the collection `wow_timer__<context>`,
 * named by the rule of the log's collections, one document per timer, keyed by a unique index on its owner
 * (`owner: {aggregate, tenant_id, id}`, its context the collection's; finding A2-12) and `key`.
 *
 * Every write is one single-document operation, but for [cancelAll] and [settle]. A put is a conditional upsert (see
 * [conditionalUpsert]) whose filter admits only another timer at the same or a later version; a cancel is a
 * conditional update; a settle is one unordered bulk write of conditional updates, each on the document as it was
 * loaded. A partial index on `(partition, due_at, timer_id)` of the PENDING and FIRING (claimed) timers serves the due read of every
 * partition asked, one on the target of the PARKED timers a resume. The body is kept as the command's JSON text, the
 * rule as a subdocument (`{type, ...}`, as Wow's JSON writes it). Format 2 added the rule and the slot, format 3 the
 * slot's due time (`slot_due_at`), kept apart from `due_at`, which a claim or a retry moves, and, within format 3, the
 * count of the slots a recurring timer coalesced before its current one (`skipped`, absent read as none), format 4
 * the structured owner instead of its joined name. The timers are a view rebuilt from the log: a collection of format
 * 3 is refused, dropped and rebuilt.
 *
 * Calls run on [dispatcher], virtual threads by default (decision D29).
 */
@InternalWowApi
class MongoTimerStore(
    client: MongoClient,
    databaseName: String,
    private val context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : TimerStore {
    /** The collection's name, `wow_timer__<context>`. */
    val collectionName: String = MongoNames.limited("${PREFIX}__${MongoNames.part(context)}")

    private val database = client.getDatabase(databaseName)
    private val timers: MongoCollection<Document> = database.getCollection(collectionName)

    /**
     * Creates the key index and the partial indexes of the due and the parked timers when missing; refuses a collection
     * recorded in another format version ([MongoFormat], decision D59).
     */
    fun createSchema() {
        MongoFormat.claim(database, collectionName, FORMAT)
        timers.createIndex(
            Indexes.ascending(OWNER_AGGREGATE, OWNER_TENANT_ID, OWNER_AGGREGATE_ID, KEY),
            IndexOptions().unique(true).name(TIMER_KEY),
        )
        timers.createIndex(
            Indexes.ascending(PARTITION, DUE_AT, TIMER_ID),
            IndexOptions().name(DUE_INDEX).partialFilterExpression(Filters.`in`(STATUS, DUE_STATUSES)),
        )
        timers.createIndex(
            Indexes.ascending(TARGET_CONTEXT, TARGET_AGGREGATE, TARGET_TENANT_ID, TARGET_ID),
            IndexOptions().name(PARKED_INDEX).partialFilterExpression(Filters.eq(STATUS, TimerStatus.PARKED.name)),
        )
    }

    override suspend fun put(timer: Timer): Boolean = dispatcher.mongo {
        require(timer.owner.type.context == context) { "${timer.owner} keeps its timers in its own context." }
        val replaceable = Filters.and(
            keyFilter(timer.owner, timer.key),
            Filters.ne(TIMER_ID, timer.id),
            Filters.lte(VERSION, timer.version),
        )
        val write = Updates.combine(
            Updates.set(TIMER_ID, timer.id),
            Updates.set(VERSION, timer.version),
            Updates.set(PARTITION, timer.partition),
            Updates.set(RULE, MongoRules.document(timer.rule)),
            Updates.set(SLOT, timer.slot),
            Updates.set(DUE_AT, timer.dueAt),
            Updates.set(SLOT_DUE_AT, timer.slotDueAt),
            Updates.set(SKIPPED, timer.skipped),
            Updates.set(TARGET_CONTEXT, timer.target.type.context),
            Updates.set(TARGET_AGGREGATE, timer.target.type.name),
            Updates.set(TARGET_TENANT_ID, timer.target.tenantId),
            Updates.set(TARGET_ID, timer.target.id),
            Updates.set(COMMAND_TYPE, timer.commandType),
            Updates.set(BODY, timer.body),
            Updates.set(CORRELATION_ID, timer.causation?.correlationId),
            Updates.set(CAUSATION_ID, timer.causation?.causationId),
            Updates.set(HEADERS, Document(timer.headers)),
            Updates.set(STATUS, TimerStatus.PENDING.name),
            Updates.set(ATTEMPTS, 0),
            Updates.set(OUTCOME, null),
            Updates.set(OPERATOR, MongoOperators.document(timer.operator)),
            Updates.set(OWNER_ID, timer.scope.ownerId),
            Updates.set(SPACE_ID, timer.scope.spaceId),
        )
        conditionalUpsert { upsert ->
            val result = timers.updateOne(replaceable, write, UpdateOptions().upsert(upsert))
            result.upsertedId != null || result.matchedCount == 1L
        }
    }

    override suspend fun cancel(owner: AggregateRef, key: String, version: Long): Boolean = dispatcher.mongo {
        timers.updateOne(
            Filters.and(keyFilter(owner, key), cancellable(version)),
            cancelled(version),
        ).matchedCount == 1L
    }

    override suspend fun cancelAll(owner: AggregateRef, version: Long): Int = dispatcher.mongo {
        timers.updateMany(Filters.and(ownerFilter(owner), cancellable(version)), cancelled(version))
            .matchedCount.toInt()
    }

    override suspend fun find(owner: AggregateRef, key: String): Timer? = dispatcher.mongo {
        timers.find(keyFilter(owner, key)).firstOrNull()?.let(::timerOf)
    }

    override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> {
        require(limit >= 1) { "A page holds at least one timer." }
        if (partitions.isEmpty()) {
            return emptyList()
        }
        return dispatcher.mongo {
            timers.find(
                Filters.and(
                    Filters.`in`(PARTITION, partitions),
                    Filters.`in`(STATUS, DUE_STATUSES),
                    Filters.lte(DUE_AT, until),
                ),
            ).sort(DUE_ORDER).limit(limit).map(::timerOf).toList()
        }
    }

    override suspend fun settle(changes: List<TimerChange>): Int {
        if (changes.isEmpty()) {
            return 0
        }
        return dispatcher.mongo {
            val writes = changes.map { change ->
                val loaded = change.loaded
                val next = change.next
                UpdateOneModel<Document>(
                    Filters.and(
                        keyFilter(loaded.owner, loaded.key),
                        Filters.eq(TIMER_ID, loaded.id),
                        Filters.eq(STATUS, loaded.status.name),
                        Filters.eq(SLOT, loaded.slot),
                        Filters.eq(SLOT_DUE_AT, loaded.slotDueAt),
                        Filters.eq(ATTEMPTS, loaded.attempts),
                        Filters.eq(DUE_AT, loaded.dueAt),
                    ),
                    Updates.combine(
                        Updates.set(STATUS, next.status.name),
                        Updates.set(SLOT, next.slot),
                        Updates.set(SLOT_DUE_AT, next.slotDueAt),
                        Updates.set(SKIPPED, next.skipped),
                        Updates.set(DUE_AT, next.dueAt),
                        Updates.set(ATTEMPTS, next.attempts),
                        Updates.set(OUTCOME, next.outcome),
                    ),
                )
            }
            timers.bulkWrite(writes, BulkWriteOptions().ordered(false)).matchedCount
        }
    }

    override suspend fun parked(target: AggregateRef): List<Timer> = dispatcher.mongo {
        timers.find(
            Filters.and(
                Filters.eq(TARGET_CONTEXT, target.type.context),
                Filters.eq(TARGET_AGGREGATE, target.type.name),
                Filters.eq(TARGET_TENANT_ID, target.tenantId),
                Filters.eq(TARGET_ID, target.id),
                Filters.eq(STATUS, TimerStatus.PARKED.name),
            ),
        ).sort(DUE_ORDER).map(::timerOf).toList()
    }

    private fun ownerFilter(owner: AggregateRef): Bson = Filters.and(
        Filters.eq(OWNER_AGGREGATE, owner.type.name),
        Filters.eq(OWNER_TENANT_ID, owner.tenantId),
        Filters.eq(OWNER_AGGREGATE_ID, owner.id),
    )

    private fun keyFilter(owner: AggregateRef, key: String): Bson = Filters.and(
        ownerFilter(owner),
        Filters.eq(KEY, key)
    )

    private fun cancellable(version: Long): Bson = Filters.and(
        Filters.`in`(STATUS, TimerStatus.PENDING.name, TimerStatus.FIRING.name, TimerStatus.PARKED.name),
        Filters.lte(VERSION, version),
    )

    private fun cancelled(version: Long): Bson =
        Updates.combine(Updates.set(STATUS, TimerStatus.CANCELLED.name), Updates.max(VERSION, version))

    private fun timerOf(document: Document) = Timer(
        id = document.getString(TIMER_ID),
        owner = document.get(OWNER, Document::class.java).let { owner ->
            AggregateType(context, owner.getString(AGGREGATE)).ref(owner.getString(TENANT_ID), owner.getString(ID))
        },
        key = document.getString(KEY),
        version = document.getLong(VERSION),
        partition = document.getInteger(PARTITION),
        rule = MongoRules.of(document.get(RULE, Document::class.java)),
        slot = document.getLong(SLOT),
        dueAt = document.getLong(DUE_AT),
        target = AggregateType(document.getString(TARGET_CONTEXT), document.getString(TARGET_AGGREGATE))
            .ref(document.getString(TARGET_TENANT_ID), document.getString(TARGET_ID)),
        commandType = document.getString(COMMAND_TYPE),
        body = document.getString(BODY),
        causation = document.getString(CORRELATION_ID)?.let { Causation(it, document.getString(CAUSATION_ID)) },
        headers = document.get(HEADERS, Document::class.java).let { headers ->
            headers.keys.associateWith { headers.getString(it) }
        },
        status = TimerStatus.valueOf(document.getString(STATUS)),
        attempts = document.getInteger(ATTEMPTS),
        outcome = document.getString(OUTCOME),
        operator = MongoOperators.of(document.get(OPERATOR, Document::class.java)),
        scope = Scope(document.getString(OWNER_ID), document.getString(SPACE_ID)),
        slotDueAt = document.getLong(SLOT_DUE_AT),
        // Added within format 3 (decision D198): a document written before it skipped none.
        skipped = document.getLong(SKIPPED) ?: 0L,
    )

    private companion object {
        /** The version of this layout (decision D59). */
        const val FORMAT = 4
        const val PREFIX = "wow_timer"
        const val TIMER_KEY = "timer_key"
        const val DUE_INDEX = "timer_due"
        const val PARKED_INDEX = "timer_parked"
        val DUE_STATUSES = listOf(TimerStatus.PENDING.name, TimerStatus.FIRING.name)
        const val OWNER = "owner"
        const val AGGREGATE = "aggregate"
        const val TENANT_ID = "tenant_id"
        const val ID = "id"
        const val OWNER_AGGREGATE = "$OWNER.$AGGREGATE"
        const val OWNER_TENANT_ID = "$OWNER.$TENANT_ID"
        const val OWNER_AGGREGATE_ID = "$OWNER.$ID"
        const val KEY = "key"
        const val TIMER_ID = "timer_id"
        const val VERSION = "version"
        const val PARTITION = "partition"
        const val DUE_AT = "due_at"
        const val SLOT_DUE_AT = "slot_due_at"
        const val SKIPPED = "skipped"
        const val RULE = "rule"
        const val SLOT = "slot"
        const val TARGET_CONTEXT = "target_context"
        const val TARGET_AGGREGATE = "target_aggregate"
        const val TARGET_TENANT_ID = "target_tenant_id"
        const val TARGET_ID = "target_id"
        const val COMMAND_TYPE = "command_type"
        const val BODY = "body"
        const val CORRELATION_ID = "correlation_id"
        const val CAUSATION_ID = "causation_id"
        const val HEADERS = "headers"
        const val STATUS = "status"
        const val ATTEMPTS = "attempts"
        const val OUTCOME = "outcome"
        const val OPERATOR = "operator"
        const val OWNER_ID = "owner_id"
        const val SPACE_ID = "space_id"
        val DUE_ORDER: Bson = Sorts.ascending(DUE_AT, TIMER_ID)
    }
}
