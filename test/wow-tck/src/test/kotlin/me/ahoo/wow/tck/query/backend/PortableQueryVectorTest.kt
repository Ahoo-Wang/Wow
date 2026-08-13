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

package me.ahoo.wow.tck.query.backend

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.query.backend.QueryPortableFeature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortableQueryVectorTest {
    @Test
    fun `every portable operator has positive negative and boundary vectors`() {
        val vectorsByOperator = PortableQueryDataset.vectors
            .filter { it.key is PortableContractKey.Operator }
            .groupBy { (it.key as PortableContractKey.Operator).value }

        assertEquals(PortableOperator.entries.toSet(), vectorsByOperator.keys)
        PortableOperator.entries.forEach { operator ->
            assertEquals(
                PortableVectorKind.entries.toSet(),
                vectorsByOperator.getValue(operator).map(PortableQueryVector::kind).toSet(),
                operator.name
            )
        }
        val keys = PortableQueryDataset.vectors.map(PortableQueryVector::key).toSet()
        assertTrue(PortableContractKey.Feature(QueryPortableFeature.ELEMENT_MATCH) in keys)
        LogicalOperator.entries.forEach { operator ->
            assertTrue(PortableContractKey.Logical(operator) in keys, operator.name)
        }
        QueryOperation.entries.forEach { operation ->
            assertTrue(PortableContractKey.Operation(operation) in keys, operation.name)
        }
    }

    @Test
    fun `dataset has ten immutable logical documents and both document wrappers`() {
        assertEquals(10, PortableQueryDataset.documents.size)
        assertEquals(
            PortableQueryDataset.documents.map(PortableQueryDocument::logicalId),
            PortableQueryDataset.snapshotDocuments.map(PortableStoredQueryDocument::logicalId)
        )
        assertEquals(
            PortableQueryDataset.documents.map(PortableQueryDocument::logicalId),
            PortableQueryDataset.eventStreamDocuments.map(PortableStoredQueryDocument::logicalId)
        )
        assertEquals(
            setOf(QueryDocumentKind.SNAPSHOT),
            PortableQueryDataset.snapshotDocuments.map(PortableStoredQueryDocument::documentKind).toSet()
        )
        assertEquals(
            setOf(QueryDocumentKind.EVENT_STREAM),
            PortableQueryDataset.eventStreamDocuments.map(PortableStoredQueryDocument::documentKind).toSet()
        )

        @Suppress("UNCHECKED_CAST")
        val documents = PortableQueryDataset.documents as MutableList<PortableQueryDocument>
        assertThrows(UnsupportedOperationException::class.java) { documents.clear() }
    }

    @Test
    fun `documents vectors and expectations defensively snapshot mutable collections`() {
        val fields = linkedMapOf(PortableQueryDataset.TITLE to QueryValue.StringValue("before"))
        val document = PortableQueryDocument("copy", "tenant", "owner", "space", false, fields)
        fields.clear()
        assertEquals(QueryValue.StringValue("before"), document.fields.getValue(PortableQueryDataset.TITLE))
        @Suppress("UNCHECKED_CAST")
        val documentFields = document.fields as MutableMap<LogicalField, QueryValue>
        assertThrows(UnsupportedOperationException::class.java) { documentFields.clear() }

        val expectedIds = mutableListOf("copy")
        val expectation = PortableQueryExpectation(expectedIds)
        expectedIds.clear()
        assertEquals(listOf("copy"), expectation.logicalIds)

        val expectations = linkedMapOf(QueryDocumentKind.SNAPSHOT to expectation)
        val vector = PortableQueryVector(
            id = "copy-vector",
            key = PortableContractKey.Scenario(PortableQueryScenario.PROJECTION),
            kind = PortableVectorKind.BOUNDARY,
            expression = me.ahoo.wow.api.query.expression.MatchAll,
            expectations = expectations
        )
        expectations.clear()
        assertEquals(expectation, vector.expectation(QueryDocumentKind.SNAPSHOT))
        @Suppress("UNCHECKED_CAST")
        val vectorExpectations = vector.expectations as MutableMap<QueryDocumentKind, PortableQueryExpectation>
        assertThrows(UnsupportedOperationException::class.java) { vectorExpectations.clear() }
    }

    @Test
    fun `safe string forms do not expand document values or expressions`() {
        val document = PortableQueryDataset.documents.first()
        val vector = PortableQueryDataset.vectors.first()

        assertFalse(document.toString().contains("Alpha.*"))
        assertFalse(vector.toString().contains(vector.expression.toString()))
        assertFalse(vector.toString().contains("policy"))
        assertTrue(document.toString().contains("fieldCount="))
        assertTrue(vector.toString().contains("expectedResultCounts="))
    }

    @Test
    fun `explicit semantic scenarios remain keyed and auditable`() {
        val keys = PortableQueryDataset.vectors.map(PortableQueryVector::key).toSet()
        PortableQueryScenario.entries.forEach { scenario ->
            assertTrue(PortableContractKey.Scenario(scenario) in keys, scenario.name)
        }
        PortableLifecycleCase.entries.forEach { lifecycle ->
            assertTrue(PortableContractKey.Lifecycle(lifecycle) in keys, lifecycle.name)
        }
        listOf("aggregateId", "tenantId", "ownerId", "spaceId", "deleted").forEach { field ->
            assertTrue(PortableContractKey.SystemField(LogicalField(field)) in keys, field)
        }
    }
}
