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
package me.ahoo.wow.api.modeling

/**
 * Interface combining named aggregate capabilities with type information.
 *
 * This interface represents an aggregate that has both a name (within a bounded context)
 * and type information, providing complete identification and typing for aggregates.
 *
 * @param A The type of the aggregate.
 */
interface NamedTypedAggregate<A : Any> :
    TypedAggregate<A>,
    NamedAggregate
