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

@file:OptIn(me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class)

package me.ahoo.wow.tck.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendPageConsistency
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendRecordCompleteness
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.BackendTotalRelation
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.RecordQueryBackend
import org.junit.jupiter.api.Test

/** Shared record-result contract for portable MongoDB and Elasticsearch snapshot backends. */
interface PlannedRecordQueryBackendSpec {
    val recordBackend: RecordQueryBackend

    val recordOptions: QueryBackendExecutionOptions

    val expectedRecordIdentities: List<String>

    fun portableCountPlan(): BackendCountQueryPlan

    fun portableStreamPlan(): BackendStreamQueryPlan

    fun portableSecondPagePlan(): BackendPageQueryPlan

    @Test
    fun `portable record count and bounded stream should agree`() {
        recordBackend.count(portableCountPlan(), recordOptions).block().assert()
            .isEqualTo(expectedRecordIdentities.size.toLong())

        val records = recordBackend.stream(portableStreamPlan(), recordOptions).collectList().block()!!
        records.map { record -> record.identity }.assert().containsExactly(*expectedRecordIdentities.toTypedArray())
        records.forEach { record -> record.completeness.assert().isEqualTo(BackendRecordCompleteness.COMPLETE) }
    }

    @Test
    fun `portable record page should preserve exact total and same input consistency`() {
        val page = recordBackend.page(portableSecondPagePlan(), recordOptions).block()!!

        page.total.assert().isEqualTo(expectedRecordIdentities.size.toLong())
        page.totalRelation.assert().isEqualTo(BackendTotalRelation.EXACT)
        page.consistency.assert().isEqualTo(BackendPageConsistency.SAME_INPUT)
        page.records.single().identity.assert().isEqualTo(expectedRecordIdentities[1])
    }
}
