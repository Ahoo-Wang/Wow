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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.RouteManifest

// The comparison of an aggregate with its baseline, as [ManifestCompatibility] states it: its state, commands,
// events, access attributes, query models and published stream.

internal fun Changes.compareAggregate(path: String, baseline: AggregateManifest, current: AggregateManifest, exposure: Exposure) {
    compareState(path, baseline, current, exposure)
    if (baseline.snapshot.enabled != current.snapshot.enabled) {
        if (current.snapshot.enabled) {
            additive("$path.snapshot", "snapshots enabled")
        } else {
            breaking("$path.snapshot", "snapshots disabled")
        }
    }
    if (baseline.ownership != current.ownership) {
        breaking("$path.ownership", "ownership changed from ${baseline.ownership} to ${current.ownership}")
    }
    val owned = when {
        baseline.idSource == current.idSource -> null
        current.idSource == AggregateIdSource.OWNER -> Side.CURRENT
        else -> Side.BASELINE
    }
    if (owned != null) {
        breaking("$path.idSource", "id source changed from ${baseline.idSource} to ${current.idSource}")
    }
    compareNamed(
        "$path.commands",
        baseline.commands,
        current.commands,
        { it.name },
        "command",
        { entry, old, new -> compareCommand(entry, old, new, owned) },
        { it.aliases },
    )
    compareNamed(
        "$path.events",
        baseline.events,
        current.events,
        { it.name },
        "event",
        { entry, old, new -> compareEvent(entry, old, new, exposure) },
        { it.aliases },
        { entry, old -> removedEvent(entry, old, current.retired) },
    )
    compareValues("$path.retired", baseline.retired, current.retired, "retired event")
    compareNamed("$path.queries", baseline.queries, current.queries, { it.model }, "query model", this::compareQuery)
    compareNamed(
        "$path.access",
        baseline.access,
        current.access,
        { it.name },
        "access attribute",
        this::compareAccess,
    )
}

/** An access attribute's values are stored, indexed and compared by policies: retyping it breaks them. */
private fun Changes.compareAccess(path: String, baseline: AccessAttributeManifest, current: AccessAttributeManifest) {
    if (baseline.type != current.type) {
        breaking(path, "access attribute type changed from ${baseline.type} to ${current.type}")
    }
    if (baseline.multiple != current.multiple) {
        breaking(path, if (current.multiple) "access attribute made multiple" else "access attribute made single")
    }
}

/**
 * The state schema: a breaking change keeps the contract only when the aggregate's
 * state revision was raised, so the stored snapshots are rebuilt from the log, and no one else reads the state
 * ([Exposure.state]): the snapshot query's clients do, whenever the aggregate keeps snapshots. A lowered revision
 * breaks. The stored snapshots are compared as [SchemaDirection.STORED], so a constraint counts for the readers
 * alone ([readerBreaks]).
 */
private fun Changes.compareState(
    path: String,
    baseline: AggregateManifest,
    current: AggregateManifest,
    exposure: Exposure
) {
    val schemaChanges = schemas.compare(
        "$path.state",
        baseline.state.schema,
        current.state.schema,
        queryRoot = true,
        direction = SchemaDirection.STORED,
    )
    val old = baseline.snapshot.stateRevision
    val new = current.snapshot.stateRevision
    when {
        new < old -> {
            breaking("$path.snapshot.stateRevision", "state revision lowered from $old to $new")
            list += schemaChanges
        }

        new > old -> {
            additive("$path.snapshot.stateRevision", "state revision raised from $old to $new")
            val readers = exposure.state
            list += schemaChanges.map {
                when {
                    it.kind != ChangeKind.BREAKING -> it
                    readers.isEmpty() -> it.copy(
                        kind = ChangeKind.ADDITIVE,
                        description = "${it.description} (snapshots rebuilt at state revision $new)",
                    )

                    else -> it.copy(
                        description = "${it.description}: snapshots are rebuilt at state revision $new, but " +
                            "${readers.joinToString(" and ")} read the state",
                    )
                }
            }
        }

        else -> list += schemaChanges.map {
            when {
                it.kind != ChangeKind.BREAKING -> it
                exposure.state.isEmpty() -> it.copy(description = "${it.description} without a new state revision")
                else -> it.copy(
                    description = "${it.description}: ${exposure.state.joinToString(" and ")} read the state, " +
                        "which a new state revision does not change",
                )
            }
        }
    }
    readerBreaks("$path.state", baseline.state.schema, current.state.schema, schemaChanges, exposure.state)
}

/** Whether this path is [ancestor] or within it: a field, an item or a value of it. */
private fun String.isUnder(ancestor: String): Boolean =
    this == ancestor || listOf(".", "[", "{").any { startsWith(ancestor + it) }

