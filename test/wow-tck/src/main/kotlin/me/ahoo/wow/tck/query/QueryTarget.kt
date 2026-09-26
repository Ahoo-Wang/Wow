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

package me.ahoo.wow.tck.query

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory

/**
 * A backend with the schema the Catalog compiles for it: what a backend spec queries through trusted admission,
 * as a gateway would, but without governance.
 */
class QueryTarget<out B : QueryBackend>(val backend: B, val schemaProvider: QueryModelSchemaProvider)

/** [namedAggregate]'s snapshot backend, with the schema a Catalog over [sources] compiles from its storage facts. */
fun SnapshotQueryBackendFactory.target(
    namedAggregate: NamedAggregate,
    sources: List<QuerySchemaSource> = emptyList(),
): QueryTarget<SnapshotQueryBackend> = QueryTarget(
    create(namedAggregate).backend,
    QuerySchemaCatalog(
        snapshots = this,
        compiler = QueryModelCompiler.of(sources)
    ).provider(namedAggregate, QueryModel.SNAPSHOT),
)

/** [namedAggregate]'s event-stream backend, with the schema a Catalog over [sources] compiles from its storage facts. */
fun EventStreamQueryBackendFactory.target(
    namedAggregate: NamedAggregate,
    sources: List<QuerySchemaSource> = emptyList(),
): QueryTarget<EventStreamQueryBackend> = QueryTarget(
    create(namedAggregate).backend,
    QuerySchemaCatalog(
        eventStreams = this,
        compiler = QueryModelCompiler.of(sources)
    ).provider(namedAggregate, QueryModel.EVENT_STREAM),
)
