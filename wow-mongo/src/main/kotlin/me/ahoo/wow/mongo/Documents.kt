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
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyAsAggregateId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.asObject
import org.bson.Document
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

object Documents {
    const val ID_FIELD = "_id"

    fun Document.replaceIdAsPrimaryKey(): Document = replaceAsPrimaryKey(MessageRecords.ID)

    fun Document.replacePrimaryKeyAsId(): Document = replacePrimaryKeyAs(MessageRecords.ID)

    fun Document.replaceAggregateIdAsPrimaryKey(): Document = replaceAsPrimaryKey(MessageRecords.AGGREGATE_ID)

    fun Document.replacePrimaryKeyAsAggregateId(): Document = replacePrimaryKeyAs(MessageRecords.AGGREGATE_ID)

    fun Document.replaceAsPrimaryKey(key: String): Document {
        val id = checkNotNull(getString(key))
        append(ID_FIELD, id)
        remove(key)
        return this
    }

    fun Document.replacePrimaryKeyAs(key: String): Document {
        val primaryKey = checkNotNull(getString(ID_FIELD))
        append(key, primaryKey)
        remove(ID_FIELD)
        return this
    }
}

fun <S : Any> Document.asSnapshot(): Snapshot<S> {
    val snapshotJsonString = this.replacePrimaryKeyAsAggregateId().toJson()
    return snapshotJsonString.asObject()
}

fun <S : Any> Document.asSnapshotState(): S {
    return asSnapshot<S>().state
}

fun <S : Any> Mono<Document>.toSnapshot(): Mono<Snapshot<S>> {
    return map {
        it.asSnapshot()
    }
}

fun <S : Any> Mono<Document>.toSnapshotState(): Mono<S> {
    return map {
        it.asSnapshotState<S>()
    }
}

fun <S : Any> Flux<Document>.toSnapshot(): Flux<Snapshot<S>> {
    return map {
        it.asSnapshot()
    }
}

fun <S : Any> Flux<Document>.toSnapshotState(): Flux<S> {
    return map {
        it.asSnapshotState<S>()
    }
}
