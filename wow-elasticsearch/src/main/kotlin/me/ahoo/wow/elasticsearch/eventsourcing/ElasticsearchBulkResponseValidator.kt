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

package me.ahoo.wow.elasticsearch.eventsourcing

import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem
import co.elastic.clients.elasticsearch.core.bulk.OperationType

internal data class ElasticsearchBulkItemExpectation(
    val operationType: OperationType,
    val indexExpression: String,
    val id: String,
)

/**
 * Validates the order-preserving parts of a Bulk response.
 *
 * Elasticsearch returns the concrete/backing index in `_index` when the request
 * targets an alias or data stream. Consequently, the response index is useful
 * for diagnostics but cannot be compared to the original index expression.
 */
internal fun validateElasticsearchBulkResponse(
    expectedItems: List<ElasticsearchBulkItemExpectation>,
    responseItems: List<BulkResponseItem>,
    responseErrors: Boolean,
) {
    val mismatchMessage = when {
        responseItems.size != expectedItems.size ->
            "Elasticsearch bulk response item count[${responseItems.size}] " +
                "does not match request item count[${expectedItems.size}]."

        else -> {
            val mismatchedItem = expectedItems.zip(responseItems)
                .withIndex()
                .firstOrNull { (_, pair) ->
                    val (expected, item) = pair
                    item.operationType() != expected.operationType ||
                        item.id() != expected.id
                }
            when {
                mismatchedItem != null -> {
                    val (index, pair) = mismatchedItem
                    val (expected, item) = pair
                    "Elasticsearch bulk response item[$index] does not match its request: " +
                        "expected[${expected.operationType} " +
                        "${expected.indexExpression}/${expected.id}], " +
                        "actual[${item.operationType()} ${item.index()}/${item.id()}]."
                }

                responseErrors != responseItems.any { !it.isSuccessfulResponse() } ->
                    "Elasticsearch bulk response errors[$responseErrors] is inconsistent " +
                        "with its item failures."

                else -> null
            }
        }
    }
    if (mismatchMessage != null) {
        throw ElasticsearchBulkResponseException(mismatchMessage)
    }
}

internal fun BulkResponseItem.isSuccessfulResponse(): Boolean {
    return status() in 200..299 && error() == null
}
