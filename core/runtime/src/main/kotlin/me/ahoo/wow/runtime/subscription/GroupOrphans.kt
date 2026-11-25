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

package me.ahoo.wow.runtime.subscription

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.SubscriptionId

/**
 * The checkpoints a [SubscriptionRuntime]'s [group] holds of a subscription it no longer has, a renamed or removed one,
 * with what it parked under the name in [ledger]: found among the [directory] of the group's subscriptions, reported to
 * the operator ([GroupOrphans.orphans]) and told once at the runtime's first start ([tellOrphans]).
 */
internal class GroupOrphans(
    private val group: String,
    private val ledger: FailureLedger,
    /** The group's registered subscriptions by name, as the runtime keeps them. */
    private val directory: Map<String, Subscription>,
) {
    /**
     * The checkpoints in [saved] of a subscription the group no longer has, with the entries parked under the name in
     * the group's contexts, up to [maxParked]: what one renamed or removed left behind, by name. Not Wow's own
     * (`wow.…`, which it ends), but for a sink's other generation ([anotherGeneration]). A start warns of them;
     * operators read them ([me.ahoo.wow.runtime.node.subscriptionsView]).
     */
    internal suspend fun orphans(saved: List<Checkpoint>, maxParked: Int): List<OrphanCheckpoints> {
        require(maxParked >= 1) { "At least one parked entry is counted." }
        val generations = directory.keys.filter { it.startsWith(RESERVED) }.mapTo(HashSet(), ::familyOf)
        return saved.filter {
            !directory.containsKey(it.subscription) &&
                (!it.subscription.startsWith(RESERVED) || anotherGeneration(it.subscription, generations))
        }
            .groupBy { it.subscription }
            .toSortedMap()
            .map { (name, positions) ->
                val parked = directory.values.mapTo(HashSet()) { it.context }
                    .sumOf { ledger.count(SubscriptionId(it, name), maxParked) }.coerceAtMost(maxParked)
                OrphanCheckpoints(group, name, positions.sortedBy { it.partition }, parked)
            }
    }

    /**
     * Whether [name], one of Wow's, is another generation of a sink the group runs: a sink's subscriptions are named
     * `<processor>.<aggregate>`, and a raised `sink.generation` names them `<processor>-g<n>.<aggregate>` (#7097), so
     * a name of the same family ([familyOf]) the group does not run is the generation it left behind, or the higher
     * one a lowered generation left, with what it parked under it.
     */
    private fun anotherGeneration(name: String, generations: Set<String>): Boolean = familyOf(name) in generations

    /**
     * Warns of what [GroupOrphans.orphans] finds in [saved]: a subscription's name is its identity, so a renamed one
     * starts again as its start says; pinning its `@Name` to the old name keeps its position. A sink's other
     * generation wrote into a destination that was emptied: its parked entries are to be skipped.
     */
    suspend fun tellOrphans(saved: List<Checkpoint>) {
        val orphans = orphans(saved, ORPHAN_COUNT)
        if (orphans.isEmpty()) {
            return
        }
        val described = orphans.map { orphan ->
            val at = orphan.positions.joinToString { "partition ${it.partition} at ${it.position}" }
            val held = if (orphan.parked >= ORPHAN_COUNT) "$ORPHAN_COUNT or more" else "${orphan.parked}"
            "${orphan.name} ($at; $held parked)"
        }
        logger.warn {
            "Subscription group $group has checkpoints of subscriptions it no longer has: " +
                "${described.joinToString("; ")}. A renamed subscription starts again under its new name, as its " +
                "start says; to keep its position, pin its @Name to the old one. What is left under an old name " +
                "is not read again." + if (orphans.any { it.name.startsWith(RESERVED) }) {
                    " A sink's other generation (<processor>-g<n>) wrote into a destination that was emptied when its " +
                        "sink.generation was raised: skip its parked entries; only raise a sink's generation."
                } else {
                    ""
                }
        }
    }
}

/**
 * The checkpoints a subscription of [group] left behind: [name], a name the group no longer has, saved its [positions],
 * one per partition, and has [parked] entries under it, counted up to a limit. A checkpoint holds a subscription's name
 * alone, without its context.
 */
@InternalWowApi
data class OrphanCheckpoints(
    val group: String,
    val name: String,
    val positions: List<Checkpoint>,
    val parked: Int,
)

/** The runtime's: what it tells of its orphans is logged as it was. */
private val logger = KotlinLogging.logger(SubscriptionRuntime::class.java.name)

/** The prefix of Wow's own subscriptions' names, which the compiler reserves. */
private const val RESERVED = "wow."

/** A sink's generation from the second on, at the end of its processor's name (`-g2`). */
private val GENERATION = Regex("-g[0-9]+$")

/** [name]'s family: its processor without a sink's generation, then its aggregate (`wow.clickhouse-sink.order`). */
private fun familyOf(name: String): String =
    GENERATION.replace(name.substringBeforeLast('.'), "") + "." + name.substringAfterLast('.')

/** How many entries parked under an old name a start counts at most. */
private const val ORPHAN_COUNT = 1_000
