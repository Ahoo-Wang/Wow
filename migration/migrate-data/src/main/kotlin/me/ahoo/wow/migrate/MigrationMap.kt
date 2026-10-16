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

import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * `migration-map.json`, format 1 (design 07 §1 principle 5, §6): what the `wow-migrate` Skill wrote while rewriting a
 * 9.x domain, and what the data tool checks against the event types the 9.x store holds and the V10 Manifest before it
 * imports anything ([MappingCheck]).
 *
 * ```json
 * {
 *   "format": 1,
 *   "headers": { "app_id": "app" },
 *   "aggregates": [
 *     {
 *       "from": "example-service.order",
 *       "to": "example.order",
 *       "commands": [ { "from": "create_order", "to": "CreateOrder" } ],
 *       "events": [
 *         { "from": "order_created", "fromRevision": "0.0.1", "to": "OrderCreated", "toRevision": 1,
 *           "upgrade": "upgradeOrderCreatedFrom1" },
 *         { "from": "owner_transferred", "to": "wow.lifecycle.OwnerTransferred", "fields": { "toOwnerId": "ownerId" } },
 *         { "from": "order_archived", "to": "OrderArchived", "retire": true },
 *         { "from": "order_noted", "drop": true }
 *       ],
 *       "state": { "fields": { "orderStatus": "status" }, "ignore": ["payable", "items.totalPrice"] },
 *       "reservations": [ { "from": "order_no", "namespace": "order.no", "tenancy": "SYSTEM", "ownerId": "orderId" } ]
 *     }
 *   ]
 * }
 * ```
 *
 * - [aggregates] maps each 9.x aggregate, `contextName.aggregateName` as the 9.x stream records them, to its V10
 *   aggregate type, `context.aggregate`.
 * - [headers] lists the 9.x stream headers kept as request facts, by their 9.x key, with their V10 name. Every other
 *   header is dropped: 9.x framework headers ([NineXHeaders.FRAMEWORK]) become fields of their own or go (design 07
 *   §3), and a request fact not listed here is not recorded (decision D163).
 *
 * A file of another [format] is refused, as is a property this format does not define. [AggregateMapping.state] joined
 * format 1 with the state comparison (roadmap 8b.2) as an optional property, not a new format: a map without it reads
 * as before, and only `compare` reads it. [AggregateMapping.reservations] and [EventMapping.retire] joined format 1 the
 * same way (findings A4-8, A4-7).
 */
data class MigrationMap(
    val format: Int,
    val aggregates: List<AggregateMapping>,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(format == FORMAT) {
            "migration-map.json format $format is not supported; this tool reads format $FORMAT."
        }
        val framework = headers.keys.filter(NineXHeaders::isFramework)
        require(framework.isEmpty()) {
            "migration-map.json keeps the 9.x framework headers $framework as request facts."
        }
        require(headers.values.none { it.isBlank() }) { "migration-map.json renames a header to a blank name." }
    }

    companion object {
        /** The format this tool reads and the Skill writes (decision D59). */
        const val FORMAT: Int = 1

        private val READER: JsonMapper = WowJson.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

        /** Reads a `migration-map.json` from its [text]. */
        fun read(text: String): MigrationMap = READER.readValue(text, MigrationMap::class.java)
    }
}

/**
 * One 9.x aggregate [from] (`contextName.aggregateName`) imported as the V10 aggregate type [to]
 * (`context.aggregate`).
 *
 * - [commands] map the 9.x command names a stream records (its `upstream_name` header) to V10 command names. A stream
 *   records the type of the command that produced it (decision D55), so every command found in the store needs one.
 * - [defaultCommand] is the V10 command name of a stream that records no command name; without it, such a stream
 *   fails the check.
 * - [events] map the 9.x event names (and revisions) to V10 event names and revisions, retire or drop them.
 * - [state] says how a 9.x snapshot's state reads as the V10 state when `compare` compares them ([StateMapping]);
 *   by default field for field.
 * - [reservations] map the 9.x `PrepareKey`s whose values name an instance of this aggregate to V10 reservation
 *   namespaces ([ReservationMapping]): every `PrepareKey` the store holds needs one.
 *
 * A V10 name without a dot is the simple name in [to]'s namespace (`OrderCreated` is `example.order.OrderCreated`);
 * one with a dot is a system name such as `wow.lifecycle.AggregateDeleted`.
 */
data class AggregateMapping(
    val from: String,
    val to: String,
    val commands: List<CommandMapping> = emptyList(),
    val events: List<EventMapping> = emptyList(),
    val defaultCommand: String? = null,
    val state: StateMapping = StateMapping(),
    val reservations: List<ReservationMapping> = emptyList(),
) {
    init {
        val dot = from.lastIndexOf('.')
        require(dot in 1 until from.length - 1) { "A 9.x aggregate is named contextName.aggregateName: $from" }
        val toDot = to.indexOf('.')
        require(toDot in 1 until to.length - 1) { "A V10 aggregate type is named context.aggregate: $to" }
        require(defaultCommand == null || defaultCommand.isNotBlank()) { "$from has a blank default command." }
    }

    /** The 9.x aggregate. */
    fun source(): NineXAggregate = from.lastIndexOf('.').let {
        NineXAggregate(from.substring(0, it), from.substring(it + 1))
    }
}

