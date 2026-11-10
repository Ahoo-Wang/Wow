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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.http.ops.WebhookNotifier
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.spring.boot.WowProperties.Switch
import java.net.URI
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * The operator's side of the parked entries: the webhook told when an entry parks, and the failure ledger published
 * to the ops context. Their routes are the edge's, `wow.http.ops.enabled`.
 */
@InternalWowApi
class OpsProperties {
    /** The webhook told when an entry parks or spends its retries. */
    var webhook: WebhookProperties = WebhookProperties()

    /** Publishing the failure ledger to the ops context. */
    var publish: PublishProperties = PublishProperties()
}

/**
 * Publishing the failure ledger to the ops context, so one console sees the parked entries of every service: each
 * entry goes to the `parked` aggregate of the ops context through its command topic, which needs a topic transport.
 */
@InternalWowApi
class PublishProperties {
    /**
     * Whether the node publishes its failure ledger to the ops context: `auto` (the default) while the ops context's
     * command topic exists on the node's transport, which the ops service creates once it is deployed, checked at
     * start and every `wow.kafka.topics.partition-refresh`, and not at all without a transport; `true` always, which
     * needs a transport; `false` never.
     */
    var enabled: Switch = Switch.AUTO

    /** The ops context the ledger is published to. */
    var context: String = Ledger.OPS_CONTEXT

    /** How often the node scans its ledger and publishes it; an entry is also published at once when it parks. */
    var interval: Duration = LedgerPublishing.DEFAULT_INTERVAL.toJavaDuration()

    /** Where the node publishes its ledger, null when it does not; [transport] is whether it has one. */
    fun options(transport: Boolean): LedgerPublishing? = when (enabled) {
        Switch.TRUE -> LedgerPublishing(context, interval.toKotlinDuration())
        Switch.AUTO -> LedgerPublishing(context, interval.toKotlinDuration(), automatic = true).takeIf { transport }
        Switch.FALSE -> null
    }
}

/**
 * The webhook of the parked entries, told when an entry parks or spends its retries.
 */
@InternalWowApi
class WebhookProperties {
    /** The URL the webhook posts to; none is posted when not set. */
    var url: URI? = null

    /** The body's format: `generic`, Wow's JSON, or `wecom`, a WeCom robot's Markdown message. */
    var format: WebhookNotifier.Format = WebhookNotifier.Format.GENERIC

    /**
     * The events posted: `parked`, an entry parked, and `exhausted`, an entry spent its retries; both by default.
     */
    var events: MutableSet<ParkedNotice.Kind> = ParkedNotice.Kind.entries.toCollection(LinkedHashSet())

    /** How long one post may take. */
    var timeout: Duration = WebhookNotifier.DEFAULT_TIMEOUT.toJavaDuration()

    /** The headers added to each post, such as a token the receiver checks. */
    var headers: MutableMap<String, String> = LinkedHashMap()
}
