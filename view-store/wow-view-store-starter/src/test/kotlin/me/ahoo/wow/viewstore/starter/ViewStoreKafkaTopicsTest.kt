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

package me.ahoo.wow.viewstore.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.kafka.AggregateTopicConverter
import me.ahoo.wow.kafka.CommandTopicConverter
import me.ahoo.wow.kafka.EventStreamTopicConverter
import me.ahoo.wow.kafka.StateEventTopicConverter
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.spring.boot.starter.bi.BiScriptAggregateExclusion
import me.ahoo.wow.spring.boot.starter.kafka.KafkaAutoConfiguration
import me.ahoo.wow.viewstore.ViewStoreService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * `wow.view-store.kafka.topic-prefix` moves the view store's topics and nothing else: the host's own aggregates keep
 * byte for byte the topics they have without it.
 */
class ViewStoreKafkaTopicsTest {
    private val hostAggregates: List<NamedAggregate> = listOf(
        MaterializedNamedAggregate("compensation-service", "execution_failed"),
        MaterializedNamedAggregate("example-service", "order"),
    )
    private val view = MaterializedNamedAggregate(ViewStoreService.SERVICE_NAME, ViewStoreService.VIEW_AGGREGATE_NAME)
    private val preferences = MaterializedNamedAggregate(
        ViewStoreService.SERVICE_NAME,
        ViewStoreService.VIEW_PREFERENCES_AGGREGATE_NAME
    )

    private fun runner(vararg properties: String): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
        .withUserConfiguration(ViewStoreAutoConfiguration.ViewStoreKafkaConfiguration::class.java)
        .withPropertyValues(
            "wow.kafka.bootstrap-servers=localhost:9092",
            "wow.command.bus.type=in_memory",
            "wow.event.bus.type=in_memory",
            "wow.eventsourcing.state.bus.type=in_memory",
            *properties,
        )

    private fun AssertableApplicationContext.converters(): List<AggregateTopicConverter> = listOf(
        getBean(CommandTopicConverter::class.java),
        getBean(EventStreamTopicConverter::class.java),
        getBean(StateEventTopicConverter::class.java),
    )

    private fun topics(vararg properties: String, aggregates: List<NamedAggregate>): List<String> {
        var topics: List<String> = emptyList()
        runner(*properties).run { context ->
            topics = context.converters().flatMap { converter -> aggregates.map { converter.convert(it) } }
        }
        return topics
    }

    @Test
    fun `without the setting every topic is the host's`() {
        topics(aggregates = listOf(view, preferences)).assert().containsExactly(
            "wow.view-store.view.command",
            "wow.view-store.view_preferences.command",
            "wow.view-store.view.event",
            "wow.view-store.view_preferences.event",
            "wow.view-store.view.state",
            "wow.view-store.view_preferences.state",
        )
        runner().run { context ->
            context.getBeansOfType(ViewStoreTopicConverterPostProcessor::class.java).assert().isEmpty()
            context.getBeansOfType(BiScriptAggregateExclusion::class.java).assert().isEmpty()
        }
    }

    /** A blank prefix is no prefix: the topics stay the host's, and the BI script keeps the view store. */
    @Test
    fun `a blank setting is no setting`() {
        val without = topics(aggregates = listOf(view, preferences) + hostAggregates)
        listOf("", "  ").forEach { blank ->
            topics("wow.view-store.kafka.topic-prefix=$blank", aggregates = listOf(view, preferences) + hostAggregates)
                .assert().isEqualTo(without)
            runner("wow.view-store.kafka.topic-prefix=$blank").run { context ->
                context.getBeansOfType(ViewStoreTopicConverterPostProcessor::class.java).assert().isEmpty()
                context.getBeansOfType(BiScriptAggregateExclusion::class.java).assert().isEmpty()
            }
        }
        assertThrows<IllegalArgumentException> { ViewStoreTopicConverterPostProcessor(" ") }
    }

    /** BI reads every topic under its one prefix, which does not name the view store's own: it leaves them out. */
    @Test
    fun `the setting leaves the view store out of the BI script`() {
        runner("wow.view-store.kafka.topic-prefix=wow.compensation-service.").run { context ->
            val exclusion = context.getBean(BiScriptAggregateExclusion::class.java)
            exclusion.excludes(view).assert().isTrue()
            exclusion.excludes(preferences).assert().isTrue()
            hostAggregates.forEach { exclusion.excludes(it).assert().isFalse() }
        }
    }

