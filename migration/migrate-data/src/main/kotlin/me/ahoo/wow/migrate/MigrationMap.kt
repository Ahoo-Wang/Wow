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
 *         { "from": "order_archived", "drop": true }
 *       ]
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
 * A file of another [format] is refused, as is a property this format does not define.
 */
data class MigrationMap(
    val format: Int,
    val aggregates: List<AggregateMapping>,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(format == FORMAT) { "migration-map.json format $format is not supported; this tool reads format $FORMAT." }
        val framework = headers.keys.filter(NineXHeaders::isFramework)
        require(framework.isEmpty()) { "migration-map.json keeps the 9.x framework headers $framework as request facts." }
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
 * - [events] map the 9.x event names (and revisions) to V10 event names and revisions, or drop them.
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
) {
    init {
        val dot = from.lastIndexOf('.')
        require(dot in 1 until from.length - 1) { "A 9.x aggregate is named contextName.aggregateName: $from" }
        val toDot = to.indexOf('.')
        require(toDot in 1 until to.length - 1) { "A V10 aggregate type is named context.aggregate: $to" }
        require(defaultCommand == null || defaultCommand.isNotBlank()) { "$from has a blank default command." }
    }

    /** The 9.x aggregate. */
    fun source(): NineXAggregate = from.lastIndexOf('.').let { NineXAggregate(from.substring(0, it), from.substring(it + 1)) }
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
 * of the revision [toRevision], or left out of the stream when [drop] is set (a retired event, the 9.x `DroppedEvent`).
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
    val fields: Map<String, String> = emptyMap(),
    val upgrade: String? = null,
) {
    init {
        require(from.isNotBlank()) { "An event mapping names its 9.x event." }
        require(drop == (to == null)) { "The event mapping of $from either names its V10 event or drops it." }
        require(to == null || to.isNotBlank()) { "The event mapping of $from names a blank V10 event." }
        require(toRevision >= 1) { "The event mapping of $from has a V10 revision below 1." }
        require(!drop || fields.isEmpty()) { "The event mapping of $from drops it and renames its fields." }
    }
}
