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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy.Companion.toFilterExpression
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import kotlin.reflect.jvm.javaField

class RecordAdmissionTest {
    private fun record(tags: String = "{}", deleted: Boolean = false): ObjectNode =
        """{"aggregateId":"a1","tenantId":"t1","ownerId":"o1","spaceId":"s1","deleted":$deleted,
           "tags":$tags,"state":{"items":[{"sku":"x"},{"sku":"y"}],"name":"n"}}""".toJsonNode()

    @Test
    fun `metadata and logical nodes`() {
        val record = record()
        MatchAllFilter.admitsRecord(record).assert().isTrue()
        MatchNoneFilter.admitsRecord(record).assert().isFalse()
        AndFilter(listOf(TenantIdFilter("t1"), OwnerIdFilter("o1"), SpaceIdFilter("s1"), IdFilter("a1")))
            .admitsRecord(record).assert().isTrue()
        AndFilter(listOf(TenantIdFilter("t1"), OwnerIdFilter("other"))).admitsRecord(record).assert().isFalse()
        OrFilter(listOf(OwnerIdFilter("other"), SpaceIdFilter("s1"))).admitsRecord(record).assert().isTrue()
        NorFilter(listOf(OwnerIdFilter("o1"))).admitsRecord(record).assert().isFalse()
        DeletionFilter(DeletionState.ACTIVE).admitsRecord(record).assert().isTrue()
        DeletionFilter(DeletionState.DELETED).admitsRecord(record).assert().isFalse()
        DeletionFilter(DeletionState.ACTIVE).admitsRecord(record(deleted = true)).assert().isFalse()
        DeletionFilter(DeletionState.ALL).admitsRecord(record(deleted = true)).assert().isTrue()
    }

    @Test
    fun `field nodes cross arrays and unsupported nodes fail closed`() {
        val record = record()
        EqualFilter(QueryField("state.items.sku"), StringNode.valueOf("y")).admitsRecord(record).assert().isTrue()
        EqualFilter(QueryField("state.name"), StringNode.valueOf("x")).admitsRecord(record).assert().isFalse()
        ContainsFilter(QueryField("state.name"), "n").admitsRecord(record).assert().isFalse()
    }

    @Test
    fun `abac policy filters match the documented table`() {
        val principal = mapOf("dept" to listOf("eng", "ops")).toFilterExpression()
        principal.admitsRecord(record("""{"dept":["eng"]}""")).assert().isTrue()
        principal.admitsRecord(record("""{"dept":["hr"]}""")).assert().isFalse()
        principal.admitsRecord(record("""{}""")).assert().isTrue()
        principal.admitsRecord(record("""{"dept":[]}""")).assert().isTrue()
        val strict = mapOf("dept" to listOf("eng")).toFilterExpression(matchMissingTagKey = false)
        strict.admitsRecord(record("""{}""")).assert().isFalse()
        strict.admitsRecord(record("""{"dept":[]}""")).assert().isFalse()
        strict.admitsRecord(record("""{"dept":["eng"]}""")).assert().isTrue()
        val wildcard = mapOf("dept" to listOf("*")).toFilterExpression()
        wildcard.admitsRecord(record("""{"dept":["hr"]}""")).assert().isTrue()
        wildcard.admitsRecord(record("""{}""")).assert().isFalse()
    }

    @Test
    fun `maskRecord applies the schema masks and leaves an unmasked schema's record alone`() {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        val mask = MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
        val masked = boundSchemaFixture(objectFixture("state" to objectFixture("secret" to scalarFixture(mask = mask))))
        masked.maskRecord("""{"state":{"secret":"abc"}}""".toJsonNode())["state"]["secret"].stringValue()
            .assert().isEqualTo("***")
        val plain = boundSchemaFixture(objectFixture("state" to objectFixture("secret" to scalarFixture())))
        plain.maskRecord("""{"state":{"secret":"abc"}}""".toJsonNode())["state"]["secret"].stringValue()
            .assert().isEqualTo("abc")
    }

    private data class Masked(@field:Mask val secret: String)
}
