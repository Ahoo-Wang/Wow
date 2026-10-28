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

package me.ahoo.wow.spi.transport

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.config.ProviderOption
import kotlin.time.Duration.Companion.seconds

/**
 * The settings every topic transport has, whatever its type (`wow.<type>.*`): how the node names and uses its topics,
 * not how a client reaches its brokers. The host reads them for the node's topics; a transport's [options] add them
 * to its own ([TransportProvider.options]), so no transport declares them again or forgets one.
 */
@WowSpi
object TopicTransportOptions {
    /** A prefix of every topic name, for a cluster several services or environments share. */
    @JvmField
    val TOPIC_PREFIX: ProviderOption.Defaulted<String> = ProviderOption.string(
        "topic-prefix",
        "put before every topic name, such as staging. for staging.wow.shop.order.state; none by default, so the " +
            "topics are the Manifest's. The sinks write and the subscriptions read under it.",
        "",
    )

    /** Whether the node takes commands from its contexts' command topics. */
    @JvmField
    val COMMAND_INTAKE: ProviderOption.Defaulted<Boolean> = ProviderOption.boolean(
        "command-intake.enabled",
        "whether each context the node hosts executes the commands other contexts send to its command topic, " +
            "wow.<context>.commands. A context with an aggregate type routed through topics takes them whatever this says.",
        false,
    )

    /** How the transport manages the node's topics, over `wow.ddl`. */
    @JvmField
    val DDL: ProviderOption<Ddl> = ProviderOption.enumeration(
        "ddl",
        "how the node manages the topics its contexts own at its start: auto creates the missing ones, then " +
            "validates them; validate creates none and refuses the start when one is missing or a command topic does " +
            "not have exactly wow.log.partitions partitions; none creates and checks nothing. wow.ddl when not set.",
        Ddl::class.java,
    )

    /** How often the node reads the partitions of its topics again. */
    @JvmField
    val PARTITION_REFRESH: ProviderOption.Defaulted<kotlin.time.Duration> = ProviderOption.duration(
        "topics.partition-refresh",
        "how often the node looks for partitions added to the topics it reads; new partitions are read within about " +
            "twice this.",
        30.seconds,
    )

    /** Every setting a topic transport has. */
    @JvmField
    val ALL: List<ProviderOption<*>> = listOf(TOPIC_PREFIX, COMMAND_INTAKE, DDL, PARTITION_REFRESH)

    /** The options of [provider]: these settings, then its own. */
    @JvmStatic
    fun of(provider: TransportProvider): List<ProviderOption<*>> = ALL + provider.options
}
