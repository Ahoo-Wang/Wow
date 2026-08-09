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

package me.ahoo.wow.query.internal.gateway

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.SystemFieldKind
import org.junit.jupiter.api.Test

class LegacyQuerySchemaTest {
    @Test
    fun `snapshot aliases should resolve framework-owned logical fields`() {
        val schema = legacyQuerySchema(QueryTarget(AGGREGATE, QueryDocumentKind.SNAPSHOT))

        schema.resolveField(path("aggregateId")).assert().isEqualTo(system(SystemFieldKind.IDENTITY))
        schema.resolveField(path("tenantId")).assert().isEqualTo(system(SystemFieldKind.TENANT_ID))
        schema.resolveField(path("ownerId")).assert().isEqualTo(system(SystemFieldKind.OWNER_ID))
        schema.resolveField(path("spaceId")).assert().isEqualTo(system(SystemFieldKind.SPACE_ID))
        schema.resolveField(path("deleted")).assert().isEqualTo(system(SystemFieldKind.DELETED))
    }

    @Test
    fun `event stream aliases should distinguish stream identity and aggregate identity`() {
        val schema = legacyQuerySchema(QueryTarget(AGGREGATE, QueryDocumentKind.EVENT_STREAM))

        schema.resolveField(path("id")).assert().isEqualTo(system(SystemFieldKind.IDENTITY))
        schema.resolveField(path("aggregateId")).assert().isEqualTo(system(SystemFieldKind.AGGREGATE_ID))
        schema.resolveField(path("deleted")).assert().isNull()
    }

    private fun path(field: String): QueryFieldId.Path = QueryFieldId.Path(listOf(field))

    private fun system(kind: SystemFieldKind): QueryFieldId.System = QueryFieldId.System(kind)

    private companion object {
        val AGGREGATE = MaterializedNamedAggregate("sales", "order")
    }
}
