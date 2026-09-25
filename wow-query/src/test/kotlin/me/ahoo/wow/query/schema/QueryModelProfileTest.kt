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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

class QueryModelProfileTest {
    @Test
    fun `built-in models resolve their profiles and custom models have none`() {
        QueryModelProfile.of(QueryModel.SNAPSHOT).assert().isSameAs(SnapshotQueryModelProfile)
        QueryModelProfile.of(QueryModel.EVENT_STREAM).assert().isSameAs(EventStreamQueryModelProfile)
        QueryModelProfile.of(QueryModel("CUSTOM")).assert().isNull()
    }

    @Test
    fun `profiles expose each model record layout`() {
        SnapshotQueryModelProfile.identityField.assert().isEqualTo(QueryField("aggregateId"))
        SnapshotQueryModelProfile.payloadField.assert().isEqualTo(QueryField("state"))
        SnapshotQueryModelProfile.payloadTypeField.assert().isNull()
        EventStreamQueryModelProfile.identityField.assert().isEqualTo(QueryField("id"))
        EventStreamQueryModelProfile.payloadField.assert().isEqualTo(QueryField("body.body"))
        EventStreamQueryModelProfile.payloadTypeField.assert().isEqualTo(QueryField("body.bodyType"))
    }

    @Test
    fun `snapshot default scope selects active aggregates unless the filter scopes deletion at the top level`() {
        val tenant = TenantIdFilter("tenant")
        SnapshotQueryModelProfile.defaultScope(tenant).assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
        SnapshotQueryModelProfile.defaultScope(AndFilter(listOf(tenant, DeletionFilter(DeletionState.ALL))))
            .assert().isSameAs(MatchAllFilter)
        SnapshotQueryModelProfile.defaultScope(OrFilter(listOf(tenant, DeletionFilter(DeletionState.ALL))))
            .assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
        EventStreamQueryModelProfile.defaultScope(tenant).assert().isSameAs(MatchAllFilter)
    }

    @Test
    fun `event payload projection must retain its body type`() {
        val profile = EventStreamQueryModelProfile
        assertDoesNotThrow { profile.validateProjection(Projection.ALL) }
        assertDoesNotThrow { profile.validateProjection(Projection(include = listOf(QueryField("body")))) }
        assertDoesNotThrow { profile.validateProjection(Projection(include = listOf(QueryField("aggregateId")))) }
        assertThrows<QuerySchemaValidationException> {
            profile.validateProjection(Projection(include = listOf(QueryField("body.body"))))
        }
        assertThrows<QuerySchemaValidationException> {
            profile.validateProjection(Projection(exclude = listOf(QueryField("body.bodyType"))))
        }
        assertDoesNotThrow {
            SnapshotQueryModelProfile.validateProjection(
                Projection(include = listOf(QueryField("state")))
            )
        }
    }

    @Test
    fun `event records must carry declared payload types`() {
        val declared = setOf("created")
        fun record(json: String) = JsonSerializer.readTree(json) as ObjectNode
        assertDoesNotThrow {
            EventStreamQueryModelProfile.requireDeclaredPayloadTypes(
                record("""{"body":[{"bodyType":"created","body":{}},{"bodyType":"other","body":null}]}"""),
                declared,
            )
        }
        assertThrows<QuerySchemaValidationException> {
            EventStreamQueryModelProfile.requireDeclaredPayloadTypes(
                record("""{"body":[{"bodyType":"other","body":{}}]}"""),
                declared
            )
        }
        assertThrows<QuerySchemaValidationException> {
            EventStreamQueryModelProfile.requireDeclaredPayloadTypes(record("""{"body":{}}"""), declared)
        }
    }

    @Test
    fun `system declarations come from the profiles`() {
        SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
            .assert().isSameAs(SnapshotQueryModelProfile.systemDeclaration)
        SystemQuerySchemaSource.declaration(QueryModel.EVENT_STREAM)
            .assert().isSameAs(EventStreamQueryModelProfile.systemDeclaration)
    }
}
