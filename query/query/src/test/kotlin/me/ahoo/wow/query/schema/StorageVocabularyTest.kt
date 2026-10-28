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
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.query.spec.MetricSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The storage-facts vocabulary a third-party adapter writes: its factories, its builder and its values. */
class StorageVocabularyTest {
    private val path = QueryPathTemplate.of(QueryPathSegment.Property.of("state"), QueryPathSegment.Property.of("name"))

    @Test
    fun `the facts builder should collect what an adapter reports`() {
        val bindings = QueryValueBindings.of(
            mapOf(QueryCapability.SORT to QueryFieldBindingTemplate.of(path, setOf(QueryStorageType.of("text")))),
            projectionPath = path,
            responsePath = path,
        )
        val facts = QueryStorageFacts.build {
            binding(path, bindings)
            capabilities(QueryCapability.FULL_TEXT_TERMS)
            fullProjection(false)
            approximateMetrics("PERCENTILE")
            storage(StorageSupport.of(paging = PagingSupport.of(maxOffsetWindow = 100)))
            provisional()
        }
        facts.bindings.assert().isEqualTo(mapOf(path to bindings))
        facts.capabilities.assert().containsExactly(QueryCapability.FULL_TEXT_TERMS)
        facts.fullProjectionAvailable.assert().isFalse()
        facts.approximateMetrics.assert().containsExactly("PERCENTILE")
        facts.storage.paging.maxOffsetWindow.assert().isEqualTo(100)
        facts.provisional.assert().isTrue()
        val defaults = QueryStorageFacts.builder().build()
        defaults.bindings.assert().isEmpty()
        defaults.fullProjectionAvailable.assert().isTrue()
        defaults.storage.assert().isEqualTo(StorageSupport.NATIVE)
        defaults.provisional.assert().isFalse()
    }

    @Test
    fun `the storage support should be values its factories build`() {
        val aggregation = AggregationSupport.of(
            having = SupportMode.RESIDUAL,
            windows = mapOf(WindowFunction.entries.first() to SupportMode.NATIVE),
            termsTopN = setOf(MetricSpec.COUNT),
        )
        aggregation.assert().isEqualTo(
            AggregationSupport(
                having = SupportMode.RESIDUAL,
                windows = mapOf(WindowFunction.entries.first() to SupportMode.NATIVE),
                termsTopN = setOf(MetricSpec.COUNT),
            ),
        ).isNotEqualTo(AggregationSupport.of())
        aggregation.hashCode().assert().isEqualTo(
            AggregationSupport.of(
                having = SupportMode.RESIDUAL,
                windows = mapOf(WindowFunction.entries.first() to SupportMode.NATIVE),
                termsTopN = setOf(MetricSpec.COUNT),
            ).hashCode()
        )
        aggregation.toString().assert().contains("having=RESIDUAL", "termsTopN=[COUNT]")
        aggregation.window(WindowFunction.entries.last()).assert().isEqualTo(SupportMode.NONE)
        val paging = PagingSupport.of(keyset = SupportMode.NONE, maxOffsetWindow = 10)
        paging.assert().isEqualTo(
            PagingSupport(SupportMode.NONE, SupportMode.NATIVE, 10)
        ).isNotEqualTo(PagingSupport.of())
        paging.hashCode().assert().isEqualTo(PagingSupport.of(SupportMode.NONE, SupportMode.NATIVE, 10).hashCode())
        paging.toString().assert().isEqualTo("PagingSupport(keyset=NONE, unboundedStream=NATIVE, maxOffsetWindow=10)")
        val support = StorageSupport.of(paging, aggregation, absentValues = AbsentValues.AS_MISSING)
        support.assert().isEqualTo(StorageSupport(paging, aggregation, absentValues = AbsentValues.AS_MISSING))
            .isNotEqualTo(StorageSupport.NATIVE)
        support.hashCode().assert().isEqualTo(
            StorageSupport.of(paging, aggregation, absentValues = AbsentValues.AS_MISSING).hashCode()
        )
        support.toString().assert().contains("absentValues=AS_MISSING")
        assertThrows<IllegalArgumentException> { PagingSupport.of(keyset = SupportMode.RESIDUAL) }
    }

    @Test
    fun `the paths, types and bindings should be values`() {
        QueryPathTemplate.of(listOf(QueryPathSegment.Property.of("state"), QueryPathSegment.Property.of("name")))
            .assert().isEqualTo(path)
        path.toString().assert().isEqualTo("QueryPathTemplate([Property(name=state), Property(name=name)])")
        QueryPathSegment.Key.of(
            0
        ).assert().isEqualTo(QueryPathSegment.Key.of(0)).isNotEqualTo(QueryPathSegment.Key.of(1))
        QueryPathSegment.Key.of(0).hashCode().assert().isEqualTo(0)
        QueryPathSegment.Key.of(0).toString().assert().isEqualTo("Key(slot=0)")
        QueryPathSegment.Property.of("a").hashCode().assert().isEqualTo("a".hashCode())
        QueryStorageType.of(
            "text"
        ).assert().isEqualTo(QueryStorageType("text")).isNotEqualTo(QueryStorageType.of("int"))
        QueryStorageType.of("text").hashCode().assert().isEqualTo("text".hashCode())
        QueryStorageType.of("text").toString().assert().isEqualTo("QueryStorageType(value=text)")
        assertThrows<IllegalArgumentException> { QueryStorageType.of("1x") }
        val template = QueryFieldBindingTemplate.of(path, null, QueryField("state"), indexed = true)
        template.assert().isEqualTo(QueryFieldBindingTemplate(path, null, QueryField("state"), true))
        QueryValueBindings.of(mapOf(QueryCapability.SORT to template)).projectionPath.assert().isNull()
    }

    @Test
    fun `the families' rules, a deprecation and the sensitivity policy should be values`() {
        QueryStorageFamilyRules.of(dateOperands = false, strictValueTypes = true).assert()
            .isEqualTo(QueryStorageFamilyRules(dateOperands = false)).isNotEqualTo(QueryStorageFamilyRules.STRICT)
        QueryStorageFamilyRules.of(false, true).hashCode().assert()
            .isEqualTo(QueryStorageFamilyRules(dateOperands = false).hashCode())
        QueryStorageFamilyRules.STRICT.toString().assert()
            .isEqualTo("QueryStorageFamilyRules(dateOperands=true, strictValueTypes=true)")
        QueryDeprecation("use b").assert().isEqualTo(QueryDeprecation("use b")).isNotEqualTo(QueryDeprecation())
        QueryDeprecation("use b").hashCode().assert().isEqualTo("use b".hashCode())
        QueryDeprecation().toString().assert().isEqualTo("QueryDeprecation(message=null)")
        QuerySensitivityPolicy.of(false).assert().isEqualTo(QuerySensitivityPolicy(false))
            .isNotEqualTo(QuerySensitivityPolicy.DEFAULT)
        QuerySensitivityPolicy.of(false).hashCode().assert().isEqualTo(false.hashCode())
        QuerySensitivityPolicy.DEFAULT.toString().assert().isEqualTo("QuerySensitivityPolicy(displayComparable=true)")
    }
}
