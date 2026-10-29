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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.json.StateJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns events into what the log stores, and back. Events are named by the model, never by class name, so renaming
 * or moving a class does not orphan its history. States go into and come out of snapshots the same way.
 *
 * The default mapper is Wow's state mapper ([StateJson.mapper], of [me.ahoo.wow.spi.json.WowJson]'s configuration),
 * which keeps every JSON number exact: an untyped value reads a decimal as a [java.math.BigDecimal]. It also fills a mutable state's read-only collections in place, and reads
 * sets and maps back in the order they were written.
 */
@InternalWowApi
class EventCodec(private val mapper: JsonMapper = DEFAULT_MAPPER) {
    private val stateFingerprints = ConcurrentHashMap<Class<*>, String>()

    /** The sizes of the states this codec writes and reads, which a state cache weighs them by. */
    val sizes: StateSizes = StateSizes()

    /** [event] as a record of [model]. */
    fun encode(model: AggregateModel<*>, event: Any): EventRecord {
        val type = model.eventType(event.javaClass)
        return EventRecord(type.name, type.revision, mapper.writeValueAsString(event))
    }

    /**
     * The event [record] holds, read as [model]'s current schema of its type: a record of an older revision goes
     * through the type's upgrades one revision at a time, as a tree that keeps its numbers exact, and is then
     * read as a record of the current revision is; a record named by a former name is still found. A record of a
     * retired event fails, as one of a name the model does not know: skip it ([decodeUnlessRetired]).
     */
    fun decode(model: AggregateModel<*>, record: EventRecord): Any {
        val type = model.eventType(record.name)
        check(record.revision <= type.revision) {
            "${record.name} revision ${record.revision} is newer than revision ${type.revision} this model reads."
        }
        if (record.revision == type.revision) {
            return mapper.readValue(record.payload, type.type)
        }
        // A tree of the Wow mapper keeps every number as written (WowJson): `2.50` reaches the event as `2.50`.
        var payload = mapper.readTree(record.payload)
        for (revision in record.revision until type.revision) {
            payload = type.upgrades.getValue(revision).upgrade(payload)
        }
        // Bound from its text, as a record of the current revision is, so the upgrade path reads every value the same
        // way, whatever nodes an upgrade put in the tree.
        return mapper.readValue(mapper.writeValueAsString(payload), type.type)
    }

    /**
     * [record] as [model] reads it now: under its type's current name, at its current revision, its
     * payload upgraded as [decode] upgrades it, but left a JSON text rather than bound to the class; null when [model]
     * retired its name. A record of a name [model] does not know, or of a revision newer than its
     * own, is left as stored.
     */
    fun current(model: AggregateModel<*>, record: EventRecord): EventRecord? {
        if (model.isRetired(record.name)) {
            return null
        }
        val type = model.findEventType(record.name) ?: return record
        if (record.revision > type.revision || record.revision == type.revision && record.name == type.name) {
            return record
        }
        var payload = mapper.readTree(record.payload)
        for (revision in record.revision until type.revision) {
            payload = type.upgrades.getValue(revision).upgrade(payload)
        }
        return EventRecord(type.name, type.revision, mapper.writeValueAsString(payload))
    }

    /**
     * The event [record] holds, as [decode] reads it, or null when [model] retired its name
     * ([AggregateModel.isRetired]): a retired record is skipped, never decoded.
     */
    fun decodeUnlessRetired(model: AggregateModel<*>, record: EventRecord): Any? =
        if (model.isRetired(record.name)) null else decode(model, record)

    /** The events [records] hold, in order, as [decode] reads them, without those [model] retired. */
    fun decodeEvents(model: AggregateModel<*>, records: List<EventRecord>): List<Any> =
        records.mapNotNull { decodeUnlessRetired(model, it) }

    /** [command] as JSON: the body of a scheduled command, read back by its type when it is due. */
    fun commandText(command: Any): String = mapper.writeValueAsString(command)

    /** [state] as text, to tell whether something changed it, and as a snapshot holds it; its length is measured. */
    fun stateText(state: Any): String {
        val text = mapper.writeValueAsString(state)
        sizes.record(state.javaClass, text.length)
        return text
    }

    /** [state] as a tree of the JSON [stateText] writes: a copy, keeping every number exact. */
    fun stateTree(state: Any): JsonNode = mapper.valueToTree(state)

    /**
     * [tree] read as a [type]: a state or an event another context published, into the subscriber's
     * own class, with no model of the publisher's aggregate to check it against.
     */
    fun <T : Any> decodeTree(tree: JsonNode, type: Class<T>): T = mapper.treeToValue(tree, type)

