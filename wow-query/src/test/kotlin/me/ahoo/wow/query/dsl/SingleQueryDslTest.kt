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

package me.ahoo.wow.query.dsl

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Sort
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class SingleQueryDslTest {

    @Test
    fun query() {
        val query = singleQuery {
            sort {
                "field1".asc()
            }
            condition {
                "field1" eq "value1"
            }
        }

        assertThat(query.sort, equalTo(listOf(Sort("field1", Sort.Direction.ASC))))
        assertThat(
            query.condition,
            equalTo(Condition.eq("field1", "value1"))
        )
    }
}
