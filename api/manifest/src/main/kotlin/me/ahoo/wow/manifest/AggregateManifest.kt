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

package me.ahoo.wow.manifest

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * An aggregate of a context. Its [commands] are those it decides, those [mounted][CommandManifest.mounted] under its
 * routes, and its [system][CommandManifest.system] commands: the lifecycle commands its model handles and the rebuild
 * of its snapshots, named by their full names in the system namespace (`wow.lifecycle.DeleteAggregate`,
 * `wow.snapshot.RebuildSnapshot`). Those are the same types for every aggregate, so they are not names within the aggregate
 * and never conflict between aggregates; the body of each is the schema of [Manifest.defs] keyed by that full name
 * ([Manifest.schemaRef] of it), so a client types it like a command's.
 * [queries] are the read models of the aggregate a client queries ([QueryManifest]).
 *
 * [schedules] names the commands the aggregate's decide functions may schedule, each by its full type
 * name (`context.aggregate.Command`), as their `@OnCommand(schedules)` declare; null when none declares any.
 *
 * [ownership] is who the aggregate belongs to besides its tenant, as `@AggregateRoot(ownership)` declares it:
 * query admission restricts an owner-level caller's queries of an [Ownership.OWNER] aggregate to its owner, and
 * not those of an [Ownership.TENANT] one.
 *
 * [idSource] is where the aggregate's id comes from, as `@AggregateRoot(idSource)` declares it: an
 * [AggregateIdSource.OWNER] aggregate's id is its owner's, so every command also has a route that names no id and
 * means the caller's own aggregate ([CommandRoutes]), as its state read does, and no route generates an id.
 *
 * [retired] lists the events the aggregate retired, as `@AggregateRoot(retiredEvents)` declares them:
 * the stored names whose records are skipped, as [EventManifest.aliases] writes names (without a dot
 * within the aggregate, else full). The compatibility gate takes an event removed and retired for a retirement, and
 * the data migration checks a mapping to a retired name against it.
 *
 * [access] lists the aggregate's access attributes, as its state's `@Access` members declare them, in declaration
 * order: the facts its access policies compare with a caller's claims. The built-in ones, `owner` and `space`, are
 * every aggregate's and not listed. The compatibility gate takes one removed, renamed or retyped for a break.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class AggregateManifest @InternalWowApi constructor(
    val name: String,
    val description: String? = null,
    val state: StateManifest,
    val snapshot: SnapshotManifest = SnapshotManifest(),
    val commands: List<CommandManifest> = emptyList(),
    val events: List<EventManifest> = emptyList(),
    val queries: List<QueryManifest> = emptyList(),
    val schedules: List<String>? = null,
    val ownership: Ownership = Ownership.OWNER,
    val retired: List<String> = emptyList(),
    val idSource: AggregateIdSource = AggregateIdSource.ASSIGNED,
    val access: List<AccessAttributeManifest> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is AggregateManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "AggregateManifest(name=$name, description=$description, state=$state, snapshot=$snapshot, " +
            "commands=$commands, events=$events, queries=$queries, schedules=$schedules, " +
            "ownership=$ownership, retired=$retired, idSource=$idSource, access=$access)"

    /** The commands of the domain: those the aggregate decides or mounts, its system commands left out. */
    val domainCommands: List<CommandManifest> get() = commands.filterNot { it.system }

    /** The full names of the lifecycle commands the aggregate's model handles, among its system commands. */
    val lifecycle: List<String>
        get() = commands.filter { it.system && it.name.startsWith(SystemCommands.LIFECYCLE_PREFIX) }.map { it.name }

    private fun valueFields(): List<Any?> = listOf(
        name,
        description,
        state,
        snapshot,
        commands,
        events,
        queries,
        schedules,
        ownership,
        retired,
        idSource,
        access,
    )
}

/**
 * An access attribute of an aggregate (`@Access`): its [name] ([AccessAttributeName]), unique within the aggregate
 * ignoring case, the [type] of its values, and whether it holds several of them ([multiple]) or one, which may be
 * absent.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class AccessAttributeManifest @InternalWowApi constructor(
    val name: String,
    val type: Type,
    val multiple: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is AccessAttributeManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "AccessAttributeManifest(name=$name, type=$type, multiple=$multiple)"

    private fun valueFields(): List<Any?> = listOf(name, type, multiple)

    /**
     * The type of an access attribute's values.
     *
     * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
     */
    enum class Type {
        /** A string; an enum's values are the names its constants are written under. */
        STRING,

        /** An integer, an `Int` or a `Long`. */
        INTEGER,

        /** A boolean. */
        BOOLEAN,
    }
}

