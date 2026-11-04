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

import org.bson.Document

/**
 * How the MongoDB sources count 9.x tags ([MongoNineXEventSource.tags], [MongoNineXSnapshotSource.tags]) in the store,
 * the same way [TagValueStats.of] counts one record's: a key's values are its array without nulls (a lone value as
 * one, null as none); an integer is a string of digits, signed or not; a boolean is `true` or `false` in any case.
 */
internal object MongoTags {
    private const val INTEGER = "^[+-]?[0-9]+$"
    private const val BOOLEAN = "^(true|false)$"
    private const val VALUES = "\$values"

    /**
     * The aggregation stages that count the tags object [tags] (a field expression, `$tags`) of each document: one
     * document per key, its `_id`, with `count`, `multiple`, `notInteger` and `notBoolean` ([stats] reads them).
     */
    fun counts(tags: String): List<Document> = listOf(
        Document("\$project", Document("kv", Document("\$objectToArray", tags))),
        Document("\$unwind", "\$kv"),
        Document("\$project", Document("key", "\$kv.k").append("values", values("\$kv.v"))),
        Document(
            "\$group",
            Document("_id", "\$key")
                .append("count", Document("\$sum", 1))
                .append("multiple", counted(TagValueProblem.MULTIPLE))
                .append("notInteger", counted(TagValueProblem.NOT_INTEGER))
                .append("notBoolean", counted(TagValueProblem.NOT_BOOLEAN)),
        ),
    )

    /** The counts [counts] wrote, by tag key. */
    fun stats(documents: List<Document>): Map<String, TagValueStats> = documents.associate { document ->
        document.getString("_id") to TagValueStats(
            count = document.long("count"),
            multiple = document.long("multiple"),
            notInteger = document.long("notInteger"),
            notBoolean = document.long("notBoolean"),
        )
    }

    /**
     * The aggregation stages that keep the documents whose tags object at [tags] (a field path, `tags`, or an
     * unwound stream event's `body.body.tags`) holds, for [key], the values [problem] names, read as [counts] reads
     * them; null for a key a field path cannot name (with a dot, or starting with `$`), whose samples the check then
     * leaves out.
     */
    fun holding(tags: String, key: String, problem: TagValueProblem): List<Document>? {
        if ('.' in key || key.startsWith('$')) {
            return null
        }
        return listOf(
            Document("\$match", Document(tags, Document("\$type", "object"))),
            Document("\$project", Document("values", values("\$$tags.$key"))),
            Document("\$match", Document("\$expr", condition(problem))),
        )
    }

    /** A key's values: the array [value] without nulls, a lone value as one, null as none. */
    private fun values(value: String): Document = Document(
        "\$filter",
        Document(
            "input",
            Document(
                "\$cond",
                listOf(
                    Document("\$isArray", value),
                    value,
                    Document(
                        "\$cond",
                        listOf(
                            Document("\$eq", listOf(Document("\$ifNull", listOf(value, null)), null)),
                            emptyList<Any>(),
                            listOf(value)
                        )
                    ),
                ),
            ),
        ).append("cond", Document("\$ne", listOf("\$\$this", null))),
    )

    /** Whether a key's values, `$values`, hold [problem], as [TagValues.holds] decides it. */
    private fun condition(problem: TagValueProblem): Document = when (problem) {
        TagValueProblem.MULTIPLE ->
            Document("\$gt", listOf(Document("\$size", Document("\$setUnion", listOf(VALUES, emptyList<Any>()))), 1))

        TagValueProblem.NOT_INTEGER -> any(INTEGER, options = "")
        TagValueProblem.NOT_BOOLEAN -> any(BOOLEAN, options = "i")
    }

    /** Whether any of a key's values, as strings, does not match [regex]. */
    private fun any(regex: String, options: String): Document = Document(
        "\$anyElementTrue",
        listOf(
            Document(
                "\$map",
                Document("input", VALUES).append(
                    "in",
                    Document(
                        "\$not",
                        listOf(
                            Document(
                                "\$regexMatch",
                                Document(
                                    "input",
                                    Document("\$toString", "\$\$this")
                                ).append("regex", regex).append("options", options)
                            )
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun counted(problem: TagValueProblem): Document =
        Document("\$sum", Document("\$cond", listOf(condition(problem), 1, 0)))

    private fun Document.long(field: String): Long = (get(field) as Number).toLong()
}
