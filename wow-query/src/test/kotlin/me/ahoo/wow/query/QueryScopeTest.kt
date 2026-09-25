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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.util.context.Context

class QueryScopeTest {
    @Test
    fun `scope accumulates with AND and never changes the original context`() {
        val original = Context.of("principal", "alice")
        val tenant = TenantIdFilter("tenant")
        val owner = OwnerIdFilter("owner")
        val scoped = original.withQueryScope(tenant).withQueryScope(owner).withQueryScope(MatchAllFilter)

        original.queryScope().assert().isSameAs(MatchAllFilter)
        scoped.queryScope().assert().isEqualTo(tenant.appendFilter(owner).appendFilter(MatchAllFilter))
        scoped.get<String>("principal").assert().isEqualTo("alice")
    }

    @Test
    fun `entry is unspecified until written and in-process queries drop the inherited scope and entry`() {
        val http = Context.of("principal", "alice").withQueryScope(TenantIdFilter("tenant"))
            .withQueryEntry(QueryEntry.HTTP)
        Context.empty().queryEntry().assert().isEqualTo(QueryEntry.UNSPECIFIED)
        http.queryEntry().assert().isEqualTo(QueryEntry.HTTP)

        val nested = http.forInProcessQuery()
        nested.queryEntry().assert().isEqualTo(QueryEntry.IN_PROCESS)
        nested.queryScope().assert().isSameAs(MatchAllFilter)
        nested.get<String>("principal").assert().isEqualTo("alice")
    }

    @Test
    fun `asInProcessQuery applies to the query it wraps`() {
        Mono.deferContextual { Mono.just(it.queryEntry() to it.queryScope()) }
            .asInProcessQuery()
            .contextWrite { it.withQueryScope(TenantIdFilter("tenant")).withQueryEntry(QueryEntry.HTTP) }
            .test()
            .expectNext(QueryEntry.IN_PROCESS to MatchAllFilter)
            .verifyComplete()
        Flux.deferContextual { Flux.just(it.queryEntry()) }
            .asInProcessQuery()
            .contextWrite { it.withQueryEntry(QueryEntry.HTTP) }
            .test()
            .expectNext(QueryEntry.IN_PROCESS)
            .verifyComplete()
    }
}
