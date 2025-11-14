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

package me.ahoo.wow.event.annotation

import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateNameGetterIfAnnotated
import me.ahoo.wow.api.annotation.Event
import me.ahoo.wow.api.event.DEFAULT_REVISION
import me.ahoo.wow.event.metadata.EventMetadata
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.matedata.toNamedAggregateGetter
import me.ahoo.wow.naming.annotation.toName
import kotlin.reflect.KProperty1

/**
 * Parser for extracting metadata from domain event classes.
 *
 * This object parses domain event classes to extract metadata such as event names,
 * revisions, and aggregate information from annotations and class structure.
 * It implements CacheableMetadataParser for performance optimization.
 *
 * @see CacheableMetadataParser
 * @see EventMetadata
 * @see Event
 */
object EventMetadataParser : CacheableMetadataParser() {
    /**
     * Parses a class to extract event metadata.
     *
     * @param TYPE The type of the event class
     * @param M The metadata type (must be EventMetadata)
     * @param type The class to parse
     * @return The extracted event metadata
     *
     * @see EventMetadata
     */
    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val visitor = EventMetadataVisitor(type)
        type.kotlin.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }

    /**
     * Visitor class for extracting event metadata from Kotlin classes.
     *
     * This internal class visits class properties and annotations to collect
     * information needed to construct EventMetadata.
     *
     * @param E The event type
     * @property eventType The class being visited
     *
     * @see ClassVisitor
     * @see EventMetadata
     */
    internal class EventMetadataVisitor<E : Any>(
        private val eventType: Class<E>
    ) : ClassVisitor<E, EventMetadata<E>> {
        private val eventName: String = eventType.toName()
        private var aggregateNameGetter: PropertyGetter<E, String>? = null
        private var revision = DEFAULT_REVISION
        private var aggregateIdGetter: PropertyGetter<E, String>? = null

        init {
            eventType.kotlin.scanAnnotation<Event>()?.let {
                if (it.revision.isNotEmpty()) {
                    revision = it.revision
                }
            }
        }

        /**
         * Visits a class property to extract aggregate-related getters.
         *
         * @param property The property being visited
         *
         * @see PropertyGetter
         * @see toAggregateNameGetterIfAnnotated
         * @see toAggregateIdGetterIfAnnotated
         */
        override fun visitProperty(property: KProperty1<E, *>) {
            if (aggregateNameGetter == null) {
                aggregateNameGetter = property.toAggregateNameGetterIfAnnotated()
            }
            if (aggregateIdGetter == null) {
                aggregateIdGetter = property.toAggregateIdGetterIfAnnotated()
            }
        }

        /**
         * Creates the EventMetadata from collected information.
         *
         * @return The constructed EventMetadata instance
         *
         * @see EventMetadata
         * @see NamedAggregateGetter
         */
        override fun toMetadata(): EventMetadata<E> {
            val namedAggregateGetter = aggregateNameGetter.toNamedAggregateGetter(eventType)
            return EventMetadata(
                eventType = eventType,
                namedAggregateGetter = namedAggregateGetter,
                name = eventName,
                revision = revision,
                aggregateIdGetter = aggregateIdGetter,
            )
        }
    }
}

/**
 * Extension function to parse event metadata from a class.
 *
 * @param E The event type
 * @receiver The class to parse metadata from
 * @return The parsed event metadata
 *
 * @see EventMetadataParser.parse
 * @see EventMetadata
 */
fun <E : Any> Class<out E>.toEventMetadata(): EventMetadata<E> = EventMetadataParser.parse(this)

/**
 * Inline function to get event metadata for a reified type.
 *
 * @param E The event type
 * @return The event metadata for the specified type
 *
 * @see toEventMetadata
 * @see EventMetadata
 */
inline fun <reified E : Any> eventMetadata(): EventMetadata<E> = E::class.java.toEventMetadata()
