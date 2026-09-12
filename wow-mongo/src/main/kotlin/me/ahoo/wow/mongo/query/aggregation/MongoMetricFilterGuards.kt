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

package me.ahoo.wow.mongo.query.aggregation

import me.ahoo.wow.query.schema.QuerySchemaValidationException
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonRegularExpression
import org.bson.BsonValue
import org.bson.Document
import org.bson.conversions.Bson

/**
 * MongoDB evaluates a match document in expression position as a truthy object literal,
 * so a `$cond` guard must re-express the compiled predicate with aggregation operators.
 * The translation covers every shape [me.ahoo.wow.mongo.query.AbstractMongoFilterCompiler]
 * emits — [me.ahoo.wow.query.schema.requireScalarMetricFilterFields] has already rejected
 * the filters it cannot express — and preserves its null-versus-missing match semantics.
 */
internal fun Bson.toGuardCondition(): Any = toGuardCondition(toBsonDocument())

private fun toGuardCondition(document: BsonDocument): Any =
    if (document.size == 0) {
        Document("\$literal", true)
    } else {
        toGuardCondition(document.entries.single())
    }

private fun toGuardCondition(entry: Map.Entry<String, BsonValue>): Any {
    val path = entry.key
    val condition = entry.value
    return when {
        path == "\$and" || path == "\$or" ->
            Document(path, condition.asArray().map { toGuardCondition(it.asDocument()) })

        path == "\$nor" -> Document(
            "\$not",
            listOf(
                Document(
                    "\$or",
                    condition.asArray().map { toGuardCondition(it.asDocument()) },
                ),
            ),
        )

        else -> toGuardCondition(path, condition)
    }
}

private fun toGuardCondition(path: String, condition: BsonValue): Any {
    if (condition.isNull) {
        return matchesNull(path)
    }
    if (condition.isRegularExpression) {
        return regexGuard(path, condition.asRegularExpression())
    }
    if (!condition.isDocument) {
        return Document("\$eq", listOf(fieldRef(path), condition.literalOperand()))
    }
    return condition.asDocument().entries.single().let { (operator, value) -> toGuardCondition(path, operator, value) }
}

private fun toGuardCondition(path: String, operator: String, value: BsonValue): Any = when (operator) {
    "\$ne" -> if (value.isNull) {
        Document("\$and", listOf(Document("\$ne", listOf(fieldRef(path), null)), isPresent(path)))
    } else {
        Document("\$ne", listOf(fieldRef(path), value.literalOperand()))
    }

    "\$gt", "\$gte", "\$lt", "\$lte" -> Document(operator, listOf(fieldRef(path), value.literalOperand()))
    "\$in" -> inGuard(path, value.asArray())
    "\$nin" -> Document("\$not", listOf(inGuard(path, value.asArray())))
    "\$exists" -> if (value.asBoolean().value) {
        isPresent(path)
    } else {
        Document("\$eq", listOf(typeOf(path), "missing"))
    }

    else -> throw QuerySchemaValidationException(
        "MongoDB metric filters cannot translate operator [$operator] into a guard condition.",
    )
}

private fun matchesNull(path: String): Any = Document(
    "\$or",
    listOf(
        Document("\$eq", listOf(fieldRef(path), null)),
        Document("\$eq", listOf(typeOf(path), "missing")),
    ),
)

/**
 * A string operand starting with `$` would be parsed as a field reference in expression
 * position, so such operands are pinned with `$literal`; every other operand keeps its shape.
 */
private fun BsonValue.literalOperand(): BsonValue =
    if (isString && asString().value.startsWith("$")) {
        BsonDocument("\$literal", this)
    } else {
        this
    }

private fun inGuard(path: String, values: BsonArray): Any = Document(
    "\$in",
    listOf(fieldRef(path), BsonArray(values.map { it.literalOperand() })),
)

/**
 * `$regexMatch` errors on non-string input while `$match` regex semantics never match one,
 * so the guard applies only to string values; null and missing inputs never match.
 */
private fun regexGuard(path: String, regex: BsonRegularExpression): Document {
    val input = fieldRef(path)
    val condition = Document("input", input).append("regex", regex.pattern)
    regex.options?.takeIf { it.isNotEmpty() }?.let { condition.append("options", it) }
    return Document(
        "\$cond",
        listOf(
            Document("\$eq", listOf(typeOf(path), "string")),
            Document("\$regexMatch", condition),
            false,
        ),
    )
}

private fun fieldRef(path: String): String = "\$$path"

private fun typeOf(path: String): Document = Document("\$type", fieldRef(path))

private fun isPresent(path: String): Document = Document("\$ne", listOf(typeOf(path), "missing"))
