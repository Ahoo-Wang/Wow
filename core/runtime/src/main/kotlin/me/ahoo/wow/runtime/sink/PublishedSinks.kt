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

package me.ahoo.wow.runtime.sink

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.transport.TopicProducer

/**
 * The built-in sink subscriptions of a node (design 04 §4): one [SinkSubscription] per stream a context publishes
 * ([published], by context: the Manifest's `published`, decisions D58, D135), named `<processor>.<aggregate>`, each
 * writing to the sink [sinkOf] gives its stream, with or without the state as the stream says
 * ([PublishedManifest.includeState]).
 *
 * Give it to the node as a [SubscriptionFactory], beside the application's (`+`): each sink subscription is one of
 * its context's state readers ([stateReaders]), under its own name, of its aggregate type, so the context's executor
 * keeps the views it reads (decision D133). A context that publishes an aggregate it has no model of fails the
 * node's assembly.
 */
class PublishedSinks(
    private val published: Map<String, List<PublishedManifest>>,
    private val processor: String,
    private val sinkOf: (context: String, stream: PublishedManifest) -> Sink,
) : SubscriptionFactory {
    init {
        require(processor.isNotBlank()) { "The sinks are named after a processor." }
    }

    /** The name of the subscription that publishes [stream]: `<processor>.<aggregate>`. */
    fun nameOf(stream: PublishedManifest): String = "$processor.${stream.aggregate}"

    override fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> =
        stateReaders(context, models).values.flatMapTo(LinkedHashSet()) { it }

    override fun stateReaders(context: String, models: List<AggregateModel<*>>): Map<String, Set<AggregateType>> =
        published[context].orEmpty().associate { stream -> nameOf(stream) to setOf(typeOf(context, stream, models)) }

    override fun subscriptions(context: ContextNode): List<Subscription> =
        published[context.context].orEmpty().map { stream ->
            val name = nameOf(stream)
            SinkSubscription(
                name = name,
                type = typeOf(context.context, stream, context.models),
                sink = sinkOf(context.context, stream),
                reader = context.stateReader(name),
                includeState = stream.includeState,
            ).subscription
        }

    private fun typeOf(context: String, stream: PublishedManifest, models: List<AggregateModel<*>>): AggregateType {
        val type = AggregateType(context, stream.aggregate)
        require(models.any { it.type == type }) { "Context $context publishes $type, which it has no model of." }
        return type
    }

    override fun toString(): String = "PublishedSinks($processor)"

    companion object {
        /** The processor name of the Kafka Sink's subscriptions: `kafka-sink.<aggregate>`. */
        const val KAFKA_SINK: String = "kafka-sink"

        /** The streams [manifest]'s contexts publish, by context; a context that publishes none is left out. */
        fun publishedOf(manifest: Manifest): Map<String, List<PublishedManifest>> =
            manifest.contexts.filter { it.published.isNotEmpty() }.associate { it.name to it.published }

        /**
         * The Kafka Sink (decisions D58, D135): every stream [manifest] publishes, written to its topic
         * ([PublishedManifest.topic], after [topicPrefix]) through [producer].
         */
        fun topics(
            manifest: Manifest,
            producer: TopicProducer,
            topicPrefix: String = "",
            processor: String = KAFKA_SINK,
        ): PublishedSinks = PublishedSinks(publishedOf(manifest), processor) { _, stream ->
            TopicSink(producer, topicPrefix + stream.topic)
        }
    }
}
