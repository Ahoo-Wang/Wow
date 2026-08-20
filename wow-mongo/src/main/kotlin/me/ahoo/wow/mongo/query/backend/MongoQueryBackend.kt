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

@file:JvmSynthetic

package me.ahoo.wow.mongo.query.backend

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Facet
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicLong

internal class MongoQueryBackend(
    private val database: MongoDatabase,
    private val collectionName: String,
    binding: MongoQueryFieldBinding,
    nativeTemplates: MongoNativeQueryTemplateRegistry,
    private val publisherObserver: MongoQueryPublisherObserver,
    override val descriptor: QueryBackendDescriptor,
    readinessRequirements: MongoQueryReadinessRequirements
) : QueryBackend {
    private val compiler = MongoQueryPlanCompiler(binding, nativeTemplates)
    private val decoder = MongoQueryResultDecoder(binding)
    private val readiness = MongoQueryReadiness(database, collectionName, readinessRequirements)

    override fun readiness(): Mono<QueryBackendReadiness> = readiness.inspect()

    override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = Mono.defer {
        val query = configureFind(plan).limit(1).batchSize(1)
        Flux.from(publisherObserver.observe(query))
            .next()
            .map { document -> decode(document, plan) }
    }

    override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.defer {
        var query = configureFind(plan).batchSize(batchSize(plan))
        val budgetLimit = plan.effectiveBudget.maxResults
        if (plan.limit > 0 && budgetLimit != null && plan.limit.toLong() > budgetLimit) {
            return@defer Flux.error(budgetExceeded())
        }
        val driverLimit = when {
            plan.limit > 0 -> plan.limit
            budgetLimit != null && budgetLimit < Int.MAX_VALUE -> (budgetLimit + 1).toInt()
            else -> null
        }
        driverLimit?.let { query = query.limit(it) }
        val source = Flux.from(publisherObserver.observe(query))
        val guarded = if (plan.limit == 0 && budgetLimit != null) {
            val delivered = AtomicLong()
            source.handle<Document> { document, sink ->
                if (delivered.getAndIncrement() >= budgetLimit) {
                    sink.error(budgetExceeded())
                } else {
                    sink.next(document)
                }
            }
        } else {
            source
        }
        guarded.map { document -> decode(document, plan) }
    }

    override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> = Mono.defer {
        val offset = try {
            Math.multiplyExact((plan.page.index - 1).toLong(), plan.page.size.toLong())
        } catch (_: ArithmeticException) {
            throw budgetExceeded()
        }
        if (offset > Int.MAX_VALUE) {
            throw budgetExceeded()
        }
        val itemStages = ArrayList<Bson>()
        itemStages += Aggregates.skip(offset.toInt())
        itemStages += Aggregates.limit(plan.page.size)
        compiler.projection(plan)?.let { itemStages += Aggregates.project(it) }
        val pipeline = ArrayList<Bson>()
        pipeline += Aggregates.match(compiler.filter(plan.securedExpression))
        compiler.sort(plan)?.let { pipeline += Aggregates.sort(it) }
        pipeline += Aggregates.facet(
            Facet(ITEMS_FACET, itemStages),
            Facet(TOTAL_FACET, listOf(Aggregates.count(COUNT_FIELD)))
        )
        val aggregate = collection().aggregate(pipeline).batchSize(1)
        Mono.from(publisherObserver.observe(aggregate)).map { result -> decodePage(result, plan) }
    }

    override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.defer {
        Mono.from(publisherObserver.observe(collection().countDocuments(compiler.filter(plan.securedExpression))))
    }

    private fun collection() = database.getCollection(collectionName)

    private fun configureFind(plan: me.ahoo.wow.query.plan.QueryPlanV1): com.mongodb.reactivestreams.client.FindPublisher<Document> {
        var query = collection().find(compiler.filter(plan.securedExpression))
        compiler.sort(plan)?.let { query = query.sort(it) }
        compiler.projection(plan)?.let { query = query.projection(it) }
        return query
    }

    private fun batchSize(plan: ListQueryPlanV1<*>): Int {
        val configured = plan.effectiveBudget.maxResults?.coerceAtMost(DEFAULT_BATCH_SIZE.toLong())?.toInt()
        return (configured ?: DEFAULT_BATCH_SIZE).coerceAtLeast(1)
    }

    private fun budgetExceeded(): QueryException = QueryException(
        QueryErrorCode.BUDGET_EXCEEDED,
        QueryStage.EXECUTION,
        QueryErrorReason.BUDGET_LIMIT_REACHED
    )

    private fun <R : Any> decode(document: Document, plan: me.ahoo.wow.query.plan.QueryPlanV1): R =
        decoder.decode(document, plan.authorizedResultShape, compiler.resultProjection(plan))

    private fun <R : Any> decodePage(document: Document, plan: PageQueryPlanV1<R>): QueryPage<R> {
        val items = document.getList(ITEMS_FACET, Document::class.java).map { item -> decode<R>(item, plan) }
        val totalDocuments = document.getList(TOTAL_FACET, Document::class.java)
        val total = totalDocuments.firstOrNull()?.get(COUNT_FIELD)?.let { (it as Number).toLong() } ?: 0L
        return QueryPage(items, total, QueryConsistency.EXACT)
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE: Int = 256
        const val ITEMS_FACET: String = "items"
        const val TOTAL_FACET: String = "total"
        const val COUNT_FIELD: String = "value"
    }
}
