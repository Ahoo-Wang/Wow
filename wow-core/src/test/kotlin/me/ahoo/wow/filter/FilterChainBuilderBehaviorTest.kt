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

package me.ahoo.wow.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class FilterChainBuilderBehaviorTest {

    @Test
    fun `build orders filters and terminates with empty chain`() {
        val chain = FilterChainBuilder<MutableList<String>>()
            .addFilter(LastRecordingFilter())
            .addFilter(MiddleRecordingFilter())
            .addFilter(FirstRecordingFilter())
            .build()
        val context = mutableListOf<String>()

        StepVerifier.create(chain.filter(context))
            .verifyComplete()

        context.assert().isEqualTo(listOf("first", "middle", "last"))
    }

    @Test
    fun `build with no filters returns terminal empty chain`() {
        val chain = FilterChainBuilder<String>().build()

        StepVerifier.create(chain.filter("context"))
            .verifyComplete()
    }

    @Test
    fun `typed condition includes matching filters and filters without type annotation`() {
        val chain = FilterChainBuilder<MutableList<String>>()
            .addFilter(OtherTypedFilter())
            .addFilter(SpecialTypedFilter())
            .addFilter(UntypedFilter())
            .filterCondition(SpecialFilterMarker::class)
            .build()
        val context = mutableListOf<String>()

        StepVerifier.create(chain.filter(context))
            .verifyComplete()

        context.assert().isEqualTo(listOf("special", "untyped"))
    }
}

@Order(ORDER_FIRST)
private class FirstRecordingFilter : Filter<MutableList<String>> {
    override fun filter(context: MutableList<String>, next: FilterChain<MutableList<String>>): Mono<Void> {
        context.add("first")
        return next.filter(context)
    }
}

private class MiddleRecordingFilter : Filter<MutableList<String>> {
    override fun filter(context: MutableList<String>, next: FilterChain<MutableList<String>>): Mono<Void> {
        context.add("middle")
        return next.filter(context)
    }
}

@Order(ORDER_LAST)
private class LastRecordingFilter : Filter<MutableList<String>> {
    override fun filter(context: MutableList<String>, next: FilterChain<MutableList<String>>): Mono<Void> {
        context.add("last")
        return next.filter(context)
    }
}

private interface SpecialFilterMarker
private interface OtherFilterMarker

@FilterType(SpecialFilterMarker::class)
private class SpecialTypedFilter : Filter<MutableList<String>> {
    override fun filter(context: MutableList<String>, next: FilterChain<MutableList<String>>): Mono<Void> {
        context.add("special")
        return next.filter(context)
    }
}

@FilterType(OtherFilterMarker::class)
private class OtherTypedFilter : Filter<MutableList<String>> {
    override fun filter(context: MutableList<String>, next: FilterChain<MutableList<String>>): Mono<Void> {
        context.add("other")
        return next.filter(context)
    }
}

private class UntypedFilter : Filter<MutableList<String>> {
    override fun filter(context: MutableList<String>, next: FilterChain<MutableList<String>>): Mono<Void> {
        context.add("untyped")
        return next.filter(context)
    }
}
