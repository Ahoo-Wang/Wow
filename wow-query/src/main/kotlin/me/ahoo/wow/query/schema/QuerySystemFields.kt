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
package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import java.util.Collections

object QuerySystemFields {
    fun fields(documentKind: QueryDocumentKind): List<QueryFieldSchema> = when (documentKind) {
        QueryDocumentKind.SNAPSHOT -> SNAPSHOT_FIELDS
        QueryDocumentKind.EVENT_STREAM -> EVENT_STREAM_FIELDS
    }

    private fun string(path: String, sortable: Boolean = false): QueryFieldSchema = QueryFieldSchema(
        path = LogicalField(path),
        valueKind = QueryFieldValueKind.STRING,
        nullable = false,
        sortable = sortable,
        system = true
    )

    private fun integer(path: String, sortable: Boolean = true): QueryFieldSchema = QueryFieldSchema(
        path = LogicalField(path),
        valueKind = QueryFieldValueKind.INTEGER,
        nullable = false,
        sortable = sortable,
        system = true
    )

    private fun time(path: String): QueryFieldSchema = QueryFieldSchema(
        path = LogicalField(path),
        valueKind = QueryFieldValueKind.TIME,
        nullable = false,
        system = true
    )

    private fun boolean(path: String): QueryFieldSchema = QueryFieldSchema(
        path = LogicalField(path),
        valueKind = QueryFieldValueKind.BOOLEAN,
        nullable = false,
        system = true
    )

    private fun map(path: String): QueryFieldSchema = QueryFieldSchema(
        path = LogicalField(path),
        valueKind = QueryFieldValueKind.MAP,
        nullable = false,
        queryable = false,
        system = true
    )

    private val COMMON_FIELDS = listOf(
        string("contextName"),
        string("aggregateName"),
        string("tenantId"),
        string("ownerId"),
        string("spaceId"),
        integer("version")
    )

    private val SNAPSHOT_FIELDS = immutableList {
        addAll(COMMON_FIELDS)
        add(string("id", sortable = true))
        add(string("eventId"))
        add(string("firstOperator"))
        add(string("operator"))
        add(time("firstEventTime"))
        add(time("eventTime"))
        add(time("snapshotTime"))
        add(map("tags"))
        add(boolean("deleted"))
        add(
            QueryFieldSchema(
                path = LogicalField("state"),
                valueKind = QueryFieldValueKind.OBJECT,
                nullable = false,
                queryable = false,
                operators = emptySet(),
                system = true,
            )
        )
    }

    private val EVENT_STREAM_FIELDS = immutableList {
        addAll(COMMON_FIELDS)
        add(string("id", sortable = true))
        add(string("aggregateId", sortable = true))
        add(string("commandId"))
        add(string("requestId"))
        add(time("createTime"))
        add(map("header"))
        add(
            QueryFieldSchema(
                path = LogicalField("body"),
                valueKind = QueryFieldValueKind.OBJECT,
                nullable = false,
                collectionKind = QueryCollectionKind.OBJECT,
                elementMatchEnabled = false,
                system = true,
            )
        )
        add(string("body.id"))
        add(string("body.name"))
        add(string("body.revision"))
        add(string("body.bodyType"))
    }

    private fun immutableList(builder: MutableList<QueryFieldSchema>.() -> Unit): List<QueryFieldSchema> {
        val fields = mutableListOf<QueryFieldSchema>()
        fields.builder()
        return Collections.unmodifiableList(ArrayList(fields))
    }
}
