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

import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToAggregateId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toObject
import org.bson.Document
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

object Documents {
    const val ID_FIELD = "_id"

    fun Document.replaceIdToPrimaryKey(): Document = replaceToPrimaryKey(MessageRecords.ID)

    fun Document.replacePrimaryKeyToId(): Document = replacePrimaryKeyTo(MessageRecords.ID)

    fun Document.replaceAggregateIdToPrimaryKey(): Document = replaceToPrimaryKey(MessageRecords.AGGREGATE_ID)

    fun Document.replacePrimaryKeyToAggregateId(): Document = replacePrimaryKeyTo(MessageRecords.AGGREGATE_ID)

    fun Document.replaceToPrimaryKey(key: String): Document {
        val id = checkNotNull(getString(key))
        append(ID_FIELD, id)
        remove(key)
        return this
    }

    fun Document.replacePrimaryKeyTo(key: String): Document {
        val primaryKey = checkNotNull(getString(ID_FIELD))
        append(key, primaryKey)
        remove(ID_FIELD)
        return this
    }
}

fun <S : Any> Document.toSnapshot(): Snapshot<S> {
    val snapshotJsonString = this.replacePrimaryKeyToAggregateId().toJson()
    return snapshotJsonString.toObject()
}

fun <S : Any> Document.toSnapshotState(): S {
    return toSnapshot<S>().state
}

fun <S : Any> Mono<Document>.toSnapshot(): Mono<Snapshot<S>> {
    return map {
        it.toSnapshot()
    }
}

fun <S : Any> Mono<Document>.toSnapshotState(): Mono<S> {
    return map {
        it.toSnapshotState<S>()
    }
}

fun <S : Any> Flux<Document>.toSnapshot(): Flux<Snapshot<S>> {
    return map {
        it.toSnapshot()
    }
}

fun <S : Any> Flux<Document>.toSnapshotState(): Flux<S> {
    return map {
        it.toSnapshotState<S>()
    }
}
