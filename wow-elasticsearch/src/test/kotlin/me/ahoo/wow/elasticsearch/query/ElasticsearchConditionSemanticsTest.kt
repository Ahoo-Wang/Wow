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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotConditionConverter
import org.junit.jupiter.api.Test

class ElasticsearchConditionSemanticsTest {
    @Test
    fun `string conditions omit case insensitive option by default`() {
        val contains = Condition.contains("field", "value").convertWithoutDeletionGuard().wildcard()
        val startsWith = Condition.startsWith("field", "value").convertWithoutDeletionGuard().prefix()
        val endsWith = Condition.endsWith("field", "value").convertWithoutDeletionGuard().wildcard()

        contains.caseInsensitive().assert().isNull()
        startsWith.caseInsensitive().assert().isNull()
        endsWith.caseInsensitive().assert().isNull()
    }

    @Test
    fun `elemMatch qualifies relative fields recursively`() {
        val condition =
            Condition.elemMatch(
                "items",
                Condition.and(
                    Condition.eq("items.sku", "sku-a"),
                    Condition.or(
                        Condition.eq("color", "red"),
                        Condition.eq("color", "blue"),
                    ),
                    Condition.nor(Condition.eq("status", "disabled")),
                    Condition.elemMatch("attributes", Condition.eq("name", "size")),
                ),
            )

        val nested = condition.convertWithoutDeletionGuard().nested()
        nested.path().assert().isEqualTo("items")
        val nestedFilters = nested.query().bool().filter()
        nestedFilters[0].term().field().assert().isEqualTo("items.sku")
        nestedFilters[1].bool().minimumShouldMatch().assert().isEqualTo("1")
        nestedFilters[1].bool().should().map { it.term().field() }
            .assert().containsExactly("items.color", "items.color")
        nestedFilters[2].bool().mustNot().single().term().field().assert().isEqualTo("items.status")
        val nestedAttributes = nestedFilters[3].nested()
        nestedAttributes.path().assert().isEqualTo("items.attributes")
        nestedAttributes.query().term().field().assert().isEqualTo("items.attributes.name")
    }

    @Test
    fun `elemMatch preserves fieldless child conditions`() {
        val condition = Condition.elemMatch("items", Condition.all())

        val nested = condition.convertWithoutDeletionGuard().nested()
        nested.path().assert().isEqualTo("items")
        nested.query()._kind().assert().isEqualTo(Query.Kind.MatchAll)
    }

    private fun Condition.convertWithoutDeletionGuard(): Query =
        SnapshotConditionConverter.convert(this).bool().filter().last()
}
