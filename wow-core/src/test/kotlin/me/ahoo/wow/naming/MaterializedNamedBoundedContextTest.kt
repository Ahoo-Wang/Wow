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

package me.ahoo.wow.naming

import me.ahoo.wow.configuration.BoundedContext
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class MaterializedNamedBoundedContextTest {

    @Test
    fun isSameBoundedContext() {
        val context = "test-context".toNamedBoundedContext()
        assertThat(context.contextName, equalTo("test-context"))
        assertThat(context.isSameBoundedContext("test-context".toNamedBoundedContext()), equalTo(true))
    }

    @Test
    fun notSameBoundedContext() {
        val actual = "test-context".toNamedBoundedContext()
            .isSameBoundedContext("test-context-other".toNamedBoundedContext())
        assertThat(actual, equalTo(false))
    }

    @Test
    fun getContextAlias() {
        val actual = "test-context".toNamedBoundedContext().getContextAlias()
        assertThat(actual, equalTo("test-context"))
    }

    @Test
    fun getContextAliasIfNull() {
        val actual = "test-context".toNamedBoundedContext().getContextAlias(BoundedContext())
        assertThat(actual, equalTo("test-context"))
    }

    @Test
    fun getContextAliasIfEmpty() {
        val actual = "test-context".toNamedBoundedContext().getContextAlias(BoundedContext(""))
        assertThat(actual, equalTo("test-context"))
    }

    @Test
    fun getContextAliasIfNotEmpty() {
        val actual = "test-context".toNamedBoundedContext().getContextAlias(BoundedContext("alias"))
        assertThat(actual, equalTo("alias"))
    }
}
