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

package me.ahoo.wow.analytics.catalog.api.dataset

import me.ahoo.wow.analytics.catalog.api.source.SourceColumn

/**
 * One compiled version of a dataset (decision D259 7.2b-2): the ClickHouse [view] of [database] that serves it, the
 * [ddl] that creates it (audited with the publication, decision D251 ④), and what a query node needs to serve it
 * without asking the catalog: its [scope], its [fields] with their protection and lineage, its [identity] and
 * [eventTime] columns. [sources] are the catalog's sources it reads; [approvals] say why publishing it takes an
 * approver, empty when it does not. [columns] are the view's columns as the warehouse created them, their warehouse
 * types: what a query node declares each field as; empty until the publication creates the view. [select] is the
 * query the view is, which the catalog asks the warehouse to describe before it publishes a recompilation.
 */
data class DatasetArtifact(
    val context: String,
    val name: String,
    val version: Int,
    val database: String,
    val view: String,
    val ddl: String,
    val scope: DatasetScope,
    val fields: List<DatasetField>,
    val sources: List<String>,
    val title: String? = null,
    val identity: String? = null,
    val eventTime: String? = null,
    val approvals: List<String> = emptyList(),
    val columns: List<SourceColumn> = emptyList(),
    val select: String = "",
)

/**
 * The columns of a dataset's view that hold its rows' scope: [tenant], [owner] and [space], from its subject; none
 * for a [platform] dataset, whose rows every tenant shares.
 */
data class DatasetScope(
    val tenant: String? = null,
    val owner: String? = null,
    val space: String? = null,
    val platform: Boolean = false,
)

/**
 * A column of a dataset's view: its [name] and [title], the [sensitivity] its lineage gives it (null when it is not
 * sensitive), the values it reads ([inputs], each `<source id>/<column>[.<path>]`), whether it is [computed], and the
 * aggregate its value is the id of ([reference], decision D238 ⑤), as the source it is read from says: a row's action
 * on that aggregate goes to the service that owns it.
 */
data class DatasetField(
    val name: String,
    val title: String? = null,
    val sensitivity: DatasetSensitivity? = null,
    val inputs: List<String> = emptyList(),
    val computed: Boolean = false,
    val reference: DatasetReference? = null,
)

/** The aggregate a dataset's value is the id of: [aggregate] of the bounded context [context]. */
data class DatasetReference(val context: String, val aggregate: String)
