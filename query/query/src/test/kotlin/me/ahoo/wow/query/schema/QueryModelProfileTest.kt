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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.spec.SystemField
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class QueryModelProfileTest {
    private val custom = QueryModel("CUSTOM")

    @Test
    fun `built-in models resolve their profiles and custom models have none`() {
        QueryModelProfile.of(QueryModel.SNAPSHOT).assert().isSameAs(SnapshotQueryModelProfile)
        QueryModelProfile.of(QueryModel.DATASET).assert().isNull()
        QueryModelProfile.of(custom).assert().isNull()
        SnapshotQueryModelProfile.model.assert().isEqualTo(QueryModel.SNAPSHOT)
    }

    @Test
    fun `profiles expose each model record layout`() {
        SnapshotQueryModelProfile.identityField.assert().isEqualTo(QueryField("aggregateId"))
        SnapshotQueryModelProfile.payloadField.assert().isEqualTo(QueryField("state"))
        SnapshotQueryModelProfile.eventTimeField.assert().isEqualTo(QueryField("lastEventTime"))
        SnapshotQueryModelProfile.firstEventTimeField.assert().isEqualTo(QueryField("firstEventTime"))
        SnapshotQueryModelProfile.requiredScope.assert().isEqualTo(QueryField("tenantId"))
        SnapshotQueryModelProfile.fixedTenantId.assert().isNull()
    }

    @Test
    fun `system-field filters target the record identity and metadata fields`() {
        mapOf(
            SystemField.IDENTITY to "aggregateId",
            SystemField.AGGREGATE_ID to "aggregateId",
            SystemField.TENANT_ID to "tenantId",
            SystemField.OWNER_ID to "ownerId",
            SystemField.SPACE_ID to "spaceId",
            SystemField.DELETED to "deleted",
        ).forEach { (field, path) ->
            SnapshotQueryModelProfile.systemField(field).assert().describedAs(field.name).isEqualTo(QueryField(path))
            boundSchemaFixture(objectFixture()).systemField(field).assert().isEqualTo(QueryField(path))
        }
        assertThrows<IllegalStateException> { QueryModelProfile.metadataField(SystemField.IDENTITY) }
    }

    @Test
    fun `a custom model shares the metadata fields but defines no identity`() {
        val schema = boundSchemaFixture(objectFixture(), model = custom)
        schema.profile.assert().isNull()
        schema.systemField(SystemField.TENANT_ID).assert().isEqualTo(QueryField("tenantId"))
        schema.systemField(SystemField.DELETED).assert().isEqualTo(QueryField("deleted"))
        val rejection = assertThrows<QuerySchemaValidationException> { schema.systemField(SystemField.IDENTITY) }
        rejection.violation.assert().isEqualTo(QueryViolation.IdentityUndefined(custom))
        rejection.violation!!.code.assert().isEqualTo(QueryErrorCodes.IDENTITY_UNDEFINED)
        assertThrows<QuerySchemaValidationException> { schema.requireIdentityField() }
        boundSchemaFixture(objectFixture()).requireIdentityField().assert().isEqualTo(QueryField("aggregateId"))
    }

    @Test
    fun `first and last order by their own field, or by the event time at the record level`() {
        val snapshot = boundSchemaFixture(objectFixture())
        val last = AggregationMetric.Last(QueryField("state.status"), "last")
        val ordered = AggregationMetric.First(QueryField("state.status"), "first", orderBy = QueryField("version"))

        snapshot.firstLastOrderBy(last, null).assert().isEqualTo(QueryField("lastEventTime"))
        snapshot.firstLastOrderBy(ordered, null).assert().isEqualTo(QueryField("version"))
        snapshot.firstLastOrderBy(ordered, QueryField("state.lines")).assert().isEqualTo(QueryField("version"))
        assertThrows<QuerySchemaValidationException> { snapshot.firstLastOrderBy(last, QueryField("state.lines")) }
            .violation.assert().isEqualTo(QueryViolation.FirstLastRequiresOrderBy(QueryField("last")))
        assertThrows<QuerySchemaValidationException> {
            boundSchemaFixture(objectFixture(), model = custom).firstLastOrderBy(last, null)
        }
    }

    @Test
    fun `snapshot default scope selects active aggregates unless the filter scopes deletion at the top level`() {
        val tenant = TenantIdFilter("tenant")
        SnapshotQueryModelProfile.defaultScope(tenant).assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
        SnapshotQueryModelProfile.defaultScope(DeletionFilter(DeletionState.DELETED)).assert().isSameAs(MatchAllFilter)
        SnapshotQueryModelProfile.defaultScope(AndFilter(listOf(tenant, DeletionFilter(DeletionState.ALL))))
            .assert().isSameAs(MatchAllFilter)
        SnapshotQueryModelProfile.defaultScope(
            AndFilter(listOf(tenant, AndFilter(listOf(DeletionFilter(DeletionState.ALL))))),
        ).assert().isSameAs(MatchAllFilter)
        SnapshotQueryModelProfile.defaultScope(OrFilter(listOf(tenant, DeletionFilter(DeletionState.ALL))))
            .assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
    }

    @Test
    fun `a snapshot projection is never rejected`() {
        assertDoesNotThrow { SnapshotQueryModelProfile.validateProjection(Projection.ALL) }
        assertDoesNotThrow {
            SnapshotQueryModelProfile.validateProjection(Projection(include = listOf(QueryField("state"))))
        }
    }

    @Test
    fun `system declarations come from the profiles`() {
        SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
            .assert().isSameAs(SnapshotQueryModelProfile.systemDeclaration)
    }
}
