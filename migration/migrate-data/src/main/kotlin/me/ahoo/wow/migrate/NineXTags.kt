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

import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode

/**
 * Where a 9.x tag key's values come from ([TagMapping.source]).
 *
 * 9.x gave an aggregate its tags two ways: `ApplyResourceTags` set them (its event replacing the whole map), and a
 * state implementing `StateAggregateTagsExtractor` derived them on every commit, 9.x keeping what it returned (the
 * example's merged the tags it had, applied and derived before, into the ones it derived). V10 has no
 * tags: each becomes an access attribute (`@Access`), a state field a domain event sets, or a function of the state.
 */
internal enum class TagSource {
    /** Set by 9.x's tags event: a state field in V10, which the mapped tags event writes. */
    APPLIED,

    /** Derived by the 9.x state: an `@Access` function in V10; mapped so `compare` checks V10 derives the same. */
    DERIVED,

    /**
     * Both, the state's extractor having the last word, as 9.x had it: an `@Access` function in V10, so the tags
     * event leaves its applied values out (the check warns of them).
     */
    BOTH,
}

/**
 * One 9.x tag key of an aggregate ([AggregateMapping.tags]): the V10 access attribute [to] it becomes, its value
 * converted by the attribute's Manifest entry ([TagValues]), or, without [to], a key left out on purpose, for the
 * [reason] the check quotes: leaving a key out changes who may read the data, so the map records why.
 */
internal data class TagMapping(
    val key: String,
    val to: String? = null,
    val source: TagSource = TagSource.APPLIED,
    val reason: String = "",
) {
    init {
        require(key.isNotBlank()) { "A tag mapping names its 9.x tag key." }
        require(to == null || to.isNotBlank()) { "The tag mapping of $key names a blank access attribute." }
        require(to != null || reason.isNotBlank()) {
            "The tag mapping of $key names no access attribute, so it leaves the key out: give the reason."
        }
    }

    /** Whether the mapped tags event writes this key's attribute: an applied key that is not derived too. */
    val written: Boolean get() = to != null && source == TagSource.APPLIED
}

/** A 9.x tag key's values, as some records hold them: how many records, and how many hold values the attribute can't. */
internal data class TagValueStats(
    val count: Long,
    val multiple: Long = 0,
    val notInteger: Long = 0,
    val notBoolean: Long = 0,
) {
    /** These and [other]'s counts added. */
    operator fun plus(other: TagValueStats): TagValueStats = TagValueStats(
        count + other.count,
        multiple + other.multiple,
        notInteger + other.notInteger,
        notBoolean + other.notBoolean,
    )

    companion object {
        /** The counts of one record's [values]. */
        fun of(values: List<String>): TagValueStats = TagValueStats(
            count = 1,
            multiple = if (values.distinct().size > 1) 1 else 0,
            notInteger = if (values.any { it.toLongOrNull() == null }) 1 else 0,
            notBoolean = if (values.any { TagValues.booleanOf(it) == null }) 1 else 0,
        )
    }
}

/** A kind of 9.x tag value an access attribute cannot hold, for the samples the check names ([TagValues.problems]). */
internal enum class TagValueProblem {
    /** Two values or more, for a single attribute. */
    MULTIPLE,

    /** A value that is no integer, for an `INTEGER` attribute. */
    NOT_INTEGER,

    /** A value that is not `true` or `false`, for a `BOOLEAN` attribute. */
    NOT_BOOLEAN,
}

/**
 * What a 9.x aggregate's tags hold, for the check and for `draft-map`: per tag key, the values its tags events wrote
 * ([applied]: events whose payload holds a `tags` object, the 9.x `ResourceTagsApplied` shape), and the values its
 * snapshots hold ([snapshots]: applied, then derived); and the events by name that hold tags ([events]). A key the
 * snapshots hold and no event wrote was derived by the 9.x state.
 */
internal data class NineXTags(
    val applied: Map<String, TagValueStats> = emptyMap(),
    val events: Map<String, Long> = emptyMap(),
    val snapshots: Map<String, TagValueStats> = emptyMap(),
) {
    companion object {
        val NONE = NineXTags()

        /** The tags of one 9.x payload, or of a snapshot: the `tags` object, its values as strings; null without one. */
        fun of(tags: JsonNode?): Map<String, List<String>>? {
            if (tags == null || !tags.isObject) {
                return null
            }
            return tags.properties().associate { (key, value) ->
                key to when {
                    value.isArray -> value.mapNotNull { element -> element.takeUnless { it.isNull }?.asString() }
                    value.isNull -> emptyList()
                    else -> listOf(value.asString())
                }
            }
        }
    }
}

/**
 * A 9.x tag's values as the V10 access attribute they become holds them, by its Manifest entry ([AccessAttributeManifest]):
 * one the import writes into the mapped tags event, the check counts, and `compare` compares, each the same way.
 *
 * - A multiple attribute holds the values, without duplicates; a single one, the one value, or none for none.
 * - An `INTEGER` attribute's values are the strings 9.x stored, read as integers; a `BOOLEAN` one's, `true` or `false`
 *   in any case.
 * - What the attribute cannot hold is a [TagValueProblem]: two values or more for a single attribute, or a value that
 *   does not read as its type. The check reports each with its count; the import fails the stream that holds one.
 */
