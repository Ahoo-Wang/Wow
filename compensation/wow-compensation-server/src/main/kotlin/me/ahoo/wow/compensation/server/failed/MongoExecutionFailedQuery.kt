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

package me.ahoo.wow.compensation.server.failed

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.reactivestreams.client.MongoClient
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.compensation.CompensationService
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.api.query.ExecutionFailedQuery
import me.ahoo.wow.compensation.api.query.PagedList
import me.ahoo.wow.compensation.api.query.PagedQuery
import me.ahoo.wow.compensation.api.query.Sort
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.compensation.domain.FindNextRetry
import me.ahoo.wow.mongo.toSnapshotState
import me.ahoo.wow.serialization.MessageRecords
import org.bson.conversions.Bson
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

@Primary
@Repository
class MongoExecutionFailedQuery(mongoClient: MongoClient) : FindNextRetry, ExecutionFailedQuery {
    companion object {
        const val DATABASE_NAME = "compensation_db"
        const val COLLECTION_NAME = CompensationService.EXECUTION_FAILED_AGGREGATE_NAME + "_snapshot"

        private const val STATE_FIELD_PREFIX = "state."
        private const val STATUS_FIELD = STATE_FIELD_PREFIX + "status"
        private const val RECOVERABLE_FIELD = STATE_FIELD_PREFIX + "recoverable"
        private const val IS_RETRYABLE_FIELD = STATE_FIELD_PREFIX + "isRetryable"
        private const val IS_BELOW_RETRY_THRESHOLD_FIELD = STATE_FIELD_PREFIX + "isBelowRetryThreshold"
        private const val RETRY_STATE_FIELD = STATE_FIELD_PREFIX + "retryState"
        private const val RETRY_STATE_FIELD_PREFIX = "$RETRY_STATE_FIELD."
        private const val NEXT_RETRY_AT_FIELD = RETRY_STATE_FIELD_PREFIX + "nextRetryAt"
        private const val TIMEOUT_AT_FIELD = RETRY_STATE_FIELD_PREFIX + "timeoutAt"
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

        return Filters.and(
            Filters.ne(RECOVERABLE_FIELD, RecoverableType.UNRECOVERABLE.name),
            Filters.eq(IS_RETRYABLE_FIELD, true),
            Filters.lte(NEXT_RETRY_AT_FIELD, currentTime),
            Filters.or(
                Filters.eq(STATUS_FIELD, ExecutionFailedStatus.FAILED.name),
                Filters.and(
                    Filters.eq(STATUS_FIELD, ExecutionFailedStatus.PREPARED.name),
                    Filters.lte(TIMEOUT_AT_FIELD, currentTime)
                )
            )
        )
    }

    fun toRetryFilter(): Bson {
        val currentTime = System.currentTimeMillis()
        return Filters.and(
            Filters.ne(RECOVERABLE_FIELD, RecoverableType.UNRECOVERABLE.name),
            Filters.eq(IS_RETRYABLE_FIELD, true),
            Filters.or(
                Filters.eq(STATUS_FIELD, ExecutionFailedStatus.FAILED.name),
                Filters.and(
                    Filters.eq(STATUS_FIELD, ExecutionFailedStatus.PREPARED.name),
                    Filters.lte(TIMEOUT_AT_FIELD, currentTime)
                )
            )
        )
    }

    fun executingFilter(): Bson {
        val currentTime = System.currentTimeMillis()
        return Filters.and(
            Filters.eq(STATUS_FIELD, ExecutionFailedStatus.PREPARED.name),
            Filters.gt(TIMEOUT_AT_FIELD, currentTime)
        )
    }

    fun nonRetryableFilter(): Bson {
        return Filters.and(
            Filters.ne(RECOVERABLE_FIELD, RecoverableType.UNRECOVERABLE.name),
            Filters.ne(STATUS_FIELD, ExecutionFailedStatus.SUCCEEDED.name),
            Filters.eq(IS_BELOW_RETRY_THRESHOLD_FIELD, false)
        )
    }

    fun successFilter(): Bson {
        return Filters.eq(STATUS_FIELD, ExecutionFailedStatus.SUCCEEDED.name)
    }

    fun unrecoverableFilter(): Bson {
        return Filters.eq(RECOVERABLE_FIELD, RecoverableType.UNRECOVERABLE.name)
    }

    override fun findAll(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = Filters.empty()
        return findPagedList(filter, pagedQuery)
    }

    override fun findNextRetry(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = nextRetryFilter()
        return findPagedList(filter, pagedQuery)
    }

    override fun findExecuting(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = executingFilter()
        return findPagedList(filter, pagedQuery)
    }

    override fun findToRetry(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = toRetryFilter()
        return findPagedList(filter, pagedQuery)
    }

    override fun findNonRetryable(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = nonRetryableFilter()
        return findPagedList(filter, pagedQuery)
    }

    override fun findSuccess(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = successFilter()
        return findPagedList(filter, pagedQuery)
    }

    override fun findUnrecoverable(pagedQuery: PagedQuery): Mono<PagedList<out IExecutionFailedState>> {
        val filter = unrecoverableFilter()
        return findPagedList(filter, pagedQuery)
    }

    private fun findPagedList(
        filter: Bson,
        pagedQuery: PagedQuery
    ): Mono<PagedList<out IExecutionFailedState>> {
        val sort = pagedQuery.sort.map {
            when (it.order) {
                Sort.Order.ASC -> Sorts.ascending(it.field)
                Sort.Order.DESC -> Sorts.descending(it.field)
            }
        }.toList().let {
            Sorts.orderBy(it)
        }
        val totalPublisher = snapshotCollection.countDocuments(filter).toMono()
        val listPublisher = snapshotCollection.find(filter)
            .sort(sort)
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