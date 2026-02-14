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

package me.ahoo.wow.elasticsearch

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.messaging.dispatcher.SafeSubscriber
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonString
import org.springframework.core.io.ClassPathResource
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.document.Document
import org.springframework.data.elasticsearch.core.index.PutIndexTemplateRequest
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode

class IndexTemplateInitializer(private val elasticsearchOperations: ReactiveElasticsearchOperations) {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val EVENT_STREAM_TEMPLATE_NAME = "wow-event-stream-template"
        private const val SNAPSHOT_TEMPLATE_NAME = "wow-snapshot-template"
        private const val INDEX_PATTERNS_KEY = "index_patterns"
        private const val TEMPLATE_KEY = "template"
        private const val MAPPINGS_KEY = "mappings"
    }

    private val eventStreamTemplate: JsonNode =
        ClassPathResource("templates/$EVENT_STREAM_TEMPLATE_NAME.json").inputStream.use {
            JsonSerializer.readValue(it, JsonNode::class.java)
        }
    private val snapshotTemplate: JsonNode =
        ClassPathResource("templates/$SNAPSHOT_TEMPLATE_NAME.json").inputStream.use {
            JsonSerializer.readValue(it, JsonNode::class.java)
        }

    fun initEventStreamTemplate(): Mono<Boolean> {
        return initTemplate(EVENT_STREAM_TEMPLATE_NAME, eventStreamTemplate)
    }

    fun initSnapshotTemplate(): Mono<Boolean> {
        return initTemplate(SNAPSHOT_TEMPLATE_NAME, snapshotTemplate)
    }

    fun initTemplate(name: String, template: JsonNode): Mono<Boolean> {
        log.info {
            "initTemplate - name:$name ."
        }
        val indexPatterns = template.get(INDEX_PATTERNS_KEY).map {
            it.asText()
        }.toList().toTypedArray()
        val mappings = template.get(TEMPLATE_KEY).get(MAPPINGS_KEY).toJsonString().let {
            Document.parse(it)
        }
        val putIndexTemplateRequest = PutIndexTemplateRequest.builder()
            .withName(name)
            .withIndexPatterns(*indexPatterns)
            .withMapping(mappings)
            .build()
        return elasticsearchOperations.indexOps(IndexCoordinates.of(name))
            .putIndexTemplate(putIndexTemplateRequest)
    }

    fun initAll() {
        initEventStreamTemplate().subscribe(InitSubscriber("InitEventStreamTemplate"))
        initSnapshotTemplate().subscribe(InitSubscriber("InitSnapshotTemplate"))
    }

    class InitSubscriber(override val name: String) : SafeSubscriber<Boolean>()
}