internal object TagValues {
    /** [values] as [attribute] holds them; [TagValueException] when it cannot. An absent key is no values. */
    fun valueOf(attribute: AccessAttributeManifest, values: List<String>?): Any? {
        val read = values.orEmpty().distinct().map { valueOf(attribute, it) }
        if (attribute.multiple) {
            return read
        }
        if (read.size > 1) {
            throw TagValueException(
                "holds ${read.size} values, $values, which the single attribute ${attribute.name} cannot"
            )
        }
        return read.singleOrNull()
    }

    /** [values] as [attribute] holds them, a JSON value of the mapped event's payload. */
    fun nodeOf(attribute: AccessAttributeManifest, values: List<String>?): JsonNode =
        WowJson.mapper.valueToTree(valueOf(attribute, values))

    /**
     * An attribute's value, a payload's JSON, as the 9.x tag values it was: the strings 9.x stored, for the rollback
     * export; none for `null` or an empty list.
     */
    fun stringsOf(node: JsonNode?): List<String> = when {
        node == null || node.isNull -> emptyList()
        node.isArray -> node.filterNot { it.isNull }.map { it.asString() }
        else -> listOf(node.asString())
    }

    /** The problems [stats] counts that [attribute] cannot hold, with their counts. */
    fun problems(attribute: AccessAttributeManifest, stats: TagValueStats): Map<TagValueProblem, Long> = buildMap {
        if (!attribute.multiple && stats.multiple > 0) {
            put(TagValueProblem.MULTIPLE, stats.multiple)
        }
        if (attribute.type == AccessAttributeManifest.Type.INTEGER && stats.notInteger > 0) {
            put(TagValueProblem.NOT_INTEGER, stats.notInteger)
        }
        if (attribute.type == AccessAttributeManifest.Type.BOOLEAN && stats.notBoolean > 0) {
            put(TagValueProblem.NOT_BOOLEAN, stats.notBoolean)
        }
    }

    /** Whether [values] hold [problem]. */
    fun holds(problem: TagValueProblem, values: List<String>): Boolean = when (problem) {
        TagValueProblem.MULTIPLE -> values.distinct().size > 1
        TagValueProblem.NOT_INTEGER -> values.any { it.toLongOrNull() == null }
        TagValueProblem.NOT_BOOLEAN -> values.any { booleanOf(it) == null }
    }

    /** [value] read as `true` or `false`, in any case; null for any other. */
    fun booleanOf(value: String): Boolean? = when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> null
    }

    private fun valueOf(attribute: AccessAttributeManifest, value: String): Any = when (attribute.type) {
        AccessAttributeManifest.Type.INTEGER ->
            value.toLongOrNull() ?: throw TagValueException("holds $value, which the integer attribute ${attribute.name} cannot")

        AccessAttributeManifest.Type.BOOLEAN ->
            booleanOf(value) ?: throw TagValueException("holds $value, which the boolean attribute ${attribute.name} cannot")

        else -> value
    }
}

/** A 9.x tag value an access attribute cannot hold ([TagValues]): its message says what, for the caller to place. */
internal class TagValueException(message: String) : RuntimeException(message)

/** What the check reads of a 9.x store's tags ([MappingCheck]): their counts, then sample ids of a problem. */
internal interface TagReader {
    /** What [aggregate]'s tags events wrote and its snapshots hold. */
    fun tags(aggregate: NineXAggregate): NineXTags

    /**
     * A few ids of [aggregate]'s records whose values of [key] hold [problem]: its instances' snapshots when
     * [fromSnapshots], its streams otherwise.
     */
    fun samples(aggregate: NineXAggregate, key: String, problem: TagValueProblem, fromSnapshots: Boolean): List<String>

    companion object {
        /** The ids a problem names at most. */
        const val SAMPLES = 3

        /** A store without tags. */
        val NONE: TagReader = object : TagReader {
            override fun tags(aggregate: NineXAggregate): NineXTags = NineXTags.NONE

            override fun samples(aggregate: NineXAggregate, key: String, problem: TagValueProblem, fromSnapshots: Boolean) =
                emptyList<String>()
        }

        /** The tags of the 9.x store whose streams [streams] and whose snapshots [snapshots] read. */
        fun of(streams: NineXEventSource, snapshots: NineXSnapshotSource): TagReader = object : TagReader {
            override fun tags(aggregate: NineXAggregate): NineXTags =
                streams.tags(aggregate).copy(snapshots = snapshots.tags(aggregate))

            override fun samples(aggregate: NineXAggregate, key: String, problem: TagValueProblem, fromSnapshots: Boolean) =
                if (fromSnapshots) {
                    snapshots.tagSamples(aggregate, key, problem, SAMPLES)
                } else {
                    streams.tagSamples(aggregate, key, problem, SAMPLES)
                }
        }
    }
}
