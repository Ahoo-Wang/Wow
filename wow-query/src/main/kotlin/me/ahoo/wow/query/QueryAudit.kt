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

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.ProjectionCapable
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.filter.predicateField
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toJsonNode
import reactor.util.context.ContextView
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/**
 * One query as an audit trail sees it: who asked (read the principal from [context]; Wow does not own identity),
 * what shape of query on which model version, under which restrictions, and what came back. It never carries a filter
 * value, so no personal data from the query reaches the audit log.
 *
 * @property namedAggregate the aggregate queried.
 * @property queryType the gateway operation.
 * @property entry the query's entry, as the caller set it.
 * @property model the queried model, or `null` when the query was rejected before its schema loaded.
 * @property modelVersion the content hash of the model's capabilities ([QueryModelSchema.version]).
 * @property fingerprint a hash of the submitted query's shape (operators, fields, sort, projection and sizes), with
 * every value, search text and cursor left out, so equal shapes group together.
 * @property scopeFields the fields the caller's scope restricts (`tenantId`, `ownerId`, ...), not their values.
 * @property policies the [QueryPolicy] classes that restricted the query (those that returned anything but match-all).
 * @property rows the records or aggregation rows delivered; a count delivers one.
 * @property maskedFields the masked fields of the model the response carries; empty for a count or an aggregation.
 * @property outcome how the query ended.
 * @property errorCode the error code of a failed query ([ErrorInfo.errorCode] and, when it states one, the rule code
 * of its first binding error).
 * @property context the subscriber context, for the principal and any other request attribute.
 */
data class QueryAudit(
    val namedAggregate: NamedAggregate,
    val queryType: QueryType,
    val entry: QueryEntry,
    val model: QueryModel?,
    val modelVersion: String?,
    val fingerprint: String,
    val scopeFields: List<String>,
    val policies: List<String>,
    val rows: Long,
    val maskedFields: List<String>,
    val outcome: Outcome,
    val errorCode: String?,
    val context: ContextView,
) {
    enum class Outcome { COMPLETE, ERROR, CANCEL }

    /** Leaves [context] out: it holds scope values and request attributes that must not reach a log line. */
    override fun toString(): String =
        "QueryAudit(namedAggregate=$namedAggregate, queryType=$queryType, entry=$entry, model=$model, " +
            "modelVersion=$modelVersion, fingerprint=$fingerprint, scopeFields=$scopeFields, policies=$policies, " +
            "rows=$rows, maskedFields=$maskedFields, outcome=$outcome, errorCode=$errorCode)"
}

/** Collects one subscription's [QueryAudit]; not thread-safe beyond the serialized signals of one subscription. */
internal class QueryAuditTrail(
    private val namedAggregate: NamedAggregate,
    private val queryType: QueryType,
    private val query: Any,
    private val context: ContextView,
) {
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
            namedAggregate = namedAggregate,
            queryType = queryType,
            entry = context.queryEntry(),
            model = schema?.model,
            modelVersion = schema?.version,
            fingerprint = fingerprintOf(query),
            scopeFields = scopeFieldsOf(context.queryScope()),
            policies = synchronized(policies) { policies.toList() },
            rows = rows,
            maskedFields = schema?.let(::maskedFieldsOf).orEmpty(),
            outcome = outcome,
            errorCode = error?.let(::errorCodeOf),
            context = context,
        )
    }

    private fun maskedFieldsOf(schema: QueryModelSchema): List<String> {
        if (queryType == QueryType.COUNT || queryType == QueryType.AGGREGATION) return emptyList()
        val projection = (query as? ProjectionCapable<*>)?.projection ?: return schema.maskedFields
        fun QueryField.covers(path: String) = path == this.path || path.startsWith("${this.path}.")
        return schema.maskedFields.filter { path ->
            (projection.include.isEmpty() || projection.include.any { it.covers(path) }) &&
                projection.exclude.none { it.covers(path) }
        }
    }
}

/** Keys whose content is a caller-supplied value: redacted from the fingerprint. */
private val VALUE_KEYS = setOf("value", "values", "query", "cursor", "offset", "zoneId", "datePattern", "timeZone")

internal fun fingerprintOf(query: Any): String {
    val shape = redact(query.toJsonNode<JsonNode>())
    val digest = MessageDigest.getInstance("SHA-256").digest(shape.toString().toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun redact(node: JsonNode): JsonNode = when (node) {
    is ObjectNode -> JsonNodeFactory.instance.objectNode().also { redacted ->
        node.propertyNames().sorted().forEach { name ->
            val child = node.get(name)
            redacted.set(name, if (name in VALUE_KEYS) JsonNodeFactory.instance.stringNode("?") else redact(child))
        }
    }
    is ArrayNode -> JsonNodeFactory.instance.arrayNode().also { redacted -> node.forEach { redacted.add(redact(it)) } }
    else -> node
}

internal fun scopeFieldsOf(scope: FilterExpression): List<String> {
    val fields = linkedSetOf<String>()
    fun visit(filter: FilterExpression) {
        when (filter) {
            is AndFilter -> filter.operands.forEach(::visit)
            is OrFilter -> filter.operands.forEach(::visit)
            is NorFilter -> filter.operands.forEach(::visit)
            is TenantIdFilter -> fields += MessageRecords.TENANT_ID
            is OwnerIdFilter -> fields += MessageRecords.OWNER_ID
            is SpaceIdFilter -> fields += MessageRecords.SPACE_ID
            else -> filter.predicateField()?.let { fields += it.path }
        }
    }
    visit(scope)
    return fields.toList()
}

private fun errorCodeOf(error: Throwable): String {
    val info = error as? ErrorInfo ?: return error.javaClass.simpleName
    val rule = info.bindingErrors.firstNotNullOfOrNull { it.code }
    return if (rule == null) info.errorCode else "${info.errorCode}:$rule"
}
