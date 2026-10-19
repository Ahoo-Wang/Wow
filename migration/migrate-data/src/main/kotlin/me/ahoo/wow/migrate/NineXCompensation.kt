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

package me.ahoo.wow.migrate

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import org.bson.Document
import org.bson.conversions.Bson
import tools.jackson.databind.JsonNode

/** A 9.x processor: its bounded context (`contextName`) and its name (`processorName`). */
data class NineXProcessor(val context: String, val processor: String) {
    override fun toString(): String = "$context.$processor"
}

/** A 9.x processor function, as a 9.x `ExecutionFailed` records it (`FunctionInfoData`). */
data class NineXFunction(val context: String, val processor: String, val name: String) {
    val owner: NineXProcessor get() = NineXProcessor(context, processor)

    override fun toString(): String = "$context.$processor.$name"
}

/**
 * One 9.x `ExecutionFailed` of the 9.x compensation service, read from its snapshot by its storage format (decision
 * D85: no 9.x module). 9.x's `MongoSnapshotStore` keeps one document per execution in `execution_failed_snapshot` of
 * the compensation service's database, the `ExecutionFailedState` in its `state`:
 *
 * ```json
 * { "_id": "0VYC…", "contextName": "compensation-service", "aggregateName": "execution_failed", "version": 5,
 *   "deleted": false, "state": {
 *     "id": "0VYC…", "status": "FAILED", "recoverable": "RECOVERABLE", "executeAt": 1792004371000,
 *     "eventId": { "id": "…", "aggregateId": { "contextName": "example-service", "aggregateName": "order",
 *                  "aggregateId": "0VY3…", "tenantId": "(0)" }, "version": 1 },
 *     "function": { "functionKind": "EVENT", "contextName": "example-service", "processorName": "OrderSaga",
 *                   "name": "onEvent" },
 *     "error": { "errorCode": "Conflict", "errorMsg": "…", "stackTrace": "…", "succeeded": false },
 *     "retrySpec": { "maxRetries": 5, "minBackoff": 60, "executionTimeout": 30 },
 *     "retryState": { "retries": 1, "retryAt": …, "timeoutAt": …, "nextRetryAt": … },
 *     "isBelowRetryThreshold": true, "isRetryable": true } }
 * ```
 *
 * [function] failed on the event of [aggregate]'s instance [aggregateId] at [version]. [executeAt] is when it last
 * ran; [retries] the automatic retries 9.x made, the last one started at [retryAt] (the failure itself before the
 * first); [maxRetries], [minBackoff] and [executionTimeout] (seconds) its retry spec; [recoverable] the 9.x
 * `RecoverableType`. The stack trace is not read (decision D215).
 */
data class NineXExecutionFailed(
    val id: String,
    val function: NineXFunction,
    val functionKind: String,
    val aggregate: NineXAggregate,
    val aggregateId: NineXAggregateId,
    val version: Int,
    val errorCode: String,
    val errorMessage: String,
    val executeAt: Long,
    val retries: Int,
    val retryAt: Long,
    val maxRetries: Int,
    val minBackoff: Int,
    val executionTimeout: Int,
    val recoverable: String,
    val status: String,
    val deleted: Boolean = false,
) {
    /**
     * Whether the execution still waits: it neither succeeded (status `SUCCEEDED`) nor was skipped (its 9.x
     * aggregate deleted). 9.x settles one in no other way; `FAILED` and `PREPARED` (a retry in flight) both wait.
     */
    val unresolved: Boolean get() = !deleted && status != SUCCEEDED

    override fun toString(): String = "$id ($function on $aggregate/$aggregateId v$version)"

    companion object {
        /** The 9.x `ExecutionFailedStatus` of an execution that succeeded on a retry. */
        const val SUCCEEDED: String = "SUCCEEDED"
        private const val UNKNOWN = "UNKNOWN"

        /**
         * Reads an execution from its snapshot [node] (a document of `execution_failed_snapshot`, `_id` its id); a
         * field it needs and lacks fails with a [MigrationException] naming it.
         */
        fun parse(node: JsonNode): NineXExecutionFailed {
            val id = node.text("_id") ?: node.text("aggregateId")
                ?: throw MigrationException("A 9.x execution has no _id.")
            val state = node.at(id, "state")
            fun text(path: String): String = state.at(id, path).takeIf { it.isString }?.stringValue()
                ?: throw MigrationException("9.x execution $id has no text at $path.")
            fun number(path: String): Long = state.at(id, path).takeIf { it.isIntegralNumber }?.asLong()
                ?: throw MigrationException("9.x execution $id has no number at $path.")
            return NineXExecutionFailed(
                id = id,
                function = NineXFunction(
                    text("function.contextName"),
                    text("function.processorName"),
                    text("function.name")
                ),
                functionKind = text("function.functionKind"),
                aggregate = NineXAggregate(
                    text("eventId.aggregateId.contextName"),
                    text("eventId.aggregateId.aggregateName")
                ),
                aggregateId = NineXAggregateId(
                    text("eventId.aggregateId.aggregateId"),
                    text("eventId.aggregateId.tenantId")
                ),
                version = number("eventId.version").toInt(),
                errorCode = text("error.errorCode"),
                errorMessage = state.get("error")?.text("errorMsg").orEmpty(),
                executeAt = number("executeAt"),
                retries = number("retryState.retries").toInt(),
                retryAt = number("retryState.retryAt"),
                maxRetries = number("retrySpec.maxRetries").toInt(),
                minBackoff = number("retrySpec.minBackoff").toInt(),
                executionTimeout = number("retrySpec.executionTimeout").toInt(),
                recoverable = state.text("recoverable") ?: UNKNOWN,
                status = text("status"),
                deleted = node.get("deleted")?.asBoolean() ?: false,
            )
        }

        /** The value at the dotted [path] in this node; a [MigrationException] naming it when there is none. */
        private fun JsonNode.at(id: String, path: String): JsonNode =
            path.split('.').fold<String, JsonNode?>(this) { node, name -> node?.get(name) }?.takeIf { !it.isNull }
                ?: throw MigrationException("9.x execution $id has no $path.")

        private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.stringValue()
    }
}