/**
 * The aggregate's state: its [schema], and the [fingerprint] a snapshot written under it carries;
 * the fingerprint is null until the schemas are generated. In a peer contract, [view] is the binary name of the
 * `<State>View` the api module generates of it ([PeerContracts.viewsOf]), which a reader finds the view by: null for
 * a state the contract has no view of, and in a service's own Manifest.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class StateManifest @InternalWowApi constructor(
    val schema: String,
    val fingerprint: String? = null,
    val view: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is StateManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "StateManifest(schema=$schema, fingerprint=$fingerprint, view=$view)"

    private fun valueFields(): List<Any?> = listOf(schema, fingerprint, view)
}

/**
 * Whether the aggregate keeps snapshots ([enabled]), and the revision of its state's structure
 * the snapshots are written at ([stateRevision]): raised, the service rebuilds the stored snapshots in
 * place, and the compatibility gate accepts a state schema change of an aggregate whose state no one else reads (no
 * snapshot query, no published stream with the state).
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class SnapshotManifest @InternalWowApi constructor(val enabled: Boolean = true, val stateRevision: Int = 1) {
    init {
        require(stateRevision >= 1) { "A state revision starts at 1." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SnapshotManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "SnapshotManifest(enabled=$enabled, stateRevision=$stateRevision)"

    private fun valueFields(): List<Any?> = listOf(enabled, stateRevision)
}

/**
 * A query model of an aggregate: [model] is [QueryRoutes.SNAPSHOT], the aggregate's snapshots,
 * when it keeps them, or [QueryRoutes.EVENTS], its event streams; [routes] are the model's query route and the route
 * of its capability descriptor ([QueryRoutes]). What a model can do (its operations, fields, limits) is not here: it
 * depends on the query backend the service runs on, which the build does not know, and the
 * descriptor route answers it.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class QueryManifest @InternalWowApi constructor(val model: String, val routes: List<RouteManifest> = emptyList()) {
    override fun equals(other: Any?): Boolean =
        this === other || other is QueryManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "QueryManifest(model=$model, routes=$routes)"

    private fun valueFields(): List<Any?> = listOf(model, routes)
}

/**
 * A command the aggregate decides, or one [mounted] under its routes.
 *
 * [aliases] are former names, as `@Alias` declares them: one without a dot is within the aggregate, one with dots a
 * full type name. [produces] names the events of the aggregate the command's decide function and after hooks can
 * produce, as their signatures say or `@OnCommand(produces)` declares; null when neither says (a
 * `Decision`, `Any`). [routes] are the command's REST routes ([CommandRoutes]).
 *
 * A [mounted] command (`@MountedCommand`) is decided by no aggregate: a transformer of the service turns
 * it into a command that is, at admission. It creates nothing itself ([creation] is [Creation.NONE]) and produces
 * nothing ([produces] is null); [transformsTo] lists, by full type name, the commands it becomes, as
 * `@MountedCommand(transformsTo)` declares them; null when it declares none.
 *
 * [description] is what the command's class says it does: its `@Description`, else the summary of its KDoc; null when
 * it says nothing. It documents the command for a client, and is no part of the contract.
 *
 * [idProperty] is the property of the command's JSON body that holds the id of the aggregate it acts on, as its class's
 * `@AggregateId` property declares it; null for a command whose request names the aggregate. Such a command's routes
 * name no id: a sender reads it from the body, and one that cannot (a computed id it does not send) leaves it to the
 * service, which reads it from the command.
 *
 * [occurredAtProperty] is the property of a creation command's JSON body that declares when what it records happened
 * (epoch milliseconds), as its class's `@OccurredAt` property declares it: the creating commit's time, an import's
 * creation time in the system it came from; null for a command that declares none.
 *
 * A [system] command is the framework's, not the domain's: a lifecycle command, a snapshot rebuild or a schedule job's
 * command, named by its full system name (`wow.lifecycle.DeleteAggregate`) and routed on a reserved segment
 * ([CommandRoutes.SYSTEM_SEGMENT_PREFIX]). Every route the edge serves is a command's [routes], a system command's
 * included, so a client routes from the Manifest alone.
 *
 * An [internal] command (`@InternalCommand`) is sent by the platform alone: the service's own sagas, schedules and
 * in-process gateway, and other services on its internal port. Its [routes] are written as any command's, and the
 * internal port serves them; the public port does not, and its OpenAPI document and the generated clients leave the
 * command out. A system command is never internal.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class CommandManifest @InternalWowApi constructor(
    val name: String,
    val aliases: List<String> = emptyList(),
    val schema: String,
    val creation: Creation = Creation.NONE,
    val produces: List<String>? = null,
    val routes: List<RouteManifest> = emptyList(),
    val mounted: Boolean = false,
    val transformsTo: List<String>? = null,
    val description: String? = null,
    val system: Boolean = false,
    val idProperty: String? = null,
    val internal: Boolean = false,
    val occurredAtProperty: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CommandManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandManifest(name=$name, aliases=$aliases, schema=$schema, creation=$creation, produces=$produces, " +
            "routes=$routes, mounted=$mounted, transformsTo=$transformsTo, description=$description, system=$system, " +
            "idProperty=$idProperty, internal=$internal, occurredAtProperty=$occurredAtProperty)"

    private fun valueFields(): List<Any?> = listOf(
        name,
        aliases,
        schema,
        creation,
        produces,
        routes,
        mounted,
        transformsTo,
        description,
        system,
        idProperty,
        internal,
        occurredAtProperty,
    )
}

/**
 * An HTTP route: its [method] and its [path], a template whose variables are written literally in braces
 * (`/shop/cart/{id}/add-item`).
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class RouteManifest @InternalWowApi constructor(val method: String, val path: String) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RouteManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "RouteManifest(method=$method, path=$path)"

    private fun valueFields(): List<Any?> = listOf(method, path)
}

/**
 * An event of the aggregate: its name in the log, its [revision] and former names, and its
 * [description], as for a [CommandManifest].
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class EventManifest @InternalWowApi constructor(
    val name: String,
    val aliases: List<String> = emptyList(),
    val revision: Int = 1,
    val schema: String,
    val description: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is EventManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "EventManifest(name=$name, aliases=$aliases, revision=$revision, schema=$schema, description=$description)"

    private fun valueFields(): List<Any?> = listOf(name, aliases, revision, schema, description)
}