    @Test
    fun `the setting moves the view store's topics only`() {
        topics(
            "wow.view-store.kafka.topic-prefix=wow.compensation-service.",
            aggregates = listOf(view, preferences),
        ).assert().containsExactly(
            "wow.compensation-service.view-store.view.command",
            "wow.compensation-service.view-store.view_preferences.command",
            "wow.compensation-service.view-store.view.event",
            "wow.compensation-service.view-store.view_preferences.event",
            "wow.compensation-service.view-store.view.state",
            "wow.compensation-service.view-store.view_preferences.state",
        )
    }

    @Test
    fun `the host's topics are byte-identical with and without the setting`() {
        val without = topics(aggregates = hostAggregates)
        without.assert().hasSize(6).allMatch { it.startsWith("wow.") }
        topics("wow.view-store.kafka.topic-prefix=wow.compensation-service.", aggregates = hostAggregates)
            .assert().isEqualTo(without)

        val withHostPrefix = topics("wow.kafka.topic-prefix=host.", aggregates = hostAggregates)
        topics(
            "wow.kafka.topic-prefix=host.",
            "wow.view-store.kafka.topic-prefix=views.",
            aggregates = hostAggregates,
        ).assert().isEqualTo(withHostPrefix)
    }

    @Test
    fun `a converter of the host's own is kept for the host's aggregates`() {
        val host = object : CommandTopicConverter {
            override fun convert(namedAggregate: NamedAggregate): String = "host-${namedAggregate.aggregateName}"
        }
        val wrapped = ViewStoreTopicConverterPostProcessor("views.")
            .postProcessAfterInitialization(host, "hostCommandTopicConverter") as CommandTopicConverter
        wrapped.convert(hostAggregates.first()).assert().isEqualTo("host-execution_failed")
        wrapped.convert(view).assert().isEqualTo("views.view-store.view.command")
        ViewStoreTopicConverterPostProcessor("other.")
            .postProcessAfterInitialization(wrapped, "again").assert().isSameAs(wrapped)
        ViewStoreTopicConverterPostProcessor("views.")
            .postProcessAfterInitialization("not a converter", "bean").assert().isEqualTo("not a converter")
    }

    /**
     * A host bean that is several converters at once cannot say which kind it is asked for: the starter refuses it at
     * startup rather than put the view store on the wrong topics.
     */
    @Test
    fun `a converter of several kinds fails fast`() {
        class CommandAndEvents : CommandTopicConverter, EventStreamTopicConverter {
            override fun convert(namedAggregate: NamedAggregate): String = "host-${namedAggregate.aggregateName}"
        }
        val error = assertThrows<IllegalStateException> {
            ViewStoreTopicConverterPostProcessor("views.")
                .postProcessAfterInitialization(CommandAndEvents(), "hostTopicConverter")
        }
        error.message.assert()
            .contains("hostTopicConverter")
            .contains("CommandTopicConverter, EventStreamTopicConverter")
            .contains("wow.view-store.kafka.topic-prefix")

        runner("wow.view-store.kafka.topic-prefix=views.")
            .withBean("hostTopicConverter", CommandAndEvents::class.java, { CommandAndEvents() })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("hostTopicConverter")
            }
        runner().withBean("hostTopicConverter", CommandAndEvents::class.java, { CommandAndEvents() })
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `each single-kind converter is wrapped as its kind`() {
        val event = object : EventStreamTopicConverter {
            override fun convert(namedAggregate: NamedAggregate): String = "host-event"
        }
        val wrappedEvent = ViewStoreTopicConverterPostProcessor("views.")
            .postProcessAfterInitialization(event, "event") as EventStreamTopicConverter
        wrappedEvent.convert(view).assert().isEqualTo("views.view-store.view.event")
        wrappedEvent.convert(hostAggregates.last()).assert().isEqualTo("host-event")

        val stateOnly = object : StateEventTopicConverter {
            override fun convert(namedAggregate: NamedAggregate): String = "host-state"
        }
        val wrappedState = ViewStoreTopicConverterPostProcessor("views.")
            .postProcessAfterInitialization(stateOnly, "state") as StateEventTopicConverter
        wrappedState.convert(preferences).assert().isEqualTo("views.view-store.view_preferences.state")
        wrappedState.convert(hostAggregates.last()).assert().isEqualTo("host-state")

        val plain = AggregateTopicConverter { "plain" }
        ViewStoreTopicConverterPostProcessor("views.").postProcessAfterInitialization(plain, "plain")
            .assert().isSameAs(plain)
    }
}
