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

/**
 * The fields of a 9.x MongoDB event-store document as 9.x's `wow-mongo` writes them, those the tool names in more
 * than one place: an event stream's (`<aggregate>_event_stream`, [MongoNineXEventSource], [MongoNineXEventTarget],
 * [MongoNineXInventory]) with the fields of each event in its [BODY], and those its snapshot (`<aggregate>_snapshot`,
 * [MongoNineXSnapshotSource]) shares. A pipeline names a field from these (`"\$$CONTEXT_NAME"`), as the
 * documents are written.
 */
internal object NineXMongoFields {
    const val ID = "_id"
    const val CONTEXT_NAME = "contextName"
    const val AGGREGATE_NAME = "aggregateName"
    const val AGGREGATE_ID = "aggregateId"
    const val TENANT_ID = "tenantId"
    const val VERSION = "version"
    const val CREATE_TIME = "createTime"
    const val COMMAND_ID = "commandId"
    const val HEADER = "header"
    const val BODY = "body"
    const val BODY_TYPE = "bodyType"

    /** An event's name, in [BODY]. */
    const val EVENT_NAME = "name"

    /** An event's revision, in [BODY]. */
    const val EVENT_REVISION = "revision"
}
