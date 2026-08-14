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

package me.ahoo.wow.query.validation

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.query.expression.RelativeTimeExpressionNormalizer
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchemaView
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

class QueryExpressionValidator(
    private val limits: QueryStructureLimits
) {
    fun <E : QueryExpression> validateStructure(expression: E): E {
        val pending = ArrayDeque<StructureFrame>()
        pending.addLast(StructureFrame(expression, 1))
        var reservedNodes = 1L
        var membershipItems = 0L
        var nativeParameterBytes = 0L
        while (pending.isNotEmpty()) {
            val frame = pending.removeLast()
            if (frame.depth > limits.maxDepth) {
                invalidQuery()
            }
            when (val current = frame.expression) {
                MatchAll,
                MatchNone -> Unit

                is LogicalExpression -> {
                    reservedNodes = reserveNodes(reservedNodes, current.operands.size)
                    current.operands.forEach { child -> pending.addChild(child, frame.depth) }
                }

                is PortableLogicalExpression -> {
                    reservedNodes = reserveNodes(reservedNodes, current.operands.size)
                    current.operands.forEach { child -> pending.addChild(child, frame.depth) }
                }

                is PredicateExpression -> {
                    validateArity(current)
                    if (current.operator.isMembership()) {
                        membershipItems = addWithin(
                            membershipItems,
                            current.values.size.toLong(),
                            limits.maxMembershipItems.toLong()
                        )
                    }
                }

                is ElementMatchExpression -> {
                    reservedNodes = reserveNodes(reservedNodes, 1)
                    pending.addChild(current.predicate, frame.depth)
                }
                is RelativeTimeExpression -> validateRelativeTime(current)
                is FullTextExpression -> Unit
                is NativeExpression -> {
                    nativeParameterBytes = addWithin(
                        nativeParameterBytes,
                        estimateNativeParameters(current.parameters, limits.maxNativeParameterBytes - nativeParameterBytes),
                        limits.maxNativeParameterBytes
                    )
                }
            }
        }
        return expression
    }

    fun <E : QueryExpression> validateSchema(expression: E, schema: QuerySchemaView): E {
        val pending = ArrayDeque<SchemaFrame>()
        pending.addLast(SchemaFrame(expression, null))
        while (pending.isNotEmpty()) {
            validateSchemaFrame(pending.removeLast(), schema, pending)
        }
        return expression
    }

    private fun validateSchemaFrame(
        frame: SchemaFrame,
        schema: QuerySchemaView,
        pending: ArrayDeque<SchemaFrame>
    ) {
        when (val current = frame.expression) {
            MatchAll,
            MatchNone -> Unit

            is LogicalExpression -> pending.addChildren(current.operands, frame.relativeTo)
            is PortableLogicalExpression -> pending.addChildren(current.operands, frame.relativeTo)
            is PredicateExpression -> validatePredicate(current, frame.relativeTo, schema)
            is ElementMatchExpression -> validateElementMatch(current, frame.relativeTo, schema, pending)
            is RelativeTimeExpression -> invalidQuery()
            is FullTextExpression -> validateCapabilityFields(current.capabilityId, current.fields, schema)
            is NativeExpression -> validateCapabilityFields(current.capabilityId, current.declaredFields, schema)
        }
    }

    private fun validateElementMatch(
        expression: ElementMatchExpression,
        relativeTo: LogicalField?,
        schema: QuerySchemaView,
        pending: ArrayDeque<SchemaFrame>
    ) {
        val field = schema.field(resolvePath(relativeTo, expression.field)) ?: invalidQuery()
        if (!field.supportsElementMatch()) {
            invalidQuery()
        }
        pending.addLast(SchemaFrame(expression.predicate, field.path))
    }

    private fun validateRelativeTime(expression: RelativeTimeExpression) {
        try {
            LogicalField(expression.field)
            RelativeTimeExpressionNormalizer.validate(expression)
        } catch (_: QueryException) {
            invalidQuery()
        } catch (_: RuntimeException) {
            invalidQuery()
        }
    }

    private fun QueryFieldSchema.supportsElementMatch(): Boolean =
        collectionKind == QueryCollectionKind.OBJECT && valueKind == QueryFieldValueKind.OBJECT &&
            queryable && elementMatchEnabled

    private fun validateArity(predicate: PredicateExpression) {
        val size = predicate.values.size
        when (predicate.operator) {
            PortableOperator.EQ,
            PortableOperator.NE,
            PortableOperator.GT,
            PortableOperator.LT,
            PortableOperator.GTE,
            PortableOperator.LTE,
            PortableOperator.CONTAINS,
            PortableOperator.STARTS_WITH,
            PortableOperator.ENDS_WITH -> if (size != 1) invalidQuery()

            PortableOperator.EXISTS -> if (
                size != 1 || predicate.values.single() !is QueryValue.BooleanValue
            ) {
                invalidQuery()
            }

            PortableOperator.BETWEEN -> if (size != 2) invalidQuery()
            PortableOperator.IN,
            PortableOperator.NOT_IN,
            PortableOperator.ALL_IN -> if (size < 1) invalidQuery()

            PortableOperator.NULL,
            PortableOperator.NOT_NULL,
            PortableOperator.TRUE,
            PortableOperator.FALSE,
            PortableOperator.EMPTY_COLLECTION -> if (size != 0) invalidQuery()
        }
    }

    private fun validatePredicate(
        predicate: PredicateExpression,
        relativeTo: LogicalField?,
        schema: QuerySchemaView
    ) {
        val field = schema.field(resolvePath(relativeTo, predicate.field)) ?: invalidQuery()
        if (!field.queryable || predicate.operator !in field.operators) {
            invalidQuery()
        }
        if (
            predicate.operator.isStringMatching() &&
            predicate.stringComparison !in (field.stringOptions?.comparisonModes ?: emptySet())
        ) {
            invalidQuery()
        }
        if (predicate.operator == PortableOperator.EXISTS) {
            return
        }
        predicate.values.forEach { value -> validateValue(value, predicate.operator, field) }
    }

    private fun validateValue(value: QueryValue, operator: PortableOperator, field: QueryFieldSchema) {
        if (value === QueryValue.NullValue) {
            if (!field.nullable || operator !in NULL_VALUE_OPERATORS) {
                invalidQuery()
            }
            return
        }
        if (!value.isCompatibleWith(field.valueKind)) {
            invalidQuery()
        }
        if (value is QueryValue.StringValue && field.exceedsMaximumLength(value)) {
            invalidQuery()
        }
    }

    private fun QueryValue.isCompatibleWith(kind: QueryFieldValueKind): Boolean = when (kind) {
        QueryFieldValueKind.BOOLEAN -> this is QueryValue.BooleanValue
        QueryFieldValueKind.INTEGER -> this is QueryValue.IntegerValue
        QueryFieldValueKind.DECIMAL -> isNumericValue()
        QueryFieldValueKind.STRING -> this is QueryValue.StringValue
        QueryFieldValueKind.TIME -> this is QueryValue.InstantValue
        QueryFieldValueKind.ENUM -> this is QueryValue.EnumValue || this is QueryValue.StringValue
        QueryFieldValueKind.BINARY -> this is QueryValue.BinaryValue
        QueryFieldValueKind.OBJECT,
        QueryFieldValueKind.MAP -> false
    }

    private fun QueryValue.isNumericValue(): Boolean =
        this is QueryValue.IntegerValue || this is QueryValue.FloatingValue || this is QueryValue.DecimalValue

    private fun QueryFieldSchema.exceedsMaximumLength(value: QueryValue.StringValue): Boolean =
        valueKind == QueryFieldValueKind.STRING && stringOptions?.maxLength?.let { value.value.length > it } == true

    private fun validateCapabilityFields(
        capabilityId: me.ahoo.wow.api.query.expression.QueryCapabilityId,
        fields: Set<LogicalField>,
        schema: QuerySchemaView
    ) {
        fields.forEach { path ->
            val field = schema.field(path) ?: invalidQuery()
            if (!field.queryable || capabilityId !in field.capabilities) {
                invalidQuery()
            }
        }
    }

    private fun resolvePath(relativeTo: LogicalField?, field: LogicalField): LogicalField =
        if (relativeTo == null) field else LogicalField("${relativeTo.value}.${field.value}")

    private fun ArrayDeque<StructureFrame>.addChild(child: QueryExpression, parentDepth: Int) {
        if (parentDepth >= limits.maxDepth) {
            invalidQuery()
        }
        addLast(StructureFrame(child, parentDepth + 1))
    }

    private fun ArrayDeque<SchemaFrame>.addChildren(
        children: List<QueryExpression>,
        relativeTo: LogicalField?
    ) {
        children.forEach { child -> addLast(SchemaFrame(child, relativeTo)) }
    }

    private fun PortableOperator.isMembership(): Boolean = when (this) {
        PortableOperator.IN,
        PortableOperator.NOT_IN,
        PortableOperator.ALL_IN -> true

        PortableOperator.EQ,
        PortableOperator.NE,
        PortableOperator.GT,
        PortableOperator.LT,
        PortableOperator.GTE,
        PortableOperator.LTE,
        PortableOperator.CONTAINS,
        PortableOperator.BETWEEN,
        PortableOperator.STARTS_WITH,
        PortableOperator.ENDS_WITH,
        PortableOperator.NULL,
        PortableOperator.NOT_NULL,
        PortableOperator.TRUE,
        PortableOperator.FALSE,
        PortableOperator.EXISTS,
        PortableOperator.EMPTY_COLLECTION -> false
    }

    private fun PortableOperator.isStringMatching(): Boolean = when (this) {
        PortableOperator.CONTAINS,
        PortableOperator.STARTS_WITH,
        PortableOperator.ENDS_WITH -> true

        PortableOperator.EQ,
        PortableOperator.NE,
        PortableOperator.GT,
        PortableOperator.LT,
        PortableOperator.GTE,
        PortableOperator.LTE,
        PortableOperator.IN,
        PortableOperator.NOT_IN,
        PortableOperator.BETWEEN,
        PortableOperator.ALL_IN,
        PortableOperator.NULL,
        PortableOperator.NOT_NULL,
        PortableOperator.TRUE,
        PortableOperator.FALSE,
        PortableOperator.EXISTS,
        PortableOperator.EMPTY_COLLECTION -> false
    }

    private fun estimateNativeParameters(parameters: Map<String, QueryValue>, available: Long): Long {
        var bytes = addWithin(0, CONTAINER_COST, available)
        val pending = ArrayDeque<NativeValueFrame>()
        parameters.forEach { (key, value) ->
            bytes = addWithin(bytes, CONTAINER_ITEM_COST, available)
            bytes = addWithin(bytes, key.utf8Length(), available)
            pending.addLast(NativeValueFrame(value))
        }
        while (pending.isNotEmpty()) {
            val value = pending.removeLast().value
            bytes = addWithin(bytes, VALUE_COST, available)
            bytes = estimateNativeValue(value, bytes, available, pending)
        }
        return bytes
    }

    private fun estimateNativeValue(
        value: QueryValue,
        current: Long,
        available: Long,
        pending: ArrayDeque<NativeValueFrame>
    ): Long = when (value) {
        is QueryValue.BooleanValue -> addWithin(current, BOOLEAN_BYTES, available)
        is QueryValue.IntegerValue -> addWithin(current, INTEGER_BYTES, available)
        is QueryValue.FloatingValue -> addWithin(current, FLOATING_BYTES, available)
        is QueryValue.DecimalValue -> addWithin(current, value.value.plainStringLength(), available)
        is QueryValue.StringValue -> addWithin(current, value.value.utf8Length(), available)
        is QueryValue.InstantValue -> addWithin(
            current,
            DateTimeFormatter.ISO_INSTANT.format(value.value).utf8Length(),
            available
        )

        is QueryValue.EnumValue -> addWithin(current, value.value.utf8Length(), available)
        is QueryValue.BinaryValue -> addWithin(current, value.value.size.toLong(), available)
        is QueryValue.ListValue -> addNativeList(value.values, current, available, pending)
        is QueryValue.ObjectValue -> addNativeObject(value.values, current, available, pending)
        QueryValue.NullValue -> current
    }

    private fun addNativeList(
        values: List<QueryValue>,
        current: Long,
        available: Long,
        pending: ArrayDeque<NativeValueFrame>
    ): Long {
        var bytes = addWithin(current, CONTAINER_COST, available)
        values.forEach { child ->
            bytes = addWithin(bytes, CONTAINER_ITEM_COST, available)
            pending.addLast(NativeValueFrame(child))
        }
        return bytes
    }

    private fun addNativeObject(
        values: Map<String, QueryValue>,
        current: Long,
        available: Long,
        pending: ArrayDeque<NativeValueFrame>
    ): Long {
        var bytes = addWithin(current, CONTAINER_COST, available)
        values.forEach { (key, child) ->
            bytes = addWithin(bytes, CONTAINER_ITEM_COST, available)
            bytes = addWithin(bytes, key.utf8Length(), available)
            pending.addLast(NativeValueFrame(child))
        }
        return bytes
    }

    private fun reserveNodes(reserved: Long, children: Int): Long =
        addWithin(reserved, children.toLong(), limits.maxNodes.toLong())

    private fun addWithin(current: Long, addition: Long, maximum: Long): Long {
        if (addition < 0 || current > maximum || addition > maximum - current) {
            invalidQuery()
        }
        return current + addition
    }

    private fun String.utf8Length(): Long {
        var bytes = 0L
        var index = 0
        while (index < length) {
            val current = this[index]
            val addition = when {
                current.code <= 0x7F -> 1L
                current.code <= 0x7FF -> 2L
                Character.isHighSurrogate(current) && index + 1 < length &&
                    Character.isLowSurrogate(this[index + 1]) -> {
                    index++
                    4L
                }

                Character.isSurrogate(current) -> 1L
                else -> 3L
            }
            bytes += addition
            index++
        }
        return bytes
    }

    private fun java.math.BigDecimal.plainStringLength(): Long {
        val digits = precision().toLong()
        val sign = if (signum() < 0) 1L else 0L
        val scale = scale().toLong()
        if (signum() == 0) {
            return sign + if (scale > 0) scale + 2 else 1
        }
        return sign + when {
            scale == 0L -> digits
            scale < 0L -> digits - scale
            scale < digits -> digits + 1
            else -> scale + 2
        }
    }

    private data class StructureFrame(val expression: QueryExpression, val depth: Int)

    private data class SchemaFrame(val expression: QueryExpression, val relativeTo: LogicalField?)

    private data class NativeValueFrame(val value: QueryValue)

    private companion object {
        const val VALUE_COST = 1L
        const val CONTAINER_COST = 1L
        const val CONTAINER_ITEM_COST = 1L
        const val BOOLEAN_BYTES = 1L
        const val INTEGER_BYTES = Long.SIZE_BYTES.toLong()
        const val FLOATING_BYTES = Double.SIZE_BYTES.toLong()
        val NULL_VALUE_OPERATORS = setOf(
            PortableOperator.EQ,
            PortableOperator.NE,
            PortableOperator.IN,
            PortableOperator.NOT_IN
        )
    }
}

internal fun invalidQuery(): Nothing = throw QueryException(
    QueryErrorCode.INVALID_QUERY,
    QueryStage.VALIDATION,
    QueryErrorReason.INVALID_REQUEST
)
