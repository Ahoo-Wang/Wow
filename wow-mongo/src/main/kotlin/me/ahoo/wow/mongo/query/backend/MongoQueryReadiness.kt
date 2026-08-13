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

@file:JvmSynthetic

package me.ahoo.wow.mongo.query.backend

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendReadinessReason
import org.bson.Document
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Collections

internal class MongoQueryReadinessRequirements(
    textFields: Set<String>,
    val configurationValid: Boolean
) {
    val textFields: Set<String> = Collections.unmodifiableSet(LinkedHashSet(textFields))
}

internal class MongoQueryReadiness(
    private val database: MongoDatabase,
    private val collectionName: String,
    private val requirements: MongoQueryReadinessRequirements
) {
    fun inspect(): Mono<QueryBackendReadiness> {
        if (!requirements.configurationValid) {
            return Mono.just(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.CONFIGURATION_INVALID))
        }
        return Flux.from(database.listCollectionNames())
            .any(collectionName::equals)
            .flatMap { exists ->
                if (!exists) {
                    Mono.just(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING))
                } else {
                    inspectIndexes()
                }
            }
            .onErrorReturn(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.DEPENDENCY_UNAVAILABLE))
    }

    private fun inspectIndexes(): Mono<QueryBackendReadiness> {
        if (requirements.textFields.isEmpty()) {
            return Mono.just(QueryBackendReadiness.Ready)
        }
        return Flux.from(database.getCollection(collectionName).listIndexes())
            .map(::textIndexFields)
            .any { indexedFields -> indexedFields == requirements.textFields }
            .map<QueryBackendReadiness> { indexed ->
                if (indexed) {
                    QueryBackendReadiness.Ready
                } else {
                    QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING)
                }
            }
    }

    private fun textIndexFields(index: Document): Set<String> {
        val keys = index["key"] as? Document ?: return emptySet()
        val direct = keys.entries.filter { (_, kind) -> kind == "text" }.mapTo(LinkedHashSet()) { it.key }
        if (direct.remove("_fts")) {
            val weights = index["weights"] as? Document
            weights?.keys?.let(direct::addAll)
        }
        return direct
    }
}
