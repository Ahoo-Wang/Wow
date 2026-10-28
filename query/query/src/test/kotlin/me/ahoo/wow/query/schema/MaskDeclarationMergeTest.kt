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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A source of higher priority (a declaration registered in code) replacing a structure the Manifest masks keeps the
 * masks, or fails: protection is never dropped by a merge.
 */
class MaskDeclarationMergeTest {
    private val field = QueryField("state.value")
    private val display = MaskRule(SensitivityLevel.DISPLAY)
    private val confidential = MaskRule(SensitivityLevel.CONFIDENTIAL)

    private fun string(rule: MaskRule? = null) = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.SCALAR),
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
        nullable = DeclarationValue.Set(false),
        maskRule = rule?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
    )

    private fun integer() = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.SCALAR),
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)),
    )

    private fun obj(vararg properties: Pair<String, QueryFieldDeclaration>) = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.OBJECT),
        properties = DeclarationValue.Set(properties.toMap()),
    )

    private fun map(values: QueryFieldDeclaration) = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.OBJECT),
        additionalProperties = DeclarationValue.Set(values),
    )

    private fun array(items: QueryFieldDeclaration) = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.ARRAY),
        items = DeclarationValue.Set(items),
    )

    private fun union(vararg alternatives: QueryFieldDeclaration) = QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.UNION),
        alternatives = DeclarationValue.Set(alternatives.toList()),
    )

    private fun QueryFieldDeclaration.over(lower: QueryFieldDeclaration, rejectDifferent: Boolean = false) =
        lower.merge(this, field, rejectDifferent)

    @Test
    fun `replacing array items keeps the masks of their properties`() {
        val lower = array(obj("secret" to string(display), "name" to string()))
        val merged = array(obj("secret" to string(), "name" to string(), "extra" to string())).over(lower)
        val items = checkNotNull(merged.items.valueOr(null))
        items.properties.valueOr(
            emptyMap()
        ).getValue("secret").maskRule.assert().isEqualTo(DeclarationValue.Set(display))
        items.properties.valueOr(emptyMap()).getValue("extra").maskRule.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `replacing map values keeps their masks, and new properties inherit the dynamic mask`() {
        val lower = map(obj("secret" to string(display)))
        val merged = map(obj("secret" to string())).over(lower)
        merged.additionalProperties.valueOr(null)?.properties?.valueOr(emptyMap())?.getValue("secret")?.maskRule
            .assert().isEqualTo(DeclarationValue.Set(display))
        // A property declared by name under a masked map takes the map's value masks.
        val named = obj("known" to obj("secret" to string())).over(map(obj("secret" to string(display))))
        named.properties.valueOr(emptyMap()).getValue("known").properties.valueOr(emptyMap()).getValue("secret")
            .maskRule.assert().isEqualTo(DeclarationValue.Set(display))
    }

    @Test
    fun `replacing union alternatives keeps the mask of the matching alternative`() {
        val lower = union(obj("secret" to string(confidential)), integer())
        val merged = union(integer(), obj("secret" to string(), "other" to string())).over(lower)
        val alternatives = merged.alternatives.valueOr(emptyList())
        alternatives[1].properties.valueOr(emptyMap()).getValue("secret").maskRule
            .assert().isEqualTo(DeclarationValue.Set(confidential))
        // Two declarations at one priority may not state different alternatives.
        assertThrows<QuerySchemaConflictException> {
            union(integer(), obj("secret" to string())).over(lower, rejectDifferent = true)
        }
    }

    @Test
    fun `nested arrays and unions under a replaced structure keep their masks`() {
        val nested = array(array(obj("secret" to string(display)))).run {
            array(array(obj("secret" to string(), "more" to string()))).over(this)
        }
        nested.items.valueOr(null)!!.items.valueOr(null)!!.properties.valueOr(emptyMap()).getValue("secret").maskRule
            .assert().isEqualTo(DeclarationValue.Set(display))
        val alternatives = array(union(obj("secret" to string(display)), integer())).run {
            array(union(integer(), obj("secret" to string()))).over(this)
        }.items.valueOr(null)!!.alternatives.valueOr(emptyList())
        alternatives[1].properties.valueOr(emptyMap()).getValue("secret").maskRule
            .assert().isEqualTo(DeclarationValue.Set(display))
    }

    @Test
    fun `a replacement that cannot carry a mask fails`() {
        // The masked string becomes an integer.
        assertThrows<QuerySchemaConflictException> {
            array(
                obj("secret" to integer())
            ).over(array(obj("secret" to string(display))))
        }
        // The masked property disappears from the items.
        assertThrows<QuerySchemaConflictException> {
            array(
                obj("name" to string())
            ).over(array(obj("secret" to string(display))))
        }
        // The array of masked items becomes an array of strings.
        assertThrows<QuerySchemaConflictException> {
            array(
                array(string())
            ).over(array(array(obj("secret" to string(display)))))
        }
        // The masked map values disappear.
        assertThrows<QuerySchemaConflictException> { map(integer()).over(map(obj("secret" to string(display)))) }
        // No alternative, or two alternatives, could hold the masked one.
        val lower = union(obj("secret" to string(display)), integer())
        assertThrows<QuerySchemaConflictException> { union(integer(), string()).over(lower) }
        assertThrows<QuerySchemaConflictException> {
            union(obj("secret" to string()), obj("secret" to string(), "b" to string())).over(lower)
        }
        // A union of masked branches over one that is not a union.
        assertThrows<QuerySchemaConflictException> {
            array(obj("u" to string())).over(array(obj("u" to union(obj("secret" to string(display)), integer()))))
        }
    }

    @Test
    fun `two sources state one mask rule, or conflict`() {
        string(display).over(string(display)).maskRule.assert().isEqualTo(DeclarationValue.Set(display))
        string().over(string(display)).maskRule.assert().isEqualTo(DeclarationValue.Set(display))
        string(display).over(string()).maskRule.assert().isEqualTo(DeclarationValue.Set(display))
        assertThrows<QuerySchemaConflictException> { string(confidential).over(string(display)) }
    }

    @Test
    fun `mask string domains reach through arrays and nullable unions`() {
        array(string()).isMaskStringDomain().assert().isTrue()
        array(integer()).isMaskStringDomain().assert().isFalse()
        val nullBranch = QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.NULL))
        union(string(), nullBranch).isMaskStringDomain().assert().isTrue()
        union(string(), integer()).isMaskStringDomain().assert().isFalse()
        obj().isMaskStringDomain().assert().isFalse()
        QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.ARRAY)).isMaskStringDomain().assert().isFalse()
    }
}
