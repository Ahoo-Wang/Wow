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

package me.ahoo.wow.query

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.query.filter.QueryType
import java.util.concurrent.ConcurrentHashMap

/**
 * Publishes one [QueryAudit] per query as meters (§10 可观测):
 *
 * - `wow.query` (timer): tags `context`, `aggregate`, `model`, `type`, `entry`, `outcome` (`success`, `error`,
 *   `cancelled`) and `code`. `code` is the rejected rule, e.g. `QuerySchemaValidation:UNKNOWN_FIELD`, or `none`, so
 *   counting by `code` classifies admission rejections by the rule they broke.
 * - `wow.query.rows` (summary): the records or rows delivered, tags `context`, `aggregate`, `model`, `type`.
 *
 * No tag carries a filter value, a scope value or a fingerprint, so cardinality stays bounded.
 */
class QueryMetricsObserver(private val registry: MeterRegistry) : QueryObserver {
    override val audits: Boolean
        get() = true

    /** The meters of one tag set, built and registered once: tags are bounded, so the maps are too. */
    private data class MeterKey(
        val contextName: String,
        val aggregateName: String,
        val model: String?,
        val queryType: QueryType,
        val entry: QueryEntry? = null,
        val outcome: QueryAudit.Outcome? = null,
        val code: String? = null,
    )

    private val timers = ConcurrentHashMap<MeterKey, Timer>()
    private val summaries = ConcurrentHashMap<MeterKey, DistributionSummary>()

    override fun onAudit(audit: QueryAudit) {
        val named = audit.namedAggregate
        val base = MeterKey(named.contextName, named.aggregateName, audit.model?.value, audit.queryType)
        timers.computeIfAbsent(base.copy(entry = audit.entry, outcome = audit.outcome, code = audit.errorCode)) {
            Timer.builder(QUERY)
                .tags(
                    baseTags(named, it.model, it.queryType).and(ENTRY_TAG, audit.entry.name.lowercase())
                        .and(OUTCOME_TAG, audit.outcome.metricValue)
                        .and(CODE_TAG, audit.errorCode ?: NONE)
                )
                .register(registry)
        }.record(audit.elapsed)
        if (audit.outcome == QueryAudit.Outcome.COMPLETE) {
            summaries.computeIfAbsent(base) {
                DistributionSummary.builder(ROWS).tags(baseTags(named, it.model, it.queryType)).register(registry)
            }.record(audit.rows.toDouble())
        }
    }

    private val QueryAudit.Outcome.metricValue: String
        get() = when (this) {
            QueryAudit.Outcome.COMPLETE -> "success"
            QueryAudit.Outcome.ERROR -> "error"
            QueryAudit.Outcome.CANCEL -> "cancelled"
        }

    companion object {
        const val QUERY = "wow.query"
        const val ROWS = "wow.query.rows"
        const val ENTRY_TAG = "entry"
        const val OUTCOME_TAG = "outcome"
        const val CODE_TAG = "code"
        private const val NONE = "none"

        internal fun baseTags(namedAggregate: NamedAggregate, model: String?, type: QueryType? = null): Tags {
            val tags = Tags.of("context", namedAggregate.contextName)
                .and("aggregate", namedAggregate.aggregateName)
                .and("model", model?.lowercase() ?: NONE)
            return if (type == null) tags else tags.and("type", type.name.lowercase())
        }
    }
}

/**
 * Several observers behind one: each callback reaches every delegate, a failing delegate is logged and does not stop
 * the others, and audits are built when any delegate wants them.
 */
class CompositeQueryObserver(private val delegates: List<QueryObserver>) : QueryObserver {
    override val audits: Boolean = delegates.any { it.audits }

    override fun onComplete(namedAggregate: NamedAggregate, queryType: QueryType) =
        each { onComplete(namedAggregate, queryType) }

    override fun onError(namedAggregate: NamedAggregate, queryType: QueryType, error: Throwable) =
        each { onError(namedAggregate, queryType, error) }

    override fun onCancel(namedAggregate: NamedAggregate, queryType: QueryType) =
        each { onCancel(namedAggregate, queryType) }

    override fun onAudit(audit: QueryAudit) = delegates.filter { it.audits }.forEachIsolated { onAudit(audit) }

    private inline fun each(crossinline callback: QueryObserver.() -> Unit) = delegates.forEachIsolated(callback)

    @Suppress("TooGenericExceptionCaught")
    private inline fun List<QueryObserver>.forEachIsolated(crossinline callback: QueryObserver.() -> Unit) =
        forEach { delegate ->
            try {
                delegate.callback()
            } catch (failure: Exception) {
                log.error(failure) { "Query observer [${delegate.javaClass.name}] failed." }
            }
        }

    private companion object {
        val log = io.github.oshai.kotlinlogging.KotlinLogging.logger { }
    }
}
