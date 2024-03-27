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
import me.ahoo.wow.infra.reflection.KClassMetadata.visit
import me.ahoo.wow.infra.reflection.KClassVisitor
import me.ahoo.wow.metadata.KCacheableMetadataParser
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.matedata.toNamedAggregateGetter
import me.ahoo.wow.naming.annotation.toName
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Event Metadata Parser .
 *
 * @author ahoo wang
 */
object EventMetadataParser : KCacheableMetadataParser() {

    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val visitor = EventMetadataVisitor(type.kotlin)
        type.kotlin.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }

    internal class EventMetadataVisitor<E : Any>(private val eventType: KClass<E>) : KClassVisitor<E> {
        private val eventName: String = eventType.toName()
        private var aggregateNameGetter: PropertyGetter<E, String>? = null
        private var revision = DEFAULT_REVISION
        private var aggregateIdGetter: PropertyGetter<E, String>? = null

        init {
            eventType.scanAnnotation<Event>()?.let {
                if (it.revision.isNotEmpty()) {
                    revision = it.revision
                }
            }
        }

        override fun visitProperty(property: KProperty1<E, *>) {
            if (aggregateNameGetter == null) {
                aggregateNameGetter = property.toAggregateNameGetterIfAnnotated()
            }
            if (aggregateIdGetter == null) {
                aggregateIdGetter = property.toAggregateIdGetterIfAnnotated()
            }
        }

        fun toMetadata(): EventMetadata<E> {
            val namedAggregateGetter = aggregateNameGetter.toNamedAggregateGetter(eventType.java)
            return EventMetadata(
                eventType = eventType.java,
                namedAggregateGetter = namedAggregateGetter,
                name = eventName,
                revision = revision,
                aggregateIdGetter = aggregateIdGetter,
            )
        }
    }
}

fun <E : Any> Class<out E>.toEventMetadata(): EventMetadata<E> {
    return EventMetadataParser.parse(this)
}

inline fun <reified E : Any> eventMetadata(): EventMetadata<E> {
    return E::class.java.toEventMetadata()
}
