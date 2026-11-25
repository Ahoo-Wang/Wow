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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.ParameterizedType

/**
 * `SnapshotProperties.access(name)` is offered as the access attribute's field (`guide/queries.md`: "is the access
 * attribute `access.<name>`"), and the filter DSL compares a `QueryField<T>` with a `T` (`FilterDsl`'s `eq`, `ne`, `gt`,
 * `isIn`, ...: `infix fun <T> QueryField<T>.eq(value: T)`). `access` and `schedule` return `QueryField<*>`, whose star
 * projection the compiler refuses as such a receiver, so the documented use does not compile:
 *
 * ```kotlin
 * filter { SnapshotProperties.access("region") eq "EU" }
 * // receiver type 'QueryField<*>' contains star projection which prohibits the use of 'fun <T> QueryField<T>.eq(value: T)'
 * ```
 *
 * A failing test cannot hold a compile error without breaking this source set, so it reads the declared return type:
 * `QueryField<Any?>`, as `ACCESS.append(...)` and every other system field (`STATE`) give, compares; `QueryField<*>`
 * does not.
 */
class R2SnapshotPropertiesTypedTest {
    private fun typeArgumentOf(name: String): java.lang.reflect.Type =
        (SnapshotProperties::class.java.getMethod(name, String::class.java).genericReturnType as ParameterizedType)
            .actualTypeArguments.single()

    @Test
    fun `an access attribute's field should be comparable in the filter DSL, its type not a star projection`() {
        typeArgumentOf("access").assert().isEqualTo(Any::class.java)
    }

    @Test
    fun `a schedule's field should be comparable in the filter DSL, its type not a star projection`() {
        typeArgumentOf("schedule").assert().isEqualTo(Any::class.java)
    }
}
