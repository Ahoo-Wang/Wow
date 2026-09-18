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

// Not Wow source. Wire values in each shape the checker has to compare, with a
// known difference from this package in every one, so each comparison has
// something to report.
package me.ahoo.wow.api.query

// Wow gains a value upstream: it must be named as missing here.
enum class AggregationFunction { SUM, AVG, MIN, MAX, STDDEV, VARIANCE, MEDIAN }

// Wow drops one: this package would still send it.
enum class SearchMode { TERMS }

// Wow brings back a value this package keeps only for older servers.
enum class Operator { RAW }

// An enum with nothing to mirror it here. Its entries carry arguments and a
// body, which an entry may; they must still read as ONE and TWO.
enum class SyntheticOnlyInWow(val code: Int) {
    ONE(1),
    TWO(2) { override fun toString() = "two" },
}

// Discriminators spelled as literals on a sealed interface.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(SyntheticDispatch.Alpha::class, name = "ALPHA"),
    JsonSubTypes.Type(SyntheticDispatch.Beta::class, name = "BETA"),
)
sealed interface SyntheticDispatch {
    data object Alpha : SyntheticDispatch
    data object Beta : SyntheticDispatch
}

// An entry that is not UPPER_SNAKE is as much a wire value as one that is.
enum class Direction { ASC, DESC, random }

// A discriminator holding a hyphen and lowercase letters, behind a comment of
// its own, with a further annotation between it and its interface whose
// arguments hold brackets.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(HavingExpression.Condition::class, name = "CONDITION"),
    JsonSubTypes.Type(HavingExpression.NotNull::class, /* lowercase on purpose */ name = "not-null"),
)
@Schema(oneOf = [HavingExpression.Condition::class], discriminatorProperty = "type")
sealed interface HavingExpression {
    data object Condition : HavingExpression
    data object NotNull : HavingExpression
}

// An annotated entry is as much an entry. QUARTER must still be read, or it
// would be reported as sent here and unknown to Wow. `@JsonProperty` sets the
// wire value: FORTNIGHT goes out as `fortnight`, and MONTH — which keeps its
// Kotlin name, the way this package still spells it — now goes out as
// `month`, so reading the name would pass while the server refused `MONTH`.
enum class AggregationDateUnit {
    YEAR,
    @Deprecated("use MONTH") QUARTER,
    @JsonProperty("month") MONTH,
    WEEK, DAY, HOUR, MINUTE, SECOND,
    @JsonProperty(/* the wire value */ value = "fortnight", index = 8) FORTNIGHT,
}

// A discriminator spelled as a constant, the way Wow spells its filter
// operators, and one whose constant cannot be found.
object SyntheticProtocol {
    object Group {
        const val NEW_TYPE = "new-type"

        // Starts with a literal but is not one. Reading only the first string
        // would report TERMS, which this package already sends, and pass.
        const val JOINED = "TERMS" + "-server"

        // The same past a line break. A line may open with a dot, so the
        // newline does not end the expression, and reading up to it would
        // report TERMS again.
        const val DOTTED = "TERMS"
            .plus("-server")

