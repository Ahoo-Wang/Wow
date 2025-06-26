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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.query.snapshot.nestedState
import org.junit.jupiter.api.Test

class ProjectionDslTest {

    @Test
    fun build() {
        val projection = projection {
            include("field1")
            exclude("field2")
        }
        projection.assert().isEqualTo(
            Projection(
                include = listOf("field1"),
                exclude = listOf("field2")
            )
        )
    }

    @Test
    fun buildWithState() {
        val projection = projection {
            nestedState()
            include("field1")
            exclude("field2")
        }
        projection.assert().isEqualTo(
            Projection(
                include = listOf("state.field1"),
                exclude = listOf("state.field2")
            )
        )
    }
}
