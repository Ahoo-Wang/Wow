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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.DynamicDocument

@Deprecated("Use ResultPolicy for query result masking.")
interface DataMasker

@Deprecated("Use ResultPolicy for query result masking.")
interface DynamicDocumentMasker {
    fun mask(dynamicDocument: DynamicDocument): DynamicDocument
}

@Deprecated("Use ResultPolicy for query result masking.")
interface AggregateDynamicDocumentMasker : DynamicDocumentMasker, NamedAggregateDecorator

@Deprecated("Use ResultPolicy for query result masking.")
interface StateDynamicDocumentMasker : AggregateDynamicDocumentMasker

@Deprecated("Use ResultPolicy for query result masking.")
interface EventStreamDynamicDocumentMasker : AggregateDynamicDocumentMasker