    /**
     * The state [text] of a snapshot, read as [model]'s state class: a Jackson exception when it does not parse, an
     * [IllegalArgumentException] when the state read does not write back as the same JSON. A state that silently
     * dropped what it could not take back (a read-only property Jackson does not fill) would decide on lost data;
     * a computed property writes back the same and passes.
     *
     * The same JSON is the same value, not the same text ([sameJson]): a backend may give back the state it stores
     * normalised, PostgreSQL JSONB with its keys reordered and `1.0E10` as `10000000000`, and such a snapshot still
     * loads. A state whose value does not survive the round trip, a number its property cannot hold or a property it
     * cannot take back, is still refused.
     */
    fun <S : Any> decodeState(model: AggregateModel<S>, text: String): S {
        val state = mapper.readValue(text, model.stateType)
        require(sameJson(mapper.readTree(stateText(state)), mapper.readTree(text))) {
            "A ${model.stateType.name} read from this text does not hold all of it."
        }
        return state
    }

    /**
     * The snapshot of [state], which folds [ref]'s event streams up to [version]; [scope] is the aggregate's, the
     * scope of the last of them, and [firstEventTime] and [lastEventTime] are the commit times of the
     * first and the last. [deleted], [tags] and [schedules] are the aggregate's after them, evolved by the lifecycle
     * and schedule events; a state the kernel loaded holds them ([CachedState]). [operator] and [firstOperator] are the
     * operators of the last and the first of them.
     */
    @Suppress("LongParameterList")
    fun <S : Any> snapshotOf(
        model: AggregateModel<S>,
        ref: AggregateRef,
        state: S,
        version: Int,
        scope: Scope,
        firstEventTime: Long,
        lastEventTime: Long,
        deleted: Boolean = false,
        tags: Map<String, List<String>> = emptyMap(),
        operator: Operator? = null,
        firstOperator: Operator? = null,
        schedules: Map<String, ScheduleRule> = emptyMap(),
    ): SnapshotRecord = snapshotOf(
        model,
        ref,
        stateText(state),
        version,
        scope,
        firstEventTime,
        lastEventTime,
        deleted,
        tags,
        operator,
        firstOperator,
        schedules,
    )

    /**
     * The snapshot of a state this codec already serialized as [stateText]: the published state taken
     * when the stream at [version] committed, so it is not serialized again. The rest is as for the
     * snapshot of a state object.
     */
    @Suppress("LongParameterList")
    fun snapshotOf(
        model: AggregateModel<*>,
        ref: AggregateRef,
        stateText: String,
        version: Int,
        scope: Scope,
        firstEventTime: Long,
        lastEventTime: Long,
        deleted: Boolean = false,
        tags: Map<String, List<String>> = emptyMap(),
        operator: Operator? = null,
        firstOperator: Operator? = null,
        schedules: Map<String, ScheduleRule> = emptyMap(),
    ): SnapshotRecord {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        return SnapshotRecord(
            ref = ref,
            version = version,
            schemaFingerprint = stateSchemaFingerprint(model),
            stateRevision = model.stateRevision,
            state = stateText,
            scope = scope,
            deleted = deleted,
            firstEventTime = firstEventTime,
            lastEventTime = lastEventTime,
            tags = tags,
            operator = operator,
            firstOperator = firstOperator,
            schedules = schedules,
        )
    }

    /**
     * Identifies the schema of [model]'s state as this codec's mapper serializes it ([StateJson.fingerprint]): a
     * snapshot written under another fingerprint is not read back; the state is replayed from the log instead
     * (03-log-and-snapshot.md §3). Computed once per state class.
     */
    fun stateSchemaFingerprint(model: AggregateModel<*>): String =
        stateFingerprints.computeIfAbsent(model.stateType) { StateJson.fingerprint(it, mapper) }

    /** The description [stateSchemaFingerprint] hashes. */
    internal fun stateShape(stateType: Class<*>): String = StateJson.shape(stateType, mapper)

    /**
     * Whether [left] and [right] hold the same JSON value: objects by field whatever their key order, arrays element
     * by element, numbers by numeric value whatever their form or scale, so `1.0E10` is `10000000000` and `2.50` is
     * `2.5`. A backend may normalise how a number is written (PostgreSQL JSONB writes no exponent, MongoDB may hold it
     * as a Decimal128) but not its value, and a state only needs its value back. Everything else compares as
     * Jackson's nodes do.
     */
    internal fun sameJson(left: JsonNode, right: JsonNode): Boolean = when {
        left.isNumber && right.isNumber -> left.decimalValue().compareTo(right.decimalValue()) == 0
        left.isObject && right.isObject -> left.size() == right.size() &&
            left.properties().all { (name, value) -> right.get(name)?.let { sameJson(value, it) } == true }

        left.isArray && right.isArray -> left.size() == right.size() &&
            (0 until left.size()).all { sameJson(left.get(it), right.get(it)) }

        else -> left == right
    }

    private companion object {
        val DEFAULT_MAPPER: JsonMapper = StateJson.mapper
    }
}
