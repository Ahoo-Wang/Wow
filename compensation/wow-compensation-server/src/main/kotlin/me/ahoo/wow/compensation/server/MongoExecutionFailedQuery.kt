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

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.reactivestreams.client.MongoClient
import me.ahoo.wow.compensation.CompensationService
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.api.query.PagedList
import me.ahoo.wow.compensation.api.query.PagedQuery
import me.ahoo.wow.compensation.api.query.RetryQuery
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.compensation.domain.FindNextRetry
import me.ahoo.wow.mongo.toSnapshotState
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.bson.conversions.Bson
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

@Primary
@Repository
class MongoExecutionFailedQuery(mongoClient: MongoClient) : FindNextRetry, RetryQuery {
    companion object {
        const val DATABASE_NAME = "compensation_db"
        const val COLLECTION_NAME = CompensationService.EXECUTION_FAILED_AGGREGATE_NAME + "_snapshot"
    }

    private val snapshotCollection = mongoClient.getDatabase(DATABASE_NAME)
        .getCollection(COLLECTION_NAME)

    override fun findNextRetry(limit: Int): Flux<out IExecutionFailedState> {
        val filter = nextRetryFilter()
        return snapshotCollection.find(filter)
            .limit(limit)
            .sort(Sorts.ascending(MessageRecords.VERSION))
            .toFlux()
            .toSnapshotState<ExecutionFailedState>()
    }

    fun nextRetryFilter(): Bson {
        val currentTime = System.currentTimeMillis()
        val pipelineShell = """
            {
              ${'$'}and: [{
                  "state.isRetryable": true
                },
                {
                  "state.status": {
                    ${'$'}ne: "${ExecutionFailedStatus.SUCCEEDED}"
                  }
                },
                 {
                   "state.retryState.nextRetryAt": {
                      ${'$'}lte: $currentTime
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
                      }]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        return Document.parse(pipelineShell);
    }

    fun toRetryFilter(): Bson {
        val currentTime = System.currentTimeMillis()
        val pipelineShell = """
            {
              ${'$'}and: [{
                  "state.isRetryable": true
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
                      }]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        return Document.parse(pipelineShell);
    }

    fun completedFailuresFilter(): Bson {
        return Filters.and(
            Filters.ne("state.status", ExecutionFailedStatus.SUCCEEDED.name),
            Filters.eq("state.isRetryable", false)
        )
    }

    fun successFilter(): Bson {
        return Filters.eq("state.status", ExecutionFailedStatus.SUCCEEDED.name)
    }

    override fun findAll(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = Filters.empty()
        return findPagedList(filter, pagedQuery)
    }

    override fun findNextRetry(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = nextRetryFilter()
        return findPagedList(filter, pagedQuery)
    }

    override fun findToRetry(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = toRetryFilter()
        return findPagedList(filter, pagedQuery)
    }

    override fun findCompletedFailures(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = completedFailuresFilter()
        return findPagedList(filter, pagedQuery)
    }

    override fun findSuccess(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = successFilter()
        return findPagedList(filter, pagedQuery)
    }

    private fun findPagedList(
        filter: Bson,
        pagedQuery: PagedQuery
    ): Mono<PagedList<out IExecutionFailedState>> {
        val totalPublisher = snapshotCollection.countDocuments(filter).toMono()
        val listPublisher = snapshotCollection.find(filter)
            .skip(pagedQuery.offset())
            .limit(pagedQuery.pageSize)
            .toFlux()
            .toSnapshotState<ExecutionFailedState>()
            .collectList()
        return Mono.zip(totalPublisher, listPublisher)
            .map { result ->
                PagedList(result.t1, result.t2)
            }
    }
}