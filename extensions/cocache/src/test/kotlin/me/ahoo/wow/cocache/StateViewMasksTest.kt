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

package me.ahoo.wow.cocache

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.api.query.annotation.SensitiveValue
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.cocache.api.StateKey
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.read.StateMasks
import me.ahoo.wow.test.query.MemoryQueries
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * A state view's `@Sensitive` fields in the cache (#6581): the cache holds what a read of the owner returns, so the
 * owner's sink masks a state as a read does, and an entry it writes is the entry a miss loads.
 */
class StateViewMasksTest {
    private val runtime = runtimeOn(InMemoryBackend())
    private val manifest = customerManifest()
    private val raw = """{"name":"Ann","phone":"13812345678","secret":"s3cr3t"}"""

    @AfterEach
    fun close() {
        runtime.close()
    }

    @Test
    fun `an entry the sink writes should be the entry a miss loads, both masked at both levels`() =
        runBlocking<Unit> {
            val owner = MemoryQueries(manifest, mapOf(CUSTOMER to CustomerView::class.java))
                .snapshot(CUSTOMER, "c-2", CustomerView("Ann", "13812345678", "s3cr3t"), tenantId = "t")
            val caches = StateViewCaches(CoCacheFactory(runtime)) { owner }
            WowCoCacheAutoConfiguration.sinkOf(CUSTOMER, CustomerView::class.java, null, caches, PayloadMasks(manifest))
                .write(listOf(recordOf(CUSTOMER.ref("t", "c-1"), 1, raw)))
            val cache = caches.of<CustomerView>()
            val written = cache.get(StateKey("t", "c-1"))
            val loaded = cache.get(StateKey("t", "c-2"))
            written.assert().isEqualTo(loaded)
            loaded.assert().isEqualTo(CustomerView("Ann", "138****5678", "******"))
        }

    /** Masks a value as `[<level>:<length>]`, so the test sees both the level and the raw value it was given. */
    object LevelMask : MaskStrategy {
        override fun mask(value: SensitiveValue): String = "[${value.level.name.lowercase()}:${value.value.length}]"
    }

    @Test
    fun `a custom mask strategy should be applied by the sink as by a read`() = runBlocking<Unit> {
        val custom = customerManifest(secretMask = mapOf("strategy" to LevelMask::class.java.name))
        val masks = PayloadMasks(custom, MaskStrategyResolver.of(mapOf(LevelMask::class.java.name to LevelMask)))
        val caches = StateViewCaches(CoCacheFactory(runtime)) { error("no miss is read") }
        WowCoCacheAutoConfiguration.sinkOf(CUSTOMER, CustomerView::class.java, null, caches, masks)
            .write(listOf(recordOf(CUSTOMER.ref("t", "c-1"), 1, raw)))
        val read = WowJson.mapper.treeToValue(
            masks.state(CUSTOMER, WowJson.mapper.readTree(raw)),
            CustomerView::class.java
        )
        caches.of<CustomerView>().get(StateKey("t", "c-1")).assert()
            .isEqualTo(read)
            .isEqualTo(CustomerView("Ann", "138****5678", "[confidential:6]"))
    }

    @Test
    fun `the sink should mask a copy, leaving the record's state as other subscriptions read it`() =
        runBlocking<Unit> {
            val caches = StateViewCaches(CoCacheFactory(runtime)) { error("no miss is read") }
            val record = recordOf(CUSTOMER.ref("t", "c-1"), 1, raw)
            WowCoCacheAutoConfiguration.sinkOf(CUSTOMER, CustomerView::class.java, null, caches, PayloadMasks(manifest))
                .write(listOf(record))
            record.state!!.state.assert().isEqualTo(WowJson.mapper.readTree(raw))
            caches.of<CustomerView>().get(StateKey("t", "c-1"))!!.phone.assert().isEqualTo("138****5678")
        }

    @Test
    fun `a type without sensitive fields should have no mask, its states written unchanged`() = runBlocking<Unit> {
        PayloadMasks(shopManifest()).maskOf(ORDER).assert().isNull()
        val caches = StateViewCaches(CoCacheFactory(runtime)) { error("no miss is read") }
        WowCoCacheAutoConfiguration.sinksOf(
            listOf("shop.order"),
            null,
            shopManifest(),
            mapOf(ORDER to OrderView::class.java),
            caches,
            PayloadMasks(shopManifest()),
        ).toString().assert().isEqualTo("PublishedSinks(wow.cocache-sink)")
        WowCoCacheAutoConfiguration.sinkOf(ORDER, OrderView::class.java, null, caches, PayloadMasks(shopManifest()))
            .write(listOf(recordOf(ORDER.ref("t", "o-1"), 1, """{"item":"13812345678","quantity":2}""")))
        caches.of<OrderView>().get(StateKey("t", "o-1")).assert().isEqualTo(OrderView("13812345678", 2))
    }

    @Test
    fun `a node that publishes states without state masks should fail the start, naming the key and the bean`() {
        val masks = StateMasks { null }
        WowCoCacheAutoConfiguration.masksOf(listOf("shop.order"), masks).assert().isSameAs(masks)
        assertThrows<WowConfigurationException> {
            WowCoCacheAutoConfiguration.masksOf(listOf("shop.order", "shop.customer"), null)
        }.message.assert().contains("wow.cocache.publish", "shop.order, shop.customer", StateMasks::class.java.name)
    }

    private fun publishing(contract: Manifest, type: String) = ApplicationContextRunner()
        .withClassLoader(ContractClassLoader(contract, javaClass.classLoader))
        .withConfiguration(
            AutoConfigurations.of(CoCacheAutoConfiguration::class.java, WowCoCacheAutoConfiguration::class.java),
        )
        .withBean(QueryApi::class.java, { mockk<QueryApi>() })
        .withBean(Manifest::class.java, { contract })
        .withPropertyValues("cocache.backend=memory", "wow.cocache.publish=$type")

    @Test
    fun `the owner's sinks should take the node's state masks, and fail the start without them, whatever the schema`() {
        // A type that masks nothing needs the masks too: whether it masks anything is the masks' to say.
        publishing(shopManifest(), "shop.order").run { context ->
            generateSequence(context.startupFailure) { it.cause }.filterIsInstance<WowConfigurationException>()
                .first().message.assert().contains("wow.cocache.publish", StateMasks::class.java.name)
        }
        publishing(shopManifest(), "shop.order").withBean(StateMasks::class.java, { StateMasks { null } })
            .run { context -> context.startupFailure.assert().isNull() }
        publishing(manifest, "shop.customer").withBean(PayloadMasks::class.java, { PayloadMasks(manifest) })
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBean(PublishedSinks::class.java).assert().isNotNull()
            }
    }
}
