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

import com.clickhouse.client.api.Client
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.DATASET
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/**
 * With ClickHouse unreachable, a dataset query answers that the store is unavailable (finding F24): a retryable 503,
 * logged as a warning, not a broken schema answered 500 and logged as an error.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseUnreachableQueriesTest {
    /** Nothing listens on port 1 of this host: every request is refused. */
    private val client = Client.Builder().addEndpoint("http://127.0.0.1:1").setUsername("default").setPassword("")
        .build()

    @AfterAll
    fun close() {
        client.close()
    }

    @Test
    fun `a dataset query of an unreachable store is unavailable`(): Unit = runBlocking {
        val datasets = QueryDatasets(listOf(DATASET))
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(
                datasetFactory = ClickHouseDatasetQueryBackendFactory(client),
                datasets = datasets
            ),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
        val failure = assertThrows<QueryStorageUnavailableException> {
            catalog.schema(DATASET.name, QueryModel.DATASET)
        }
        failure.errorCode.assert().isEqualTo(QueryErrors.STORAGE_UNAVAILABLE)
    }
}
