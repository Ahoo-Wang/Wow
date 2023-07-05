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

package me.ahoo.wow.mongo.error

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.event.error.EventFunctionError
import me.ahoo.wow.event.error.EventFunctionErrorRepository
import me.ahoo.wow.mongo.Documents.replaceIdAsPrimaryKey
import me.ahoo.wow.serialization.asJsonString
import org.bson.Document
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class MongoEventFunctionErrorRepository(private val database: MongoDatabase) : EventFunctionErrorRepository {
    companion object {
        const val COLLECTION_NAME = "event_function_error"
    }

    override fun record(eventFunctionError: EventFunctionError): Mono<Void> {
        val eventFunctionErrorJson = eventFunctionError.asJsonString()
        val document = Document.parse(eventFunctionErrorJson).replaceIdAsPrimaryKey()
        return database.getCollection(COLLECTION_NAME)
            .insertOne(document)
            .toMono()
            .then()
    }
}
