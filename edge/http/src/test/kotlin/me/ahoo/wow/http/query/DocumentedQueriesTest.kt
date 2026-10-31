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

package me.ahoo.wow.http.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.json.QueryJson
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.io.File

/**
 * The query examples of the user documentation, the skills and the READMEs, read as the query route reads a body
 * ([QueryBodies]): a page's example that the route would refuse as malformed fails here, so a renamed operator or a
 * casing the wire does not take (`"op": "EQ"` for `eq`) does not stay on a page.
 *
 * An example is a JSON object in a fenced block, an inline code span or a `curl -d '…'` body: a query body, which
 * names its `kind`, or a filter, which names its `op` and is read as a list's. A snippet that is not JSON (one with
 * `…` in it) is not read, so the casing of `op`, `direction` and `kind` is checked in every code span besides. The
 * migration pages show 9.x queries on purpose and are left out.
 */
class DocumentedQueriesTest {
    private val root: File = generateSequence(File("").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val pages: List<File> = (
        listOf("documentation/docs", "skills").flatMap { dir -> File(root, dir).walk().filter { it.extension == "md" } } +
            listOf("examples", "platform", "typescript").flatMap { dir ->
                File(root, dir).walk().onEnter { it.name != "node_modules" && it.name != "build" }
                    .filter { it.name == "README.md" }
            }
        ).filterNot { page -> MIGRATION.any { it in page.invariantSeparatorsPath } }.toList()

    @Test
    fun `every documented query example should read as the query route reads it`() {
        pages.assert().isNotEmpty()
        val refused = pages.flatMap { page ->
            examples(page.readText()).mapNotNull { json -> refusal(json)?.let { "${relative(page)}: $it in $json" } }
        }
        refused.assert().isEmpty()
    }

    @Test
    fun `no code on a page should name an operator, a direction or a kind in another casing than the wire's`() {
        val cased = pages.flatMap { page ->
            codeOf(page.readText()).flatMap { code ->
                UPPER_CASED.findAll(code).map { "${relative(page)}: ${it.value}" }.toList()
            }
        }
        cased.assert().isEmpty()
    }

    @Test
    fun `a query in another casing should be caught by both checks`() {
        val page = "```json\n{\"kind\": \"list\", \"filter\": {\"op\": \"EQ\", \"field\": \"a\", \"value\": 1}}\n```\n" +
            "Or `{\"op\":\"MATCH_ALL\"}`, and `curl -d '{\"kind\":\"count\",\"filter\":{\"op\":\"match-all\"}}'`."
        val found = examples(page)
        found.assert().hasSize(3)
        refusal(found[0]).assert().contains("unknown-type")
        refusal(found[1]).assert().contains("unknown-type")
        refusal(found[2]).assert().isNull()
        codeOf(page).flatMap { code -> UPPER_CASED.findAll(code).map { it.value }.toList() }
            .assert().containsExactly("\"op\": \"EQ\"", "\"op\":\"MATCH_ALL\"")
    }

    /** Why the route would refuse [json] as malformed, or null when it reads it. */
    private fun refusal(json: ObjectNode): String? {
        val body = json.takeIf { it.has(QueryBodies.KIND) } ?: asList(json)
        return try {
            QueryBodies.read(QueryJson.mapper.writeValueAsBytes(body))
            null
        } catch (refused: QueryRequestException) {
            "${refused.code} ${refused.errorMsg}"
        }
    }

    /** A filter [filter] as the body of a list query. */
    private fun asList(filter: ObjectNode): ObjectNode =
        filter.objectNode().put(QueryBodies.KIND, "list").set("filter", filter)

    /** The query examples of [page]: the JSON objects of its code that are a query body or a filter. */
    private fun examples(page: String): List<ObjectNode> = codeOf(page).flatMap { code ->
        (listOf(code) + CURL_BODY.findAll(code).map { it.groupValues[1] }).mapNotNull(::parsed)
    }.filter { json -> json.isQuery() || json.isFilter() }

    private fun parsed(text: String): ObjectNode? = text.trim().takeIf { it.startsWith("{") }?.let {
        try {
            QueryJson.mapper.readTree(it) as? ObjectNode
        } catch (_: JacksonException) {
            null
        }
    }

    private fun JsonNode.isQuery(): Boolean =
        get(QueryBodies.KIND)?.isString == true && QUERY_PROPERTIES.any { has(it) }

    private fun JsonNode.isFilter(): Boolean = !has(QueryBodies.KIND) && get("op")?.isString == true

    /** The fenced blocks and inline code spans of [page]. */
    private fun codeOf(page: String): List<String> {
        val fenced = FENCED.findAll(page).map { it.groupValues[1] }.toList()
        val prose = FENCED.replace(page, "")
        return fenced + INLINE.findAll(prose).map { it.groupValues[1] }
    }

    private fun relative(page: File): String = page.relativeTo(root).invariantSeparatorsPath

    private companion object {
        /** The pages that show 9.x queries, whose casing was upper. */
        val MIGRATION = listOf("/migrate/", "/wow-migrate/")
        val QUERY_PROPERTIES =
            listOf("filter", "pagination", "limit", "sort", "projection", "groupBy", "metrics", "cursor")
        val FENCED = Regex("```[^\\n]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val INLINE = Regex("`([^`\\n]+)`")
        val CURL_BODY = Regex("-d\\s+'(\\{.*?\\})'", RegexOption.DOT_MATCHES_ALL)

        /** An `op`, a `direction` or a query `kind` written in upper case, as 9.x wrote them. */
        val UPPER_CASED = Regex(
            "\"op\"\\s*:\\s*\"[A-Z][A-Z_]*\"|\"direction\"\\s*:\\s*\"(?:ASC|DESC)\"|" +
                "\"kind\"\\s*:\\s*\"(?:LIST|PAGED|CURSOR|SINGLE|COUNT|AGGREGATE)\"",
        )
    }
}
