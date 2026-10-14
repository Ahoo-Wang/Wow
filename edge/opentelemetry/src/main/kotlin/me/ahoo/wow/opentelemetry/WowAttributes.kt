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

package me.ahoo.wow.opentelemetry

/** The attributes of Wow's spans (decision D174); the metrics' tags are named the same, without the `wow.` prefix. */
object WowAttributes {
    /** The instrumentation scope of Wow's spans. */
    const val INSTRUMENTATION: String = "me.ahoo.wow"

    const val CONTEXT: String = "wow.context"
    const val AGGREGATE: String = "wow.aggregate"
    const val AGGREGATE_ID: String = "wow.aggregate.id"
    const val TENANT_ID: String = "wow.tenant.id"
    const val COMMAND: String = "wow.command"
    const val COMMAND_ID: String = "wow.command.id"
    const val REQUEST_ID: String = "wow.request.id"
    const val VERSION: String = "wow.version"
    const val SUBSCRIPTION: String = "wow.subscription"
    const val TIMER_ID: String = "wow.timer.id"
    const val QUERY_TYPE: String = "wow.query.type"
    const val OUTCOME: String = "wow.outcome"

    /** OpenTelemetry's messaging attributes of a topic's span. */
    const val MESSAGING_DESTINATION: String = "messaging.destination.name"
    const val MESSAGING_OPERATION: String = "messaging.operation.type"
    const val MESSAGING_BATCH_COUNT: String = "messaging.batch.message_count"
}

/** The names and tags of Wow's Micrometer metrics (decision D174). */
object WowMetrics {
    /** Timer: a command's execution by the kernel, from taking it to its outcome; tags context, aggregate, outcome. */
    const val COMMIT: String = "wow.command.commit"

    /** Timer: from a stream's commit to a subscription handling it; tags context, subscription. */
    const val SUBSCRIPTION_LAG: String = "wow.subscription.lag"

    /** Gauge: the entries a subscription has parked; tag subscription. */
    const val PARKED: String = "wow.subscription.parked"

    /** Counter: the entries parked, and those whose retries are spent; tags subscription, kind. */
    const val PARKED_NOTICES: String = "wow.subscription.parked.notices"

    /** Timer: how late a timer fires after its due time; tag context. */
    const val TIMER_DELAY: String = "wow.timer.delay"

    /** Timer: a query, from its admission to its end; tags context, aggregate, query.type, outcome. */
    const val QUERY: String = "wow.query"

    /** Gauge: a subscription's watermark in a partition of the log; tags group, subscription, partition. */
    const val POSITION: String = "wow.subscription.position"

    /** Gauge: the entries of a partition of the log after a subscription's watermark; tags as [POSITION]. */
    const val BACKLOG: String = "wow.subscription.backlog"

    /**
     * Gauge: how far a subscription has come in a partition of the log, its watermark over the log's head, from 0 to
     * 1: a rebuild's progress; tags as [POSITION].
     */
    const val PROGRESS: String = "wow.subscription.progress"

    const val CONTEXT: String = "context"
    const val AGGREGATE: String = "aggregate"
    const val SUBSCRIPTION: String = "subscription"
    const val OUTCOME: String = "outcome"
    const val KIND: String = "kind"
    const val QUERY_TYPE: String = "query.type"
    const val GROUP: String = "group"
    const val PARTITION: String = "partition"
}
