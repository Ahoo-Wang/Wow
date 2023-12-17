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

package me.ahoo.wow.compensation.server

import com.mongodb.client.model.Sorts
import com.mongodb.reactivestreams.client.MongoClient
import me.ahoo.wow.compensation.CompensationService
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.compensation.domain.ToRetryQuery
import me.ahoo.wow.mongo.toSnapshotState
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toFlux

@Repository
class MongoToRetryQuery(mongoClient: MongoClient) : ToRetryQuery {
    companion object {
        const val DATABASE_NAME = "compensation_db"
        const val COLLECTION_NAME = CompensationService.EXECUTION_FAILED_AGGREGATE_NAME + "_snapshot"
    }

    private val snapshotCollection = mongoClient.getDatabase(DATABASE_NAME)
        .getCollection(COLLECTION_NAME)

    override fun findToRetry(limit: Int): Flux<out IExecutionFailedState> {
        val currentTime = System.currentTimeMillis()
        val pipelineShell = """
            {
              ${'$'}and: [{
                  "state.retryState.isRetryable": true
                },
                {
                  "state.status": {
                    ${'$'}ne: "${ExecutionFailedStatus.SUCCEEDED}"
                  }
                },
                {
                  ${'$'}or: [{
                      "state.status": "${ExecutionFailedStatus.FAILED}"
                    },
                    {
                      ${'$'}and: [{
                        "state.status": "${ExecutionFailedStatus.PREPARED}"
                      }, {
                        "state.retryState.timoutAt": {
                          ${'$'}lte: $currentTime
                        }
                      }, {
                        "state.retryState.nextRetryAt": {
                          ${'$'}lte: $currentTime
                        }
                      }]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val pipeline = Document.parse(pipelineShell)
        return snapshotCollection.find(pipeline)
            .limit(limit)
            .sort(Sorts.ascending(MessageRecords.VERSION))
            .toFlux()
            .toSnapshotState<ExecutionFailedState>()
    }
}