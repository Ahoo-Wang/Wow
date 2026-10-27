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

package me.ahoo.wow.mongo.log

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoOperators
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.AGGREGATE_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.CAUSATION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.CAUSATION_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.COMMAND_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.COMMAND_TYPE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.COMMIT_TIME
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.CORRELATION_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.EVENTS
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.HEADERS
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.NAME
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.OPERATOR
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.OWNER_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PARTITION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PAYLOAD
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.REQUEST_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.REVISION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SEQUENCE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SPACE_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.TAGS
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.TENANT_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.TRANSPORT
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.VERSION
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import org.bson.Document
import org.bson.types.Binary

/** The documents of a context's log on MongoDB: a stream's, as appended, and the entry or stream a read gives back. */
internal class MongoLogDocuments(private val context: String) {
    /** [stream]'s document, its sequence set when it is inserted. */
    fun documentOf(partition: Int, stream: EventStreamRecord, transport: ByteArray?) = Document()
        .append(PARTITION, partition)
        .append(SEQUENCE, 0L)
        .append(TENANT_ID, stream.ref.tenantId)
        .append(AGGREGATE_ID, stream.ref.id)
        .append(VERSION, stream.version)
        .append(COMMAND_ID, stream.commandId)
        .append(REQUEST_ID, stream.requestId)
        .append(COMMAND_TYPE, stream.commandType)
        .append(COMMIT_TIME, stream.commitTime)
        .append(
            EVENTS,
            stream.events.map {
                Document(NAME, it.name).append(REVISION, it.revision).append(PAYLOAD, ExactBson.document(it.payload))
            },
        )
        .append(OWNER_ID, stream.scope.ownerId)
        .append(SPACE_ID, stream.scope.spaceId)
        .append(
            CAUSATION,
            stream.causation?.let { Document(CORRELATION_ID, it.correlationId).append(CAUSATION_ID, it.causationId) },
        )
        .append(HEADERS, Document(stream.headers))
        .apply { MongoOperators.document(stream.operator)?.let { append(OPERATOR, it) } }
        // The tags after the commit (decision D259): absent when not recorded, an empty document for none.
        .apply { stream.tags?.let { append(TAGS, Document(it)) } }
        .apply { transport?.let { append(TRANSPORT, Binary(it)) } }

    /** The transport bytes as stored: the driver reads generic binary data as a byte array, other subtypes as [Binary]. */
    fun transportOf(document: Document): ByteArray? = when (val stored = document[TRANSPORT]) {
        null -> null
        is ByteArray -> stored
        is Binary -> stored.data
        else -> error("The transport of a stream is binary data, not ${stored::class.java.name}.")
    }

    private fun strings(document: Document): Map<String, String> = document.keys.associateWith {
        document.getString(
            it
        )
    }

    fun entryOf(aggregate: String, document: Document) = LogEntry(
        LogPosition(document.getInteger(PARTITION), document.getLong(SEQUENCE)),
        streamOf(aggregate, document),
        transportOf(document),
    )

    fun streamOf(aggregate: String, document: Document) = EventStreamRecord(
        ref = AggregateType(context, aggregate)
            .ref(document.getString(TENANT_ID), document.getString(AGGREGATE_ID)),
        version = document.getInteger(VERSION),
        commandId = document.getString(COMMAND_ID),
        requestId = document.getString(REQUEST_ID),
        commandType = document.getString(COMMAND_TYPE),
        commitTime = document.getLong(COMMIT_TIME),
        events = document.getList(EVENTS, Document::class.java).map {
            EventRecord(
                it.getString(NAME),
                it.getInteger(REVISION),
                ExactBson.json(it.get(PAYLOAD, Document::class.java)),
            )
        },
        scope = Scope(document.getString(OWNER_ID), document.getString(SPACE_ID)),
        causation = document.get(CAUSATION, Document::class.java)?.let {
            Causation(it.getString(CORRELATION_ID), it.getString(CAUSATION_ID))
        },
        headers = strings(document.get(HEADERS, Document::class.java)),
        operator = MongoOperators.of(document.get(OPERATOR, Document::class.java)),
        tags = document.get(TAGS, Document::class.java)?.let { tags ->
            tags.keys.associateWith { tags.getList(it, String::class.java).toList() }
        },
    )
}
