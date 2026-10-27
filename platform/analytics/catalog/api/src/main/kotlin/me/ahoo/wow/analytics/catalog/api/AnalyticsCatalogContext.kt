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

package me.ahoo.wow.analytics.catalog.api

/**
 * The analytics catalog context: one service, centrally managed, holds the warehouse's data
 * sources and, built on them, the wide tables a dataset serves. Its `source` aggregate is one physical table or view of
 * the warehouse, identified by (connection, database, table) ([sourceIdOf]): a table the Wow ClickHouse Sink keeps,
 * described by its trusted `wow_catalog` row, or a table an analyst provides, drafted from `system.columns` and
 * reviewed. Its `dataset` aggregate is a wide table over the sources (-2), identified by the name
 * queries read it by ([datasetIdOf]).
 */
object AnalyticsCatalogContext {
    const val NAME: String = "analytics-catalog"

    /** The one tenant every source is in: the platform's (the aggregate type's fixed tenant). */
    const val TENANT_ID: String = "(platform)"

    /** The id of the source of [database]'s [table] on the warehouse [connection]: `<connection>:<database>:<table>`. */
    fun sourceIdOf(connection: String, database: String, table: String): String = "$connection:$database:$table"

    /** The id of the dataset [name] of [context]: `<context>.<name>`, the name its queries read it by. */
    fun datasetIdOf(context: String, name: String): String = "$context.$name"
}
