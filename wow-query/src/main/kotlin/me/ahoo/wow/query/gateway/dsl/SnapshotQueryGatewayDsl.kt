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

package me.ahoo.wow.query.gateway.dsl

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.QueryPage
import me.ahoo.wow.query.gateway.SnapshotQueryGateway
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

fun <S : Any> SnapshotQueryGateway<S>.first(
    block: SnapshotQueryDsl.() -> Unit
): Mono<MaterializedSnapshot<S>> = first(SnapshotQueryDsl().apply(block).build())

fun <S : Any> SnapshotQueryGateway<S>.firstRecord(
    block: SnapshotRecordQueryDsl.() -> Unit
): Mono<ObjectNode> = firstRecord(SnapshotRecordQueryDsl().apply(block).build())

fun <S : Any> SnapshotQueryGateway<S>.stream(
    block: SnapshotQueryDsl.() -> Unit
): Flux<MaterializedSnapshot<S>> = stream(SnapshotQueryDsl().apply(block).build())

fun <S : Any> SnapshotQueryGateway<S>.stream(
    limit: Int,
    block: SnapshotQueryDsl.() -> Unit
): Flux<MaterializedSnapshot<S>> = stream(SnapshotQueryDsl().apply(block).build(), limit)

fun <S : Any> SnapshotQueryGateway<S>.streamRecords(
    block: SnapshotRecordQueryDsl.() -> Unit
): Flux<ObjectNode> = streamRecords(SnapshotRecordQueryDsl().apply(block).build())

fun <S : Any> SnapshotQueryGateway<S>.streamRecords(
    limit: Int,
    block: SnapshotRecordQueryDsl.() -> Unit
): Flux<ObjectNode> = streamRecords(SnapshotRecordQueryDsl().apply(block).build(), limit)

fun <S : Any> SnapshotQueryGateway<S>.page(
    page: Int,
    size: Int,
    block: SnapshotQueryDsl.() -> Unit
): Mono<QueryPage<MaterializedSnapshot<S>>> = page(SnapshotQueryDsl().apply(block).build(), page, size)

fun <S : Any> SnapshotQueryGateway<S>.pageRecords(
    page: Int,
    size: Int,
    block: SnapshotRecordQueryDsl.() -> Unit
): Mono<QueryPage<ObjectNode>> = pageRecords(SnapshotRecordQueryDsl().apply(block).build(), page, size)

fun <S : Any> SnapshotQueryGateway<S>.count(
    block: SnapshotCountQueryDsl.() -> Unit
): Mono<Long> {
    val query = SnapshotCountQueryDsl().apply(block).build()
    return count(query.filter, query.scope, query.budget)
}
