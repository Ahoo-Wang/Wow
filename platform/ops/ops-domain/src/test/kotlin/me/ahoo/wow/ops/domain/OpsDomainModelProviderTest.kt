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

package me.ahoo.wow.ops.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.ManifestTree
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * What the compiler generated for the ops domain: the `parked` aggregate in the context `ops`, whose commands the
 * services' runtimes send by name (decision D141), and the saga that sends an operator's actions back.
 */
class OpsDomainModelProviderTest {
    private val provider = ModelProvider.load().single()

    @Test
    fun `the binding index should build the parked model of the ops context`() {
        provider.context.assert().isEqualTo(Ledger.OPS_CONTEXT)
        provider.models(Dependencies.of()).map { it.type.toString() }.assert().containsExactly("ops.parked")
        val model = provider.models(Dependencies.of()).single()
        model.findCommandType("ops.parked.${Ledger.RECORD_PARKED}").assert().isNotNull()
        model.findCommandType("ops.parked.${Ledger.RECORD_RESOLVED}").assert().isNotNull()
        provider.processors(
            Dependencies.of()
        ).map { it.name }.assert().containsExactly("ParkedOperationSaga.onParkedChanged")
    }

    @Test
    fun `the fragment should name the records' commands and the members the runtime sends`() {
        val tree = checkNotNull(javaClass.classLoader.getResourceAsStream(provider.fragment))
            .use { JsonMapper.builder().build().readValue(it, Map::class.java) }
        val aggregate = ManifestTree.read(tree).contexts.single().aggregates.single()
        aggregate.name.assert().isEqualTo(Ledger.PARKED)
        aggregate.commands.map { it.name }.assert().contains(
            Ledger.RECORD_PARKED,
            Ledger.RECORD_RESOLVED,
            "Retry",
            "Skip"
        )
        me.ahoo.wow.ops.api.parked.RecordParked::class.java.declaredFields.map { it.name }.assert().contains(
            Ledger.ENTRY
        )
        me.ahoo.wow.ops.api.parked.RecordResolved::class.java.declaredFields.map { it.name }
            .assert().contains(Ledger.RESOLUTION)
    }
}