/** How many unresolved 9.x executions of [function] failed on events of [aggregate]. */
data class NineXFailureGroup(val function: NineXFunction, val aggregate: NineXAggregate, val executions: Long)

/**
 * What the 9.x compensation service holds, for [MappingCheck]: its unresolved executions by function and aggregate
 * ([unresolved]), and how many are [resolved] (succeeded or skipped), which are history and not imported.
 */
data class NineXCompensationInventory(val unresolved: List<NineXFailureGroup>, val resolved: Long)

/**
 * The 9.x compensation service's failed executions (decision D229 G-1). The MongoDB store is the only 9.x source
 * (decision D213): [MongoNineXCompensationSource]. The calls block.
 */
interface NineXCompensationSource {
    fun inventory(): NineXCompensationInventory

    /** At most [limit] unresolved executions after the id [after] (from the first when null), in id order. */
    fun unresolved(after: String?, limit: Int): List<NineXExecutionFailed>

    /** The execution [id] whatever its status, resolved or not; null when the store has none of that id. */
    fun execution(id: String): NineXExecutionFailed?
}

/**
 * The executions of the 9.x compensation service's MongoDB [database] (its own database, `compensation_db` in its
 * distribution, not the application's): the snapshots of its `execution_failed` aggregate, which the 9.x service
 * keeps at every version and reads itself to find the executions due for a retry. The tool only reads it.
 */
class MongoNineXCompensationSource(private val database: MongoDatabase) : NineXCompensationSource {
    private val collection get() = database.getCollection(COLLECTION)

    override fun inventory(): NineXCompensationInventory {
        val key = Document()
            .append("context", "$$STATE.function.contextName")
            .append("processor", "$$STATE.function.processorName")
            .append("name", "$$STATE.function.name")
            .append("aggregateContext", "$$STATE.eventId.aggregateId.contextName")
            .append("aggregate", "$$STATE.eventId.aggregateId.aggregateName")
        val groups = collection
            .aggregate(listOf(Aggregates.match(UNRESOLVED), Aggregates.group(key, Accumulators.sum(COUNT, 1))))
            .map { group ->
                val id = group.get(ID, Document::class.java)
                NineXFailureGroup(
                    NineXFunction(id.getString("context"), id.getString("processor"), id.getString("name")),
                    NineXAggregate(id.getString("aggregateContext"), id.getString("aggregate")),
                    (group.get(COUNT) as Number).toLong(),
                )
            }
            .toList()
            .sortedWith(compareBy({ it.function.toString() }, { it.aggregate.toString() }))
        val resolved = collection.countDocuments(Filters.and(COMPENSATION, Filters.nor(UNRESOLVED)))
        return NineXCompensationInventory(groups, resolved)
    }

    override fun unresolved(after: String?, limit: Int): List<NineXExecutionFailed> =
        collection.find(Filters.and(listOfNotNull(UNRESOLVED, after?.let { Filters.gt(ID, it) })))
            .sort(Sorts.ascending(ID))
            .limit(limit)
            .map { NineXExecutionFailed.parse(MongoNineXEventSource.toNode(it)) }
            .toList()

    override fun execution(id: String): NineXExecutionFailed? =
        collection.find(Filters.and(COMPENSATION, Filters.eq(ID, id))).first()
            ?.let { NineXExecutionFailed.parse(MongoNineXEventSource.toNode(it)) }

    companion object {
        /** The 9.x compensation service's snapshot collection of its `execution_failed` aggregate. */
        const val COLLECTION: String = "execution_failed_snapshot"

        /** The 9.x compensation service's bounded context. */
        const val CONTEXT: String = "compensation-service"
        private const val ID = "_id"
        private const val STATE = "state"
        private const val COUNT = "count"
        private val COMPENSATION: Bson = Filters.eq("contextName", CONTEXT)
        private val UNRESOLVED: Bson = Filters.and(
            COMPENSATION,
            Filters.ne("deleted", true),
            Filters.ne("$STATE.status", NineXExecutionFailed.SUCCEEDED),
        )
    }
}
