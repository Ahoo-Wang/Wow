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

@file:OptIn(ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.gateway

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryService

@ExperimentalQueryGatewayApi
enum class QueryElementPathMode {
    /** MongoDB `$elemMatch`: child fields are relative to the current array element. */
    CURRENT_ELEMENT_RELATIVE,

    /** Elasticsearch `nested`: child fields retain the complete root-qualified path. */
    ROOT_QUALIFIED,
}

@ExperimentalQueryGatewayApi
enum class QueryMatchScopeMode {
    /** The legacy backend treats MATCH as collection/document-wide full-text search. */
    DOCUMENT,

    /** The legacy backend binds MATCH to the supplied field. */
    FIELD,
}

@ExperimentalQueryGatewayApi
data class QueryLegacyDialect(
    val elementPathMode: QueryElementPathMode,
    val matchScopeMode: QueryMatchScopeMode,
)

@ExperimentalQueryGatewayApi
fun interface QueryLegacyDialectResolver {
    /** Must return the dialect of the raw storage route selected for this exact target. */
    fun resolve(target: QueryTarget): QueryLegacyDialect
}

/**
 * Resolves raw storage services without sharing the public application-facade factory type.
 *
 * Spring integrations must implement this from storage bindings. A Gateway-backed facade can therefore never be
 * selected recursively as its own raw backend.
 */
@ExperimentalQueryGatewayApi
interface QueryRawServiceSource {
    fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*>

    fun eventStream(namedAggregate: NamedAggregate): EventStreamQueryService
}
