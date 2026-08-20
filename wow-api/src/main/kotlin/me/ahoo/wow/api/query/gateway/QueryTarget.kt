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

package me.ahoo.wow.api.query.gateway

import me.ahoo.wow.api.modeling.NamedAggregate

enum class QueryDocumentKind {
    SNAPSHOT,
    EVENT_STREAM
}

class QueryTarget(
    namedAggregate: NamedAggregate,
    val documentKind: QueryDocumentKind
) {
    val namedAggregate: NamedAggregate = SnapshotNamedAggregate(
        contextName = namedAggregate.contextName.also {
            require(it.isNotBlank()) { "contextName cannot be blank." }
        },
        aggregateName = namedAggregate.aggregateName.also {
            require(
                it.isNotBlank()
            ) { "aggregateName cannot be blank." }
        }
    )

    operator fun component1(): NamedAggregate = namedAggregate

    operator fun component2(): QueryDocumentKind = documentKind

    fun copy(
        namedAggregate: NamedAggregate = this.namedAggregate,
        documentKind: QueryDocumentKind = this.documentKind
    ): QueryTarget = QueryTarget(namedAggregate, documentKind)

    override fun equals(other: Any?): Boolean = other is QueryTarget &&
        namedAggregate.contextName == other.namedAggregate.contextName &&
        namedAggregate.aggregateName == other.namedAggregate.aggregateName &&
        documentKind == other.documentKind

    override fun hashCode(): Int {
        var result = namedAggregate.contextName.hashCode()
        result = 31 * result + namedAggregate.aggregateName.hashCode()
        return 31 * result + documentKind.hashCode()
    }

    override fun toString(): String = "QueryTarget(namedAggregate=$namedAggregate, documentKind=$documentKind)"
}

private data class SnapshotNamedAggregate(
    override val contextName: String,
    override val aggregateName: String
) : NamedAggregate
