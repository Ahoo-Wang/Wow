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

package me.ahoo.wow.bi.renderer

import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.quoteIdentifier
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.stringLiteral
import java.util.Collections

internal fun ClickHouseRenderContext.drop(database: String, table: String): String =
    "DROP TABLE IF EXISTS ${qualified(database, table)}${scopeClause()} SYNC;"

internal fun ClickHouseRenderContext.dropView(database: String, view: String): String =
    "DROP VIEW IF EXISTS ${qualified(database, view)}${scopeClause()} SYNC;"

internal fun storageTable(table: String): String = "${table}_store"

internal fun immutableStatements(vararg statements: String): List<String> =
    immutableStatements(statements.asList())

internal fun immutableStatements(statements: Collection<String>): List<String> =
    Collections.unmodifiableList(ArrayList(statements))

internal fun qualified(database: String, table: String): String =
    "${identifier(database)}.${identifier(table)}"

internal fun identifier(value: String): String = quoteIdentifier(value)

internal fun literal(value: String): String = stringLiteral(value)

internal fun String.withTableComment(comment: String): String =
    removeSuffix(";") + "\nCOMMENT $comment;"

internal fun ClickHouseRenderContext.scopeClause(): String =
    topology.scopeClause.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()

internal val ClickHouseRenderContext.tableCreateClause: String
    get() = when (catalogMutationMode) {
        CatalogMutationMode.RECONCILE -> "CREATE TABLE IF NOT EXISTS"
        CatalogMutationMode.CREATE_ONLY -> "CREATE TABLE"
    }

internal val ClickHouseRenderContext.materializedViewCreateClause: String
    get() = when (catalogMutationMode) {
        CatalogMutationMode.RECONCILE -> "CREATE MATERIALIZED VIEW IF NOT EXISTS"
        CatalogMutationMode.CREATE_ONLY -> "CREATE MATERIALIZED VIEW"
    }

internal val ClickHouseRenderContext.viewCreateClause: String
    get() = when (catalogMutationMode) {
        CatalogMutationMode.RECONCILE -> "CREATE OR REPLACE VIEW"
        CatalogMutationMode.CREATE_ONLY -> "CREATE VIEW"
    }
