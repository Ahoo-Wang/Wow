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

package me.ahoo.wow.manifest.tools

import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary

/**
 * The words of a schema, the two annotation keywords JSON Schema has for them: `title`, the `@Summary` of what the
 * schema describes, and `description`, its `@Description`. Neither stands in for the other, and a blank one is left
 * out; both are trimmed.
 *
 * - A type's are its class's. A command's or an event's `description` is its fragment entry's
 *   ([entryDescription]), which the compiler reads from the `@Description`, else from the KDoc.
 * - A property's are its own, read where each language lets the annotations stand: on a Kotlin property (declared in
 *   the constructor or the body, or computed), on its field (`@field:Summary`), and on a Java field, which a record's
 *   component is annotated as. They are keywords of the property's schema, beside its `$ref` when its type is a
 *   definition: JSON Schema 2020-12 reads both, so the property's words hold where it is used and the type's stay in
 *   its definition. Where the type is written in place, the property's replace the type's keyword by keyword
 *   ([inPlace]).
 *
 * Words are no contract: the compatibility gate reads neither keyword ([SchemaShapes.ANNOTATIONS]).
 */
internal object SchemaWords {
    const val TITLE = "title"
    const val DESCRIPTION = "description"

    /**
     * Resolves both keywords of types and members from Wow's annotations. Called ahead of the Jackson module, whose
     * `@JsonClassDescription` and `@JsonPropertyDescription` describe what Wow's annotations do not.
     */
    fun applyTo(builder: SchemaGeneratorConfigBuilder) {
        builder.forTypesInGeneral()
            .withTitleResolver { scope -> words(scope.type.erasedType.getAnnotation(Summary::class.java)?.value) }
            .withDescriptionResolver { scope ->
                words(scope.type.erasedType.getAnnotation(Description::class.java)?.value)
            }
        builder.forFields().withTitleResolver(::summary).withDescriptionResolver(::description)
        builder.forMethods().withTitleResolver(::summary).withDescriptionResolver(::description)
    }

    /**
     * The [schema] of a command or an event, described as its fragment [entry] says, ahead of its class's own
     * description as the entry always was. An entry that only repeats the summary, which the compiler falls back to
     * and the schema already carries as its `title`, describes nothing more and is left out.
     */
    fun entryDescription(schema: Map<String, Any?>, entry: String): Map<String, Any?> =
        if (schema[TITLE] == entry) schema else schema + (DESCRIPTION to entry)

    /**
     * [schema] with each property's words written into its type's schema where that stands in place. The generator
     * writes a member's keyword that its type's schema has too as a conjunction of the two,
     * `{"allOf": [<the type's>, {"description": …}]}`; for the words the property's replaces the type's, so a property
     * is one plain schema whether its type is written in place or referred to. A conjunction whose parts differ in
     * any other keyword, a bound each of them sets, keeps both.
     */
    @Suppress("UNCHECKED_CAST")
    fun inPlace(schema: Map<String, Any?>): Map<String, Any?> = folded(schema) as Map<String, Any?>

    private fun folded(node: Any?): Any? = when (node) {
        is Map<*, *> -> {
            val written = node.entries.associate { (key, value) -> key.toString() to folded(value) }
            // The generator's conjunction is the schema's one keyword, of two parts: the type's, then the property's.
            val parts = (written[SchemaShapes.ALL_OF] as? List<*>)?.takeIf { written.size == 1 && it.size == 2 }
            val type = parts?.first() as? Map<*, *>
            val own = parts?.last() as? Map<*, *>
            if (type != null && own != null && sameButWords(type, own)) type + own else written
        }

        is List<*> -> node.map(::folded)
        else -> node
    }

    /** Whether every keyword both [type] and [own] have is a word, or the same in both. */
    private fun sameButWords(type: Map<*, *>, own: Map<*, *>): Boolean =
        own.all { (keyword, value) -> keyword !in type || keyword == TITLE || keyword == DESCRIPTION || type[keyword] == value }

    private fun summary(member: MemberScope<*, *>): String? = words(annotation<Summary>(member)?.value)

    private fun description(member: MemberScope<*, *>): String? = words(annotation<Description>(member)?.value)

    /**
     * The annotation of [member]'s property: the Kotlin property's, else its field's. None for the items of a
     * container, which the generator asks of the container's member: the words are the property's, not each item's.
     */
    private inline fun <reified A : Annotation> annotation(member: MemberScope<*, *>): A? {
        if (member.isFakeContainerItemScope) return null
        val property = runCatching { KotlinMembers.propertyOf(member)?.annotations }.getOrNull().orEmpty()
        return property.filterIsInstance<A>().firstOrNull()
            ?: (member as? FieldScope)?.rawMember?.getAnnotation(A::class.java)
    }

    private fun words(value: String?): String? = value?.trim()?.ifEmpty { null }
}
