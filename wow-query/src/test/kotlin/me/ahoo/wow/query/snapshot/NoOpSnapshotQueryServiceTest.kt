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

package me.ahoo.wow.query.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class NoOpSnapshotQueryServiceTest {
    private val queryService = NoOpSnapshotQueryServiceFactory.create<Any>("test.test".toNamedAggregate())

    @Test
    fun name() {
        queryService.name.assert().isEqualTo("no_op")
    }

    @Test
    fun aggregate() {
        queryService.namedAggregate.assert().isEqualTo("test.test".toNamedAggregate())
    }

    @Test
    fun singleQuery() {
        me.ahoo.wow.query.dsl.singleQuery {
            condition {
                "test" eq "test"
            }
        }.query(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun singleDynamicQuery() {
        me.ahoo.wow.query.dsl.singleQuery {
            condition {
                "test" eq "test"
            }
        }.dynamicQuery(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun query() {
        listQuery {
            condition {
                "test" eq "test"
            }
        }.query(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun listDynamicQuery() {
        listQuery {
            condition {
                "test" eq "test"
            }
        }.dynamicQuery(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun paged() {
        pagedQuery {
            condition {
                "test" eq "test"
            }
        }.query(queryService)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun pagedDynamicQuery() {
        pagedQuery {
            condition {
                "test" eq "test"
            }
        }.dynamicQuery(queryService)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        condition {
            "test" eq "test"
        }.count(queryService)
            .test()
            .expectNext(0L)
            .verifyComplete()
    }
}
