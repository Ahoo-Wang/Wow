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

package me.ahoo.wow.api.read

import me.ahoo.wow.api.aggregate.AggregateType

/**
 * Marks a view of an aggregate's state: a class the state decodes into, which another context reads that state as.
 * An api module's generated `<State>View` carries it, naming the [aggregate] whose state it views by its qualified
 * name, `context.aggregate`; so [Queries.snapshots] of the view finds the aggregate type, in process and over HTTP
 * alike. A view is of one aggregate.
 *
 * A view holds the state's fields, those the producer requires as non-null and the others nullable, and reads a
 * state with fields it does not know: a field the producer adds later breaks no reader.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class StateView(val aggregate: String) {
    companion object {
        /** The aggregate type whose state [type] views: null when it is no view. */
        @JvmStatic
        fun typeOf(type: Class<*>): AggregateType? = type.getAnnotation(StateView::class.java)?.aggregate?.let { name ->
            val context = name.substringBefore('.', "")
            val aggregate = name.substringAfter('.')
            require(context.isNotEmpty() && aggregate.isNotEmpty() && '.' !in aggregate) {
                "The state view ${type.name} names $name, not an aggregate's qualified name, context.aggregate."
            }
            AggregateType(context, aggregate)
        }
    }
}
