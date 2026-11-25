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

package me.ahoo.wow.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * A secret and a time that the state's interface declares on its getters (`StorageConfig` of the
 * `manifest-tools` snapshot `inherited-state.defs.json`, which `QueryKeywordsInheritanceTest` holds to what
 * `manifest-tools` writes): the snapshot query masks the secret, never compares it, and reads the time as epoch
 * seconds, end to end from the Manifest through the gateway.
 */
class InheritedAnnotationsGatewayTest {
    private suspend fun gateway(): DefaultQueryGateway {
        val storage = InMemoryQueryStorage()
        storage.save(
            SnapshotRecord(
                ref = ManifestFixtures.ORDER.ref("tenant", "o1"),
                version = 1,
                schemaFingerprint = "test",
                state = """{"accessKeySecret":"AKIA-SECRET","name":"config","updatedAt":1700000000}""",
                scope = Scope("owner"),
                firstEventTime = 1,
                lastEventTime = 1,
            ),
        )
        val manifest = ManifestFixtures.manifest(ManifestFixtures.INHERITED_DEFS, ManifestFixtures.INHERITED_STATE)
        val schema = ManifestFixtures.schemaOf(ManifestQuerySchemaSource(manifest, ManifestFixtures.STRATEGIES))
        return DefaultQueryGateway(
            ManifestFixtures.ORDER,
            storage.binding(ManifestFixtures.ORDER).backend,
            FixedQueryModelSchemaProvider(schema),
        )
    }

    @Test
    fun `a secret declared on the interface's getter should be masked in a snapshot query`() {
        runTest {
            val state = gateway().list(ListQuery(MatchAllFilter, limit = 10), QueryCaller.IN_PROCESS).toList()
                .single().path("state")
            state.path("accessKeySecret").stringValue().assert().isEqualTo("***********")
            state.path("name").stringValue().assert().isEqualTo("config")
        }
    }

    @Test
    fun `a secret declared on the interface's getter should never be compared`() {
        runTest {
            val error = runCatching {
                gateway().count(
                    CountQuery(EqualFilter(QueryField("state.accessKeySecret"), "AKIA-SECRET")),
                    QueryCaller.IN_PROCESS,
                )
            }.exceptionOrNull()
            (error as QueryError).violations.first().code.assert().isEqualTo(QueryErrorCodes.PROTECTED_COMPARISON)
        }
    }

    @Test
    fun `a time declared on the interface's getter should keep its epoch seconds`() {
        runTest {
            val schema = ManifestFixtures.schemaOf(
                ManifestQuerySchemaSource(
                    ManifestFixtures.manifest(ManifestFixtures.INHERITED_DEFS, ManifestFixtures.INHERITED_STATE),
                    ManifestFixtures.STRATEGIES,
                ),
            )
            val updatedAt = checkNotNull(schema.field(QueryField("state.updatedAt"))).value
            updatedAt.semanticType.assert().isEqualTo(Temporal.Epoch(TimeUnit.SECONDS))
        }
    }
}