/** The 9.x command [from] imported as the V10 command [to]. */
data class CommandMapping(val from: String, val to: String) {
    init {
        require(from.isNotBlank()) { "A command mapping names its 9.x command." }
        require(to.isNotBlank()) { "The command mapping of $from names its V10 command." }
    }
}

/**
 * The 9.x event [from], of the 9.x revision [fromRevision] (any revision when null), imported as the V10 event [to]
 * of the revision [toRevision]; imported under the retired name [to] when [retire] is set; or left out of its stream
 * when [drop] is set.
 *
 * - [retire] is for an event the V10 aggregate retired (decision D219, the 9.x `DroppedEvent`): the record is written
 *   as stored under the name [to], which the aggregate lists in `@AggregateRoot(retiredEvents)` (the Manifest's
 *   `retired`), so loading skips it and its stream still counts as a version. The mapping check fails when [to] is
 *   not retired there.
 * - [drop] leaves the event out of its stream. A stream whose every event is dropped would leave its version empty,
 *   which the log cannot hold: the mapping check counts such streams and fails before anything is imported; map
 *   those events with [retire] instead (finding A4-7).
 *
 * - [toRevision] is the revision the 9.x payload has in V10: a 9.x shape is revision 1, and when the V10 class moved
 *   on, its `@Upgrade` functions read it up to the current revision. It is at most the Manifest's revision.
 * - [fields] renames top-level payload fields, 9.x name to V10 name: for system events whose shape V10 fixes, such as
 *   the 9.x `OwnerTransferred.toOwnerId`, which is `wow.lifecycle.OwnerTransferred.ownerId`. A domain event's shape
 *   changes belong to its upgrade functions, not here.
 * - [upgrade] names the V10 upgrade function the Skill wrote for it; recorded for the report, not run by the tool.
 */
data class EventMapping(
    val from: String,
    val fromRevision: String? = null,
    val to: String? = null,
    val toRevision: Int = 1,
    val drop: Boolean = false,
    val retire: Boolean = false,
    val fields: Map<String, String> = emptyMap(),
    val upgrade: String? = null,
) {
    init {
        require(from.isNotBlank()) { "An event mapping names its 9.x event." }
        require(!(drop && retire)) { "The event mapping of $from both drops and retires it." }
        require(drop == (to == null)) { "The event mapping of $from either names its V10 event or drops it." }
        require(to == null || to.isNotBlank()) { "The event mapping of $from names a blank V10 event." }
        require(toRevision >= 1) { "The event mapping of $from has a V10 revision below 1." }
        require(!drop || fields.isEmpty()) { "The event mapping of $from drops it and renames its fields." }
        require(!retire || fields.isEmpty()) { "The event mapping of $from retires it and renames its fields." }
    }
}

/**
 * The 9.x `PrepareKey` named [from] (its MongoDB collection `prepare_<from>`) imported as V10 reservations in
 * [namespace] (decisions D38, D225, D227 ③), owned by instances of the enclosing aggregate:
 *
 * - each confirmed 9.x key (one prepared forever) becomes a confirmed reservation; a pending key (prepared with a time
 *   to live) or an expired one is not imported;
 * - its owner is the instance whose id the 9.x value holds: the value itself when [ownerId] is null, else the string
 *   at the dotted path [ownerId] in it (`userId`, `owner.id`). 9.x keys have no tenant, so the owner's tenant is the
 *   one its 9.x streams record;
 * - [tenancy] is where the key is unique in V10: [ReservationTenancy.TENANT], within the owner's tenant (the default
 *   of `DecideScope.reserve`), or [ReservationTenancy.SYSTEM], across the context's tenants, as every 9.x key was. It
 *   must be the tenancy the V10 decide function reserves the key with.
 */
data class ReservationMapping(
    val from: String,
    val namespace: String,
    val tenancy: ReservationTenancy = ReservationTenancy.TENANT,
    val ownerId: String? = null,
) {
    init {
        require(from.isNotBlank()) { "A reservation mapping names its 9.x PrepareKey." }
        require(namespace.isNotBlank()) { "The reservation mapping of $from names its V10 namespace." }
        require(ownerId == null || ownerId.split('.').none { it.isBlank() }) {
            "The reservation mapping of $from gives the owner id as a dotted path of names: $ownerId"
        }
    }
}

/**
 * How a 9.x snapshot's state reads as the V10 state, for the state comparison only ([StateDiff]; the import does not
 * read it):
 *
 * - [fields] renames top-level fields of the 9.x state, 9.x name to V10 name, where the Skill renamed a state
 *   property;
 * - [ignore] lists fields left out of the comparison on both sides, by their V10 name: a dotted path (`items.price`)
 *   goes into objects, and through an array into each of its elements. It is for a field only one side has by design
 *   (a 9.x property V10 dropped, a V10 property 9.x never had), an explained difference (design 07 §3 step 4), not for
 *   hiding values that differ.
 */
data class StateMapping(val fields: Map<String, String> = emptyMap(), val ignore: List<String> = emptyList()) {
    init {
        require(fields.keys.none { it.isBlank() } && fields.values.none { it.isBlank() }) {
            "A state field mapping names both fields."
        }
        require(ignore.none { path -> path.split('.').any { it.isBlank() } }) {
            "An ignored state field is a dotted path of names: $ignore"
        }
    }
}
