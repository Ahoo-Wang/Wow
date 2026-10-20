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

package me.ahoo.wow.datasetregistry.api

import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import tools.jackson.databind.node.ObjectNode

/**
 * Drafts the dataset [id] (`{context}.{dataset}`) from [table]: the server reads the table's columns and declares
 * what they prove (types, nullability, dates, string arrays), suggests the scope and identity from conventional
 * column names, and leaves sensitivity to the review.
 */
@Order(1)
@Summary("Draft a dataset from a table")
@CreateAggregate
@CommandRoute(action = "draft", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class DraftDataset(
    @CommandRoute.PathVariable
    @AggregateId
    val id: String,
    /** `database.table`, or a table of the source's default database. */
    val table: String,
    /**
     * The named source the table lives in, such as a ClickHouse server of `wow.query.clickhouse.sources`; the default
     * one when `null`.
     */
    val source: String? = null,
)

/** A column the draft leaves out because a dataset cannot declare its type. */
data class SkippedColumn(val name: String, val type: String)

data class DatasetDrafted(
    val table: String,
    /** The declaration, in the format of a dataset file, without any `sensitive` key. */
    val document: ObjectNode,
    val skipped: List<SkippedColumn> = emptyList(),
)

/**
 * Replaces the draft's declaration with [document], in the format of a dataset file. Sensitivity is not part of it:
 * it is reviewed field by field ([ReviewSensitivity]). A field whose declaration is unchanged keeps its review.
 */
@Order(2)
@Summary("Revise a dataset's draft")
@CommandRoute(action = "revise", method = CommandRoute.Method.PUT, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class ReviseDataset(
    @CommandRoute.PathVariable
    @AggregateId
    val id: String,
    val document: ObjectNode,
)

data class DatasetRevised(
    val document: ObjectNode,
    /** The reviews the revision keeps: those of the fields whose declaration it did not change. */
    val reviews: Map<String, FieldSensitivity>,
)

/**
 * How one field is protected, as a reviewer decided: [level] `null` is the decision that the field is not
 * sensitive, which is a decision too.
 */
data class FieldSensitivity(
    val level: SensitivityLevel? = null,
    val keepPrefix: Int = 0,
    val keepSuffix: Int = 0,
)

/** Records the reviewer's decision on each of [fields]: sensitive at a level, or not sensitive. */
@Order(3)
@Summary("Review the sensitivity of a dataset's fields")
@CommandRoute(action = "sensitivity", method = CommandRoute.Method.PUT, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class ReviewSensitivity(
    @CommandRoute.PathVariable
    @AggregateId
    val id: String,
    val fields: Map<String, FieldSensitivity>,
)

data class SensitivityReviewed(val fields: Map<String, FieldSensitivity>)

/**
 * Publishes the draft as the dataset's next version. Refused until the declaration names its scope, every string
 * field's sensitivity is reviewed, and the declaration compiles against its table.
 */
@Order(4)
@Summary("Publish a dataset")
@CommandRoute(action = "publish", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class PublishDataset(
    @CommandRoute.PathVariable
    @AggregateId
    val id: String,
)

data class DatasetPublished(
    val version: Int,
    /** The declaration served, with each sensitive field's `sensitive` key. */
    val document: ObjectNode,
)

/** Stops serving the dataset. Its draft and history stay; publishing again serves it again. */
@Order(5)
@Summary("Retire a dataset")
@CommandRoute(action = "retire", method = CommandRoute.Method.POST, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class RetireDataset(
    @CommandRoute.PathVariable
    @AggregateId
    val id: String,
)

data class DatasetRetired(val version: Int)
