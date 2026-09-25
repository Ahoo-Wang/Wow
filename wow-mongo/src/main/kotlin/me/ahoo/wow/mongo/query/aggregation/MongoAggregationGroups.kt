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

import com.mongodb.client.model.Filters
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.aggregation.DenseDateGrid
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.operationValues
import org.bson.Document
import org.bson.conversions.Bson
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

internal fun AggregationGroup.compile(
    admitted: AdmittedQuery<*>,
    denseGrid: DenseDateGrid?,
): Pair<Bson?, Any> {
    val expressionKey = { expression: AggregationExpression ->
        val input = numericParticipation(expression, nullGuarded = true, admitted).first
        Filters.expr(Document("\$ne", listOf(input, null))) to input
    }
    return compileGroup(admitted, denseGrid, expressionKey)
}

@Suppress("CyclomaticComplexMethod") // One branch per group type and input.
private fun AggregationGroup.compileGroup(
    admitted: AdmittedQuery<*>,
    denseGrid: DenseDateGrid?,
    expressionKey: (AggregationExpression) -> Pair<Bson?, Any>,
): Pair<Bson?, Any> = when (this) {
    is AggregationGroup.Terms -> expression?.let { expressionKey(it) } ?: run {
        val path = checkNotNull(field).physicalPath(admitted)
        if (missingKey == null) {
            Filters.and(Filters.exists(path), Filters.ne(path, null)) to "\$$path"
        } else {
            null to Document("\$ifNull", listOf("\$$path", missingKey))
        }
    }
    is AggregationGroup.Histogram -> {
        val computed = expression?.let { numericParticipation(it, nullGuarded = true, admitted).first }
        val input = computed ?: scalarOrSingleton("\$${checkNotNull(field).physicalPath(admitted)}")
        Filters.expr(Document("\$isNumber", input)) to Document(
            "\$multiply",
            listOf(
                Document(
                    "\$floor",
                    Document(
                        "\$divide",
                        listOf(input, interval),
                    ),
                ),
                interval,
            ),
        )
    }

    is AggregationGroup.DateHistogram -> {
        val input = dateInput(admitted)
        val zone = mongoTimeZone(timeZone)
        val truncation = Document("date", input)
            .append("unit", unit.name.lowercase())
            .append("timezone", zone)
            .apply { if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday") }
        if (denseGrid != null) {
            // `$densify` has no timezone option, so dense histograms group by the integer
            // bucket index from the [DenseDateGrid.anchor] and densify numerically; the index
            // is inverted back into the display key by [denseKeyProjection].
            Filters.expr(Document("\$ne", listOf(input, null))) to if (unit == AggregationDateUnit.HOUR) {
                wallHourIndex(Date.from(denseGrid.anchor.toInstant()), wallHourTruncation(input, zone), zone)
            } else {
                Document(
                    "\$dateDiff",
                    Document("startDate", Date.from(denseGrid.anchor.toInstant()))
                        .append("endDate", Document("\$dateTrunc", truncation))
                        .append("unit", unit.name.lowercase())
                        .append("timezone", zone)
                        .apply { if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday") },
                )
            }
        } else {
            val key = if (unit == AggregationDateUnit.HOUR) {
                wallHourTruncation(input, zone)
            } else {
                Document("\$dateTrunc", truncation)
            }
            Filters.expr(Document("\$ne", listOf(input, null))) to Document("\$toLong", key)
        }
    }

    is AggregationGroup.DatePart -> datePartKey(admitted)
}

/** The calendar part of the field's date in the group's time zone; records without a date form no group. */
private fun AggregationGroup.DatePart.datePartKey(admitted: AdmittedQuery<*>): Pair<Bson?, Any> {
    val input = dateInput(admitted)
    val operator = when (part) {
        // ISO weekday (1 = Monday); `$dayOfWeek` would number Sunday 1.
        AggregationDatePart.DAY_OF_WEEK -> "\$isoDayOfWeek"
        AggregationDatePart.DAY_OF_MONTH -> "\$dayOfMonth"
        AggregationDatePart.HOUR_OF_DAY -> "\$hour"
        AggregationDatePart.MONTH_OF_YEAR -> "\$month"
    }
    return Filters.expr(Document("\$ne", listOf(input, null))) to
        Document(operator, Document("date", input).append("timezone", mongoTimeZone(timeZone)))
}

/**
 * Truncates [input] onto the LOCAL wall-clock hour grid. MongoDB aligns `$dateTrunc`
 * (unit `hour`) to UTC hour boundaries, so a zone with a sub-hour offset (e.g.
 * Australia/Lord_Howe, +10:30) would key its buckets at local :30 instead of the wall
 * hour — diverging the Elasticsearch `calendar_interval` semantics. Every real-world
 * zone offset is a whole number of minutes, so minute truncation is offset-safe;
 * subtracting the wall minute-of-hour then lands exactly on the local hour. For
 * whole-hour offsets the result is instant-identical to `$dateTrunc` (unit `hour`).
 */
private fun wallHourTruncation(input: Any, zone: String): Document = Document(
    "\$dateSubtract",
    Document(
        "startDate",
        Document("\$dateTrunc", Document("date", input).append("unit", "minute").append("timezone", zone)),
    )
        .append("unit", "minute")
        .append("amount", Document("\$minute", Document("date", input).append("timezone", zone))),
)

/**
 * Wall-clock bucket index for dense HOUR histograms: calendar day difference times 24
 * plus the wall hour of [wallHour]. Elapsed `$dateDiff` (unit `hour`) arithmetic would
 * truncate sub-hour zone-offset shifts and drift the index onto local :30 keys — the
 * day difference is date-based and `$hour` reads the wall clock, so the composite is
 * unique per wall hour, mirroring [DenseDateGrid.indexOf].
 */
internal fun wallHourIndex(anchor: Date, wallHour: Any, zone: String): Document = Document(
    "\$add",
    listOf(
        Document(
            "\$multiply",
            listOf(
                Document(
                    "\$dateDiff",
                    Document("startDate", anchor)
                        .append("endDate", wallHour)
                        .append("unit", "day")
                        .append("timezone", zone),
                ),
                24,
            ),
        ),
        Document("\$hour", Document("date", wallHour).append("timezone", zone)),
    ),
)

/**
 * Inverts a dense HOUR bucket [index] back into its display key in WALL space: the index
 * splits into day/hour parts, `$dateAdd` (unit `day`) steps whole calendar days from the
 * anchor, and `$dateFromParts` rebuilds the local wall time. A wall hour skipped whole by
 * a DST gap resolves FORWARD onto the next real bucket and is dropped by the
 * [denseRoundTripMatch] stage — mirroring [DenseDateGrid]'s round-trip skip.
 */
internal fun denseHourKey(group: AggregationGroup.DateHistogram, grid: DenseDateGrid, index: Any): Document {
    val zone = mongoTimeZone(group.timeZone)
    val dayIndex = Document("\$floor", Document("\$divide", listOf(index, 24)))
    val hourOfDay = Document("\$subtract", listOf(index, Document("\$multiply", listOf(dayIndex, 24))))
    val dayDate = Document(
        "\$dateAdd",
        Document("startDate", Date.from(grid.anchor.toInstant()))
            .append("unit", "day")
            .append("amount", dayIndex)
            .append("timezone", zone),
    )
    return Document(
        "\$dateFromParts",
        Document("year", Document("\$year", Document("date", dayDate).append("timezone", zone)))
            .append("month", Document("\$month", Document("date", dayDate).append("timezone", zone)))
            .append("day", Document("\$dayOfMonth", Document("date", dayDate).append("timezone", zone)))
            .append("hour", hourOfDay)
            .append("timezone", zone),
    )
}

/** The field of a temporal group as a BSON date, decoded from its declared temporal encoding. */
private fun AggregationGroup.dateInput(admitted: AdmittedQuery<*>): Any = checkNotNull(field).dateInput(admitted)

/** This temporal field as a BSON date, decoded from its declared temporal encoding; `null` when absent. */
internal fun QueryField.dateInput(admitted: AdmittedQuery<*>): Any {
    val resolved = admitted.field(this)
    val logicalField = resolved.logicalField
    val physicalPath = resolved.physicalField.path
    val values = resolved.value.operationValues().filter { it.kind != QueryValueKind.NULL }
    val temporal = values.takeIf { domains -> domains.all { it.kind == QueryValueKind.SCALAR } }
        ?.map { it.semanticType }?.distinct()?.singleOrNull()
    return when (val semanticType = temporal) {
        Temporal.Date -> convert(scalarOrSingleton("\$$physicalPath"), "date")
        is Temporal.Epoch -> epochDate(physicalPath, semanticType.timeUnit)
        else -> throw QuerySchemaValidationException(
            "Query field [$logicalField] does not have a supported temporal semantic type.",
        )
    }
}

internal fun mongoTimeZone(timeZone: String): String {
    val zone = ZoneId.of(timeZone).normalized()
    if (zone !is ZoneOffset) return timeZone
    if (zone.totalSeconds % 60 != 0) {
        throw QuerySchemaValidationException("MongoDB time zone offsets must use whole minutes.")
    }
    return if (zone == ZoneOffset.UTC) "UTC" else zone.id
}
