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

import me.ahoo.wow.annotation.AggregateAnnotationParser.asAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.asAggregateNameGetterIfAnnotated
import me.ahoo.wow.api.annotation.Event
import me.ahoo.wow.api.event.DEFAULT_REVISION
import me.ahoo.wow.event.metadata.EventMetadata
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.reflection.ClassMetadata
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.modeling.matedata.asNamedAggregateGetter
import me.ahoo.wow.naming.annotation.asName
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Event Metadata Parser .
 *
 * @author ahoo wang
 */
object EventMetadataParser : CacheableMetadataParser<Class<*>, EventMetadata<*>>() {

    override fun parseAsMetadata(type: Class<*>): EventMetadata<*> {
        val visitor = EventMetadataVisitor(type)
        ClassMetadata.visit(type, visitor)
        return visitor.asMetadata()
    }

    internal class EventMetadataVisitor<E>(private val eventType: Class<E>) : ClassVisitor {
        private val eventName: String = eventType.asName()
        private var aggregateNameGetter: PropertyGetter<E, String>? = null
        private var revision = DEFAULT_REVISION
        private var aggregateIdGetter: PropertyGetter<E, String>? = null

        init {
            val event = eventType.getAnnotation(Event::class.java)
            event?.let {
                if (it.revision.isNotEmpty()) {
                    revision = it.revision
                }
            }
        }

        override fun visitField(field: Field) {
            if (aggregateNameGetter == null) {
                aggregateNameGetter = field.asAggregateNameGetterIfAnnotated()
            }
            if (aggregateIdGetter == null) {
                aggregateIdGetter = field.asAggregateIdGetterIfAnnotated()
            }
        }

        override fun visitMethod(method: Method) {
            if (aggregateNameGetter == null) {
                aggregateNameGetter = method.asAggregateNameGetterIfAnnotated()
            }
            if (aggregateIdGetter == null) {
                aggregateIdGetter = method.asAggregateIdGetterIfAnnotated()
            }
        }

        fun asMetadata(): EventMetadata<E> {
            val namedAggregateGetter = aggregateNameGetter.asNamedAggregateGetter(eventType)
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

fun <E> Class<out E>.asEventMetadata(): EventMetadata<E> {
    @Suppress("UNCHECKED_CAST")
    return EventMetadataParser.parse(this) as EventMetadata<E>
}

inline fun <reified E> eventMetadata(): EventMetadata<E> {
    return E::class.java.asEventMetadata()
}
