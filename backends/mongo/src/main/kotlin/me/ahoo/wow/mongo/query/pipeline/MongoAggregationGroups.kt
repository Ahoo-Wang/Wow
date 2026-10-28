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

package me.ahoo.wow.mongo.query.pipeline

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UnwindOptions
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.aggregation.DenseDateGrid
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.Temporal
import org.bson.Document
import org.bson.conversions.Bson
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

/**
 * The `$match` a record must pass to join a group of this kind (`null`: every record joins), and the group's key
 * expression. As the reference semantics say: a record without a value forms the `null` group of a terms or histogram
 * key (or the terms' missing key), a record joins the terms group of each of its values ([termsValues] unwinds them
 * first), a value that is not a number forms the `null` histogram bucket, and a record without an instant joins no
 * date group.
 */
internal fun AggregationGroup.compile(
    admitted: AdmittedQuery<*>,
    denseGrid: DenseDateGrid?,
    index: Int,
): Pair<Bson?, Any> = when (this) {
    is AggregationGroup.Terms -> null to termsKey(admitted, index)
    is AggregationGroup.Histogram -> {
        val computed = expression?.let { numericParticipation(it, nullGuarded = true, admitted).first }
        val input = computed ?: scalarOrSingleton("\$${admitted.path(checkNotNull(field))}")
        null to Document(
            "\$let",
            Document("vars", Document("value", input)).append(
                "in",
                Document(
                    "\$cond",
                    listOf(
                        Document("\$isNumber", "\$\$value"),
                        Document(
                            "\$multiply",
                            listOf(Document("\$floor", Document("\$divide", listOf("\$\$value", interval))), interval),
                        ),
                        null,
                    ),
                ),
            ),
        )
    }
    is AggregationGroup.DateHistogram -> dateHistogramKey(admitted, denseGrid)
    is AggregationGroup.DatePart -> datePartKey(admitted)
}

/**
 * Whether this group reads a field that may hold several values per record, so [termsValues] unwinds it before the
 * `$group`: a field of unknown cardinality too, since unwinding a single value keeps it as it is.
 */
internal fun AggregationGroup.multiValued(admitted: AdmittedQuery<*>): Boolean =
    this is AggregationGroup.Terms && expression == null &&
        admitted.field(checkNotNull(field)).cardinality != QueryCardinality.SINGLE

/**
 * The stages that give each record one copy per distinct non-null value of the terms field of group [index], in the
 * reserved field [termsValue]: an array's values, or its single value; a record without one (missing, `null`, an empty
 * array or only `null`s) is kept once without it, so it forms the `null` group.
 */
internal fun AggregationGroup.Terms.termsValues(admitted: AdmittedQuery<*>, index: Int): List<Bson> {
    val value = "\$${admitted.path(checkNotNull(field))}"
    val values = Document(
        "\$cond",
        listOf(
            Document("\$isArray", value),
            Document(
                "\$setUnion",
                listOf(
                    Document(
                        "\$filter",
                        Document("input", value).append("cond", Document("\$ne", listOf("\$\$this", null))),
                    ),
                ),
            ),
            value,
        ),
    )
    return listOf(
        Aggregates.set(Field(termsValue(index), values)),
        Aggregates.unwind("\$${termsValue(index)}", UnwindOptions().preserveNullAndEmptyArrays(true)),
    )
}

/** The reserved field one value of group [index]'s terms field is unwound into. */
internal fun termsValue(index: Int): String = "__wow_terms_$index"

/** A terms key: the expression's value, or the field's value (one of them, unwound), `null` or the missing key without. */
private fun AggregationGroup.Terms.termsKey(admitted: AdmittedQuery<*>, index: Int): Any {
    expression?.let { return numericParticipation(it, nullGuarded = true, admitted).first }
    val value = if (multiValued(admitted)) "\$${termsValue(index)}" else "\$${admitted.path(checkNotNull(field))}"
    // A missing key is a value: `$literal` keeps one starting with `$` from reading a field.
    return Document("\$ifNull", listOf(value, missingKey?.let { Document("\$literal", it) }))
}

private fun AggregationGroup.DateHistogram.dateHistogramKey(
    admitted: AdmittedQuery<*>,
    denseGrid: DenseDateGrid?,
): Pair<Bson?, Any> {
    val input = dateInput(admitted)
    val zone = mongoTimeZone(timeZone)
    val truncation = Document("date", input)
        .append("unit", unit.name.lowercase())
        .append("timezone", zone)
        .apply { if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday") }
    val present = Filters.expr(Document("\$ne", listOf(input, null)))
    if (denseGrid == null) {
        val key = if (unit == AggregationDateUnit.HOUR) {
            wallHourTruncation(input, zone)
        } else {
            Document("\$dateTrunc", truncation)
        }
        return present to Document("\$toLong", key)
    }
    // `$densify` has no timezone option, so dense histograms group by the integer bucket index from the
    // [DenseDateGrid.anchor] and densify numerically; the index is inverted back into the display key by
    // [denseKeyProjection].
    return present to if (unit == AggregationDateUnit.HOUR) {
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

/**
 * This temporal field as a BSON date, decoded from its epoch; `null` when absent. A snapshot document stores a date
 * string as text ([me.ahoo.wow.mongo.ExactBson]), so the storage facts grant temporal aggregation to epochs alone.
 */
internal fun QueryField.dateInput(admitted: AdmittedQuery<*>): Any {
    val resolved = admitted.field(this)
    return when (val temporal = resolved.temporal) {
        is Temporal.Epoch -> epochDate(admitted.path(this), temporal.timeUnit)
        Temporal.Date, is Temporal.Formatted, null -> throw QueryExecutionException(
            "Admission resolved [${resolved.logicalField}] without an instant encoding."
        )
    }
}

internal fun mongoTimeZone(timeZone: String): String {
    val zone = ZoneId.of(timeZone).normalized()
    if (zone !is ZoneOffset) return timeZone
    if (zone.totalSeconds % 60 != 0) {
        throw QueryViolation.StorageUnsupported("time zone offsets with seconds").rejection()
    }
    return if (zone == ZoneOffset.UTC) "UTC" else zone.id
}