/**
 * What breaks the readers of an output (state, events, the published stream), which read what the service writes
 * now with what they were built against: the changes a reader breaks on ([SchemaDirection.OUTPUT]), such as a
 * field made optional or an enum value added, that do not already break the service's own records. A revision
 * only rebuilds or upgrades the service's own records, so it keeps none of these; without [readers] there is no
 * one to break. Each takes the place of the same change found additive for the service's own records.
 */
private fun Changes.readerBreaks(
    path: String,
    baseline: String,
    current: String,
    own: List<ManifestChange>,
    readers: List<String>,
) {
    if (readers.isEmpty()) return
    val ownBreaks = own.filter { it.kind == ChangeKind.BREAKING }.map { it.path }.toSet()
    val breaks = schemas.compare(path, baseline, current, path.endsWith(".state"), SchemaDirection.OUTPUT)
        // A change under one that already breaks the service's own records is reported there.
        .filter { change -> change.kind == ChangeKind.BREAKING && ownBreaks.none { change.path.isUnder(it) } }
    list.removeAll { change ->
        change.kind == ChangeKind.ADDITIVE &&
            breaks.any { it.path == change.path && it.description == change.description }
    }
    list += breaks.map { it.copy(description = "${it.description}: ${readers.joinToString(" and ")} read it") }
}

/**
 * A command; [owned] is the side whose aggregate's id is its owner's when the id source changed: its routes by no
 * id that the other side lacks are that change's, already reported.
 */
private fun Changes.compareCommand(path: String, baseline: CommandManifest, current: CommandManifest, owned: Side?) {
    compareValues("$path.aliases", baseline.aliases, current.aliases, "alias")
    list += schemas.compare("$path.schema", baseline.schema, current.schema)
    compareInternal("$path.internal", baseline.internal, current.internal)
    compareOccurredAt("$path.occurredAtProperty", baseline.occurredAtProperty, current.occurredAtProperty)
    if (baseline.creation != current.creation) {
        val change = "creation changed from ${baseline.creation} to ${current.creation}"
        if (current.creation == Creation.ALLOWED) {
            additive("$path.creation", change)
        } else {
            breaking("$path.creation", change)
        }
    }
    val oldProduces = baseline.produces
    val newProduces = current.produces
    if (oldProduces != null && newProduces != null) {
        compareValues("$path.produces", oldProduces, newProduces, "produced event")
    } else if (oldProduces != newProduces) {
        additive(
            "$path.produces",
            if (newProduces == null) "produced events no longer declared" else "produced events declared"
        )
    }
    // The route is a parameter, not a receiver: an extension's `path` would read this function's.
    fun isOwn(route: RouteManifest, others: List<RouteManifest>) = CommandRoutes.ID !in route.path && route !in others
    compareRoutes(
        "$path.routes",
        if (owned == Side.BASELINE) baseline.routes.filterNot { isOwn(it, current.routes) } else baseline.routes,
        if (owned == Side.CURRENT) current.routes.filterNot { isOwn(it, baseline.routes) } else current.routes,
    )
}

/**
 * A creation command's declaration of when it occurred (`@OccurredAt`): one added is additive, since a sender that
 * gives no time keeps the clock's; one removed or moved to another property is breaking, since an importer's times
 * would then be ignored, or read from a property it does not send.
 */
private fun Changes.compareOccurredAt(path: String, baseline: String?, current: String?) {
    when {
        baseline == current -> Unit
        baseline == null -> additive(path, "the command declares when it occurred, by $current")
        current == null -> breaking(
            path,
            "the command no longer declares when it occurred: an importer's times are ignored"
        )
        else -> breaking(path, "the command declares when it occurred by $current, not $baseline")
    }
}

/** A command made internal, whose public routes go, or made public, whose public routes come. */
private fun Changes.compareInternal(path: String, baseline: Boolean, current: Boolean) {
    if (current && !baseline) {
        breaking(path, "the command was made internal: the public port no longer serves its routes")
    } else if (baseline && !current) {
        additive(path, "the command was made public: the public port serves its routes")
    }
}

/**
 * An event: a breaking schema change keeps the contract only when its revision was
 * raised, so the service's own old records are upgraded, and no one else reads the event ([Exposure.events]).
 * Every aggregate lists the events query, whose clients read its events, so a structural change of a service's
 * event breaks within its line, its revision raised or not: the revision and its `@Upgrade` keep the service's own
 * history readable, and a new event beside the old one keeps the contract. The stored records are compared as
 * [SchemaDirection.STORED], so a constraint counts for the readers alone ([readerBreaks]).
 */
