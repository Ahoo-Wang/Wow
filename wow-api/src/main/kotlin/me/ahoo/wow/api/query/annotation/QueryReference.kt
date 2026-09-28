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
package me.ahoo.wow.api.query.annotation

/**
 * Declares a field (or the elements of an array field) the id of an aggregate, so query consumers can look the
 * aggregate up and link to it: `@field:QueryReference("member")`. [contextName] defaults to the bounded context of
 * the model that declares the field. A property of type `AggregateId` needs no annotation: its `aggregateId` is
 * a reference to the aggregate its own `contextName` and `aggregateName` name.
 * A field has one semantic type, so this cannot combine with [QueryTemporal], [QueryDecimal], [QueryMoney] or
 * [QueryDuration].
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class QueryReference(
    val aggregateName: String,
    /** The referenced aggregate's bounded context; empty, the default, is the declaring model's own. */
    val contextName: String = "",
)
