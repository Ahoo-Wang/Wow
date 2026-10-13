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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.FieldPredicate
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.childFilters
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.spec.SystemField
import me.ahoo.wow.query.spec.spec
import java.time.Duration

/**
 * One query as an audit trail sees it: who asked ([caller]; Wow does not own identity), what shape of query on which
 * model version, under which restrictions, and what came back. It never carries a filter value, so no personal data
 * from the query reaches the audit log. 9.x's `maskedFields` returns with masking (6.1c).
 *
 * @property aggregateType the aggregate type queried.
 * @property queryType the gateway operation.
 * @property entry the query's entry, as the caller stated it.
 * @property model the queried model, or `null` when the query was rejected before its schema loaded.
 * @property modelVersion the content hash of the model's schema ([QueryModelSchema.version]).
 * @param fingerprinter computes [fingerprint] on first read.
 * @property scopeFields the fields the caller's scope restricts (`tenantId`, `ownerId`, ...), not their values.
 * @property policies the [QueryPolicy] classes that restricted the query (those that returned anything but match-all).
 * @property rows the records or aggregation rows delivered; a count delivers one.
 * @property outcome how the query ended.
 * @property errorCode the error code of a failed query ([QueryError.errorCode] and, when it states one, the rule code
 * of its first binding error).
 * @property caller who ran the query, for the principal and its attributes.
 * @property elapsed the time from the start of the query to its end.
 */
class QueryAudit internal constructor(
    val aggregateType: AggregateType,
    val queryType: QueryType,
    val entry: QueryEntry,
    val model: QueryModel?,
    val modelVersion: String?,
    fingerprinter: () -> String,
    val scopeFields: List<String>,
    val policies: List<String>,
    val rows: Long,
    val outcome: Outcome,
    val errorCode: String?,
    val caller: QueryCaller,
    val elapsed: Duration,
) {
    enum class Outcome { COMPLETE, ERROR, CANCEL }

    /**
     * A hash of the submitted query's shape ([queryShapeOf]: operators, fields, value counts, sort, projection, groups,
     * metrics and the paging kind and size), with every value, search text, alias and cursor left out, so equal shapes
     * group together. Computed on first read, so an observer that never reads it (a metrics observer, say) does not pay
     * for it.
     */
    val fingerprint: String by lazy(fingerprinter)

    /** Leaves [caller] out: it holds scope values and principal attributes that must not reach a log line. */
    override fun toString(): String =
        "QueryAudit(aggregateType=$aggregateType, queryType=$queryType, entry=$entry, model=$model, " +
            "modelVersion=$modelVersion, fingerprint=$fingerprint, scopeFields=$scopeFields, policies=$policies, " +
            "rows=$rows, outcome=$outcome, errorCode=$errorCode, elapsed=$elapsed)"
}

/** Collects one query's [QueryAudit]. */
internal class QueryAuditTrail(
    private val aggregateType: AggregateType,
    private val queryType: QueryType,
    private val query: Any,
    private val caller: QueryCaller,
) {
    private val startedAt = System.nanoTime()

    @Volatile
    private var schema: QueryModelSchema? = null
    private val policies = mutableListOf<String>()

    @Volatile
    private var rows = 0L

    @Volatile
    private var error: Throwable? = null

    fun schema(schema: QueryModelSchema) {
        this.schema = schema
    }

    fun policy(policy: QueryPolicy) {
        synchronized(policies) { policies += policy::class.java.name }
    }

    fun rows(count: Long) {
        rows += count
    }

    fun error(error: Throwable) {
        this.error = error
    }

    fun audit(outcome: QueryAudit.Outcome): QueryAudit {
        val schema = schema
        return QueryAudit(
            aggregateType = aggregateType,
            queryType = queryType,
            entry = caller.entry,
            model = schema?.model,
            modelVersion = schema?.version,
            fingerprinter = { fingerprintOf(queryType, query) },
            scopeFields = scopeFieldsOf(caller.scope.filter, schema?.profile),
            policies = synchronized(policies) { policies.toList() },
            rows = rows,
            outcome = outcome,
            errorCode = error?.let(::errorCodeOf),
            caller = caller,
            elapsed = Duration.ofNanos(System.nanoTime() - startedAt),
        )
    }
}

/**
 * The fields [scope] restricts: the field of each field predicate and the logical field of each system-field filter,
 * the model's own identity when [profile] names it. An `ELEMENT_MATCH` restricts its collection, not the fields of its
 * element predicate.
 */
private fun scopeFieldsOf(scope: FilterExpression, profile: QueryModelProfile?): List<String> {
    val fields = linkedSetOf<String>()
    fun visit(filter: FilterExpression) {
        if (filter is FieldPredicate) {
            fields += filter.field.path
            return
        }
        filter.spec.systemField?.let { it.scopeField(profile)?.let { field -> fields += field.path } }
        filter.childFilters().forEach(::visit)
    }
    visit(scope)
    return fields.toList()
}

private fun SystemField.scopeField(profile: QueryModelProfile?): QueryField? =
    profile?.systemField(this) ?: takeIf { it != SystemField.IDENTITY }?.let(QueryModelProfile::metadataField)

private fun errorCodeOf(error: Throwable): String {
    val info = error as? QueryError ?: return error.javaClass.simpleName
    val rule = info.bindingErrors.firstOrNull()?.code
    return if (rule == null) info.errorCode else "${info.errorCode}:$rule"
}
