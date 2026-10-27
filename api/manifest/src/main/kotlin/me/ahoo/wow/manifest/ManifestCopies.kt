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

@file:JvmName("ManifestCopies")

package me.ahoo.wow.manifest

import me.ahoo.wow.api.annotation.InternalWowApi

// The Manifest model has no `copy`: it may gain fields, and a `copy` would be a promise to every caller.
// Wow's own code replaces fields through these, never by calling a constructor with the fields it knows of, so a field
// added later is carried by every copy (ValueSemanticsSpec holds each copyWith to every field).

@InternalWowApi
fun Manifest.copyWith(
    format: Int = this.format,
    service: ServiceInfo? = this.service,
    contexts: List<ContextManifest> = this.contexts,
    errors: List<ErrorManifest> = this.errors,
    defs: Map<String, Any?> = this.defs,
): Manifest = Manifest(format, service, contexts, errors, defs)

@InternalWowApi
fun ServiceInfo.copyWith(
    name: String = this.name,
    version: String? = this.version,
): ServiceInfo = ServiceInfo(name, version)

@InternalWowApi
fun ContextManifest.copyWith(
    name: String = this.name,
    description: String? = this.description,
    aggregates: List<AggregateManifest> = this.aggregates,
    subscriptions: List<SubscriptionManifest> = this.subscriptions,
    published: List<PublishedManifest> = this.published,
    errors: List<ErrorManifest> = this.errors,
): ContextManifest = ContextManifest(name, description, aggregates, subscriptions, published, errors)

@InternalWowApi
fun AggregateManifest.copyWith(
    name: String = this.name,
    description: String? = this.description,
    state: StateManifest = this.state,
    snapshot: SnapshotManifest = this.snapshot,
    commands: List<CommandManifest> = this.commands,
    events: List<EventManifest> = this.events,
    lifecycle: List<String> = this.lifecycle,
    queries: List<QueryManifest> = this.queries,
    schedules: List<String>? = this.schedules,
    ownership: Ownership = this.ownership,
    retired: List<String> = this.retired,
): AggregateManifest =
    AggregateManifest(
        name,
        description,
        state,
        snapshot,
        commands,
        events,
        lifecycle,
        queries,
        schedules,
        ownership,
        retired,
    )

@InternalWowApi
fun StateManifest.copyWith(
    schema: String = this.schema,
    fingerprint: String? = this.fingerprint,
): StateManifest = StateManifest(schema, fingerprint)

@InternalWowApi
fun SnapshotManifest.copyWith(
    enabled: Boolean = this.enabled,
    stateRevision: Int = this.stateRevision,
): SnapshotManifest = SnapshotManifest(enabled, stateRevision)

@InternalWowApi
fun QueryManifest.copyWith(
    model: String = this.model,
    routes: List<RouteManifest> = this.routes,
): QueryManifest = QueryManifest(model, routes)

@InternalWowApi
fun CommandManifest.copyWith(
    name: String = this.name,
    aliases: List<String> = this.aliases,
    schema: String = this.schema,
    creation: Creation = this.creation,
    produces: List<String>? = this.produces,
    routes: List<RouteManifest> = this.routes,
    mounted: Boolean = this.mounted,
    transformsTo: List<String>? = this.transformsTo,
    description: String? = this.description,
): CommandManifest =
    CommandManifest(name, aliases, schema, creation, produces, routes, mounted, transformsTo, description)

@InternalWowApi
fun RouteManifest.copyWith(
    method: String = this.method,
    path: String = this.path,
): RouteManifest = RouteManifest(method, path)

@InternalWowApi
fun ErrorManifest.copyWith(
    code: String = this.code,
    status: Int = this.status,
    description: String? = this.description,
): ErrorManifest = ErrorManifest(code, status, description)

@InternalWowApi
fun EventManifest.copyWith(
    name: String = this.name,
    aliases: List<String> = this.aliases,
    revision: Int = this.revision,
    schema: String = this.schema,
    description: String? = this.description,
): EventManifest = EventManifest(name, aliases, revision, schema, description)

@InternalWowApi
fun SubscriptionManifest.copyWith(
    name: String = this.name,
    kind: SubscriptionKind = this.kind,
    source: SubscriptionSource = this.source,
    function: FunctionManifest = this.function,
    derives: List<String>? = this.derives,
    rejected: List<String>? = this.rejected,
    start: SubscriptionStart? = this.start,
    schedules: List<String>? = this.schedules,
    retry: RetryManifest? = this.retry,
    committed: List<String>? = this.committed,
    job: JobManifest? = this.job,
    calls: List<String>? = this.calls,
): SubscriptionManifest =
    SubscriptionManifest(name, kind, source, function, derives, rejected, start, schedules, retry, committed, job, calls)

@InternalWowApi
fun JobManifest.copyWith(
    name: String = this.name,
    cron: String = this.cron,
    zone: String = this.zone,
    scope: String = this.scope,
    sharded: Boolean = this.sharded,
    misfire: String = this.misfire,
): JobManifest = JobManifest(name, cron, zone, scope, sharded, misfire)

@InternalWowApi
fun RetryManifest.copyWith(
    maxAttempts: Int? = this.maxAttempts,
    initialBackoff: String? = this.initialBackoff,
    maxBackoff: String? = this.maxBackoff,
    timeout: String? = this.timeout,
    recoverable: List<String>? = this.recoverable,
    unrecoverable: List<String>? = this.unrecoverable,
    multiplier: Double? = this.multiplier,
): RetryManifest = RetryManifest(
    maxAttempts,
    initialBackoff,
    maxBackoff,
    timeout,
    recoverable,
    unrecoverable,
    multiplier
)

@InternalWowApi
fun SubscriptionSource.copyWith(
    context: String? = this.context,
    aggregates: List<String> = this.aggregates,
    events: List<String> = this.events,
    nullableState: Boolean = this.nullableState,
    eventSchemas: List<String> = this.eventSchemas,
): SubscriptionSource = SubscriptionSource(context, aggregates, events, nullableState, eventSchemas)

@InternalWowApi
fun PublishedManifest.copyWith(
    aggregate: String = this.aggregate,
    topic: String = this.topic,
    includeState: Boolean = this.includeState,
): PublishedManifest = PublishedManifest(aggregate, topic, includeState)

@InternalWowApi
fun FunctionManifest.copyWith(
    kind: FunctionKind = this.kind,
    context: String = this.context,
    processor: String = this.processor,
    name: String = this.name,
): FunctionManifest = FunctionManifest(kind, context, processor, name)

@InternalWowApi
fun ManifestFragment.copyWith(
    source: String = this.source,
    manifest: Manifest = this.manifest,
): ManifestFragment = ManifestFragment(source, manifest)