private fun Changes.compareEvent(path: String, baseline: EventManifest, current: EventManifest, exposure: Exposure) {
    compareValues("$path.aliases", baseline.aliases, current.aliases, "alias")
    val schemaChanges =
        schemas.compare("$path.schema", baseline.schema, current.schema, direction = SchemaDirection.STORED)
    when {
        current.revision < baseline.revision -> {
            breaking("$path.revision", "revision lowered from ${baseline.revision} to ${current.revision}")
            list += schemaChanges
        }

        current.revision > baseline.revision -> {
            additive("$path.revision", "revision raised from ${baseline.revision} to ${current.revision}")
            // The compiler requires an @Upgrade from each earlier revision: the old records still read.
            val readers = exposure.events
            list += schemaChanges.map {
                when {
                    it.kind != ChangeKind.BREAKING -> it
                    // Only a Manifest whose aggregate lists no events query and publishes no stream.
                    readers.isEmpty() -> it.copy(
                        kind = ChangeKind.ADDITIVE,
                        description = "${it.description} (upgraded from revision ${baseline.revision})",
                    )

                    else -> it.copy(
                        description = "${it.description}: the service upgrades its records from revision " +
                            "${baseline.revision}, but ${readers.joinToString(" and ")} read the event at its " +
                            "current revision",
                    )
                }
            }
        }

        else -> list += schemaChanges.map {
            when {
                it.kind != ChangeKind.BREAKING -> it
                exposure.events.isEmpty() ->
                    it.copy(description = "${it.description} without a new revision and its @Upgrade")

                else -> it.copy(
                    description = "${it.description}: ${exposure.events.joinToString(" and ")} read the event, " +
                        "which a new revision does not change",
                )
            }
        }
    }
    readerBreaks("$path.schema", baseline.schema, current.schema, schemaChanges, exposure.events)
}

/**
 * An event the current Manifest no longer has: retired when each name its records
 * may be stored under, its name and its aliases, is in [retired]; else its history no longer loads.
 */
private fun Changes.removedEvent(path: String, event: EventManifest, retired: List<String>) {
    val names = listOf(event.name) + event.aliases
    val unretired = names.filterNot { name -> retired.any { sameName(it, name) } }
    when {
        unretired.isEmpty() -> additive(path, "event retired")
        unretired.size == names.size -> breaking(
            path,
            "event removed: list it in @AggregateRoot(retiredEvents) to retire it, or its history no longer loads",
        )

        else -> breaking(
            path,
            "event retired without its former names ${unretired.joinToString()}: their records no longer load",
        )
    }
}

/** Whether two names of an event within an aggregate are the same: a simple one, or a full one ending in it. */
private fun sameName(left: String, right: String): Boolean =
    left == right || left.endsWith(".$right") || right.endsWith(".$left")

private fun Changes.compareQuery(path: String, baseline: QueryManifest, current: QueryManifest) {
    compareRoutes("$path.routes", baseline.routes, current.routes)
}

/**
 * Who besides the service reads an aggregate's state and events, in the baseline or now: the
 * clients of its snapshot and events query models, and the subscribers of its published stream,
 * which carries its events and, unless it leaves it out, its state.
 */
internal class Exposure(val state: List<String>, val events: List<String>) {
    companion object {
        fun of(aggregate: String, baseline: ContextManifest, current: ContextManifest): Exposure {
            val manifests = listOf(baseline, current)
            val queries = manifests.flatMap { context ->
                context.aggregates.filter { it.name == aggregate }.flatMap { it.queries }.map { it.model }
            }.toSet()
            val published = manifests.flatMap { context -> context.published.filter { it.aggregate == aggregate } }
            val stream = "the subscribers of its published stream".takeIf { published.isNotEmpty() }
            val stateStream = stream.takeIf { published.any { it.includeState } }
            return Exposure(
                state = listOfNotNull(
                    "the snapshot query's clients".takeIf { QueryRoutes.SNAPSHOT in queries },
                    stateStream,
                ),
                events = listOfNotNull(
                    "the events query's clients".takeIf { QueryRoutes.EVENTS in queries },
                    stream,
                ),
            )
        }
    }
}

internal fun Changes.comparePublished(path: String, baseline: PublishedManifest, current: PublishedManifest) {
    if (baseline.topic != current.topic) {
        breaking("$path.topic", "topic changed from ${baseline.topic} to ${current.topic}")
    }
    if (baseline.includeState != current.includeState) {
        if (current.includeState) {
            additive("$path.includeState", "the state is now included")
        } else {
            breaking("$path.includeState", "the state is no longer included")
        }
    }
}

/** One side of a comparison. */
private enum class Side { BASELINE, CURRENT }
