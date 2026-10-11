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

package me.ahoo.wow.clickhouse.query

import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * S0 spike: one dataset, a declared ClickHouse table, assembled from the existing query core without changing it.
 * The dataset is named by a pseudo aggregate (`bi.<name>`) and the custom model `DATASET`, the shortcut the design
 * keeps out of the mainline (§9 Q5).
 */
class ClickHouseDataset(
    val name: String,
    database: String,
    table: String,
    declaration: QuerySchemaDeclaration,
    executor: ClickHouseExecutor,
    sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
) {
    val namedAggregate = MaterializedNamedAggregate(PSEUDO_CONTEXT, name)

    private val tableMetadata: Mono<ClickHouseTable> = executor.table(database, table).cache(
        { CACHE_TTL },
        { Duration.ZERO },
        { Duration.ZERO },
    )

    val schemaProvider: QueryModelSchemaProvider = QueryModelCompiler.of(
        sources = listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context(), declaration)))),
        sensitivity = sensitivity,
    ).compile(context(), ClickHouseQuerySchemaAdapter { tableMetadata })

    val backend = ClickHouseDatasetQueryBackend(
        namedAggregate = namedAggregate,
        executor = executor,
        table = { tableMetadata },
        fields = declaration.fields.keys.map { it.path },
    )

    private fun context() = QuerySchemaContext(namedAggregate, DATASET)

    companion object {
        val DATASET = QueryModel("DATASET")
        private const val PSEUDO_CONTEXT = "bi"
        private val CACHE_TTL: Duration = Duration.ofMinutes(5)
    }
}
