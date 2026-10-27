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

package me.ahoo.wow.spi.json.query

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The names of a snapshot record's fields as the query side reads them: one record per aggregate, the
 * columns of `me.ahoo.wow.spi.snapshot.SnapshotRecord` with the state under [STATE]. 9.x's `contextName`,
 * `aggregateName`, `eventId` and `snapshotTime` are gone, and 9.x's `eventTime` is [LAST_EVENT_TIME]: a snapshot query
 * is always of one aggregate type, which the backend adds itself. [OPERATOR] and [FIRST_OPERATOR] keep
 * 9.x's names but are objects, `{id, tenantId, crossScope}` ([OperatorFields]), or null for an
 * anonymous caller: filter on `operator.id`.
 */
@InternalWowApi
object SnapshotFields {
    const val TENANT_ID: String = "tenantId"
    const val AGGREGATE_ID: String = "aggregateId"
    const val VERSION: String = "version"
    const val OWNER_ID: String = "ownerId"
    const val SPACE_ID: String = "spaceId"
    const val DELETED: String = "deleted"
    const val TAGS: String = "tags"
    const val FIRST_EVENT_TIME: String = "firstEventTime"
    const val LAST_EVENT_TIME: String = "lastEventTime"
    const val OPERATOR: String = "operator"
    const val FIRST_OPERATOR: String = "firstOperator"

    /** The schedules the aggregate keeps: each rule, `{type, ...}`, by its key. */
    const val SCHEDULES: String = "schedules"
    const val STATE: String = "state"
}

/** The properties of an operator system field ([SnapshotFields.OPERATOR], [SnapshotFields.FIRST_OPERATOR]). */
@InternalWowApi
object OperatorFields {
    const val ID: String = "id"
    const val TENANT_ID: String = "tenantId"
    const val CROSS_SCOPE: String = "crossScope"
}

/**
 * The names of an event stream record's fields: one record per stream, the commit of one command at
 * one version, as `me.ahoo.wow.spi.log.EventStreamRecord` holds it. [OPERATOR] is `{id, tenantId, crossScope}`, or
 * null for an anonymous caller; [TAGS] the tags after the commit, or null when not recorded;
 * [EVENTS] holds each event as `{name, revision, body}` ([EventFields]).
 */
@InternalWowApi
object EventStreamFields {
    const val AGGREGATE_ID: String = "aggregateId"
    const val TENANT_ID: String = "tenantId"
    const val VERSION: String = "version"
    const val COMMIT_TIME: String = "commitTime"
    const val COMMAND_ID: String = "commandId"
    const val REQUEST_ID: String = "requestId"
    const val COMMAND_TYPE: String = "commandType"
    const val OWNER_ID: String = "ownerId"
    const val SPACE_ID: String = "spaceId"
    const val OPERATOR: String = "operator"

    /**
     * The aggregate's tags after the stream's commit, `{key: [values]}`: null when the log did not
     * record them (a stream appended before format 1 learned them, until `migrate-data` backfills it).
     */
    const val TAGS: String = "tags"
    const val EVENTS: String = "events"
}

/** The properties of one event of [EventStreamFields.EVENTS]. */
@InternalWowApi
object EventFields {
    /** The event's name, `<context>.<aggregate>.<Name>`, or a system event's (`wow.ScheduleSet`). */
    const val NAME: String = "name"

    /** The revision of the event's schema. */
    const val REVISION: String = "revision"

    /** The event's payload. */
    const val BODY: String = "body"
}