        // Declared under the same path in the schema subpackage, with another
        // value. Kotlin tells the two apart by package; this checker cannot,
        // so neither may stand in for the other.
        const val SHARED = "TERMS"
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(AggregationGroup.New::class, name = SyntheticProtocol.Group.NEW_TYPE),
    JsonSubTypes.Type(AggregationGroup.Lost::class, name = Nowhere.MISSING),
    JsonSubTypes.Type(AggregationGroup.Joined::class, name = SyntheticProtocol.Group.JOINED),
    JsonSubTypes.Type(AggregationGroup.Dotted::class, name = SyntheticProtocol.Group.DOTTED),
    JsonSubTypes.Type(AggregationGroup.Shared::class, name = SyntheticProtocol.Group.SHARED),
    // Inside brackets Kotlin reads an operator on the next line as part of the
    // expression. And a constant can start one as much as a literal can: read
    // alone, NEW_TYPE would pass for new-type.
    JsonSubTypes.Type(
        AggregationGroup.Wrapped::class,
        name = "TERMS"
            + "-wrapped",
    ),
    JsonSubTypes.Type(AggregationGroup.Suffixed::class, name = SyntheticProtocol.Group.NEW_TYPE + "-v2"),
)
sealed interface AggregationGroup {
    data object New : AggregationGroup
    data object Lost : AggregationGroup
    data object Joined : AggregationGroup
    data object Dotted : AggregationGroup
    data object Shared : AggregationGroup
    data object Wrapped : AggregationGroup
    data object Suffixed : AggregationGroup
}

// Written through a property, so the entry names are not what goes out.
enum class SyntheticWire(@get:JsonValue val wire: String) {
    A("a"),
    B("b"),
}

// Declared again, under the same simple name, in a subpackage.
enum class SyntheticTwin { ONE }

// Something the parser recognises as an entry but cannot read. Not valid
// Kotlin; it stands for whatever shape the parser has not met yet, which must
// be reported rather than dropped.
enum class Unreadable { FINE, @ BROKEN }

// Kotlin block comments nest. Stopping at the first closer would read
// `still inside the comment` and NOT_EQ as entries and swallow the real
// BETWEEN_EXCLUSIVE that follows.
enum class ComparisonOperator {
    EQ, NE, GT, GTE, LT, LTE,
    /* retired: /* was LIKE */ still inside the comment, NOT_EQ */
    BETWEEN_EXCLUSIVE,
}

// Subtypes named three ways: by `name =`, by `@JsonTypeName` on the class —
// short or qualified, and on the class the entry names rather than one a
// comment mentions — and by nothing this checker can read, which must be
// reported, not skipped.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(DerivedExpression.Ref::class, name = "METRIC_REF"),
    JsonSubTypes.Type(DerivedExpression.Named::class),
    JsonSubTypes.Type(DerivedExpression.QualifiedName::class),
    JsonSubTypes.Type(/* was DerivedExpression.Named::class */ DerivedExpression.Renamed::class),
    JsonSubTypes.Type(DerivedExpression.Unnamed::class),
)
sealed interface DerivedExpression {
    data object Ref : DerivedExpression

    @JsonTypeName("NAMED_BY_ANNOTATION")
    data object Named : DerivedExpression

    @com.fasterxml.jackson.annotation.JsonTypeName("QUALIFIED_BY_ANNOTATION")
    data object QualifiedName : DerivedExpression

    @JsonTypeName("RENAMED_BY_ANNOTATION")
    data object Renamed : DerivedExpression

    data object Unnamed : DerivedExpression
}

// Not valid Kotlin: something after an entry's name that is neither arguments
// nor a body. Reading only the leading name would take the rest on trust; it
// stands for whatever a future parsing gap leaves behind.
enum class Trailing { GOOD, BAD extra }

// @JsonValue under other use-site targets, and by its qualified name. Each puts
// a property on the wire exactly as `@get:JsonValue` does.
enum class SyntheticFieldWire(@field:JsonValue val wire: String) { A("a") }

enum class SyntheticQualifiedWire(
    @get:com.fasterxml.jackson.annotation.JsonValue val wire: String,
) { A("a") }

// Subtype names are the wire discriminators only under Id.NAME. Under
// Id.CLASS the wire carries class names, and with no @JsonTypeInfo here the id
// cannot be known from this declaration at all.
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
@JsonSubTypes(JsonSubTypes.Type(ByClass.A::class, name = "TERMS"))
sealed interface ByClass {
    data object A : ByClass
}

@JsonSubTypes(JsonSubTypes.Type(Untyped.A::class, name = "TERMS"))
sealed interface Untyped {
    data object A : Untyped
}

// The id this used to use, kept in a comment above the one it uses now. The
// wire carries class names; reading the comment would compare names instead.
@JsonTypeInfo(
    // use = JsonTypeInfo.Id.NAME,
    use = JsonTypeInfo.Id.CLASS,
)
@JsonSubTypes(JsonSubTypes.Type(CommentedId.A::class, name = "TERMS"))
sealed interface CommentedId {
    data object A : CommentedId
}

// Every annotation by its qualified name. Matching only the short names would
// skip this declaration whole, and a set Wow added this way would go unseen.
@com.fasterxml.jackson.annotation.JsonTypeInfo(
    use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
    property = "type",
)
@com.fasterxml.jackson.annotation.JsonSubTypes(
    com.fasterxml.jackson.annotation.JsonSubTypes.Type(SyntheticQualifiedDispatch.A::class, name = "QUALIFIED"),
)
sealed interface SyntheticQualifiedDispatch {
    data object A : SyntheticQualifiedDispatch
}

