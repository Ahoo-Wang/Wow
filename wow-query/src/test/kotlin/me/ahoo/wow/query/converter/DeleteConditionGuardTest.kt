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

package me.ahoo.wow.query.converter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.query.converter.DeleteConditionGuard.guard
import me.ahoo.wow.query.dsl.condition
import org.junit.jupiter.api.Test

class DeleteConditionGuardTest {

    @Test
    fun guardAll() {
        val condition = condition { }
        val converted = condition.guard()
        converted.assert().isEqualTo(Condition.ACTIVE)
    }

    @Test
    fun guardDeleted() {
        val condition = condition { deleted(DeletionState.DELETED) }
        val converted = condition.guard()
        converted.assert().isEqualTo(condition)
    }

    @Test
    fun guardAndWithDeleted() {
        val condition = condition {
            deleted(DeletionState.DELETED)
            "field" eq "hi"
        }
        val converted = condition.guard()
        converted.assert().isEqualTo(condition)
    }

    @Test
    fun guardAndWithOutDeleted() {
        val condition = condition {
            "field1" eq "hi"
            "field2" eq "hi"
        }
        val converted = condition.guard()
        converted.children.first().assert().isEqualTo(Condition.ACTIVE)
    }

    @Test
    fun guardEq() {
        val condition = condition {
            "field" eq "hi"
        }
        val converted = condition.guard()
        converted.children.first().assert().isEqualTo(Condition.ACTIVE)
    }
}
