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

package me.ahoo.wow.query.event

import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.dsl.listQuery
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class NoOpSnapshotQueryServiceFactoryTest {
    private val queryService = NoOpEventStreamQueryServiceFactory.create("test.test".toNamedAggregate())

    @Test
    fun aggregate() {
        assertThat(queryService.namedAggregate, equalTo("test.test".toNamedAggregate()))
    }

    @Test
    fun list() {
        listQuery {
            condition {
                "test" eq "test"
            }
        }.query(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun dynamicList() {
        listQuery {
            condition {
                "test" eq "test"
            }
        }.dynamicQuery(queryService)
            .test()
            .verifyComplete()
    }
}
