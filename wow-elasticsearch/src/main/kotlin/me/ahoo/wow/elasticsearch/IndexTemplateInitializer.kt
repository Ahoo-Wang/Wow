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
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonString
import org.springframework.core.io.ClassPathResource
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.document.Document
import org.springframework.data.elasticsearch.core.index.PutIndexTemplateRequest
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode

/**
 * Puts Wow's event stream and snapshot index templates. [indexNaming] prefixes the template names and their
 * `index_patterns`; with [ElasticsearchIndexNaming.DEFAULT] they are `wow-event-stream-template` (`wow.*.es`) and
 * `wow-snapshot-template` (`wow.*.snapshot`).
 */
class IndexTemplateInitializer(
    private val elasticsearchOperations: ReactiveElasticsearchOperations,
    private val indexNaming: ElasticsearchIndexNaming,
) {
    /** The constructor from before the index prefix, kept for binary compatibility: Wow's unprefixed names. */
    constructor(elasticsearchOperations: ReactiveElasticsearchOperations) :
        this(elasticsearchOperations, ElasticsearchIndexNaming.DEFAULT)

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
    private val eventStreamTemplateName = indexNaming.resolve(EVENT_STREAM_TEMPLATE_NAME)
    private val snapshotTemplateName = indexNaming.resolve(SNAPSHOT_TEMPLATE_NAME)

    fun initEventStreamTemplate(): Mono<Boolean> {
        return putTemplate(eventStreamTemplateName, eventStreamTemplate, indexNaming)
    }

    fun initSnapshotTemplate(): Mono<Boolean> {
        return putTemplate(snapshotTemplateName, snapshotTemplate, indexNaming)
    }

    /** Puts [template] under [name] as it is: its `index_patterns` are not prefixed. */
    fun initTemplate(name: String, template: JsonNode): Mono<Boolean> {
        return putTemplate(name, template, ElasticsearchIndexNaming.DEFAULT)
    }

    private fun putTemplate(name: String, template: JsonNode, naming: ElasticsearchIndexNaming): Mono<Boolean> {
        log.info {
            "initTemplate - name:$name ."
        }
        val indexPatterns = template.get(INDEX_PATTERNS_KEY).values().map {
            naming.resolve(it.asString())
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
        ensureAllTemplates().block()
    }

    fun ensureEventStreamTemplate(): Mono<Void> {
        return initEventStreamTemplate().requireAcknowledged(eventStreamTemplateName)
    }

    fun ensureSnapshotTemplate(): Mono<Void> {
        return initSnapshotTemplate().requireAcknowledged(snapshotTemplateName)
    }

    fun ensureAllTemplates(): Mono<Void> {
        return ensureEventStreamTemplate().then(Mono.defer(::ensureSnapshotTemplate))
    }

    private fun Mono<Boolean>.requireAcknowledged(templateName: String): Mono<Void> {
        return switchIfEmpty(
            Mono.error(
                IllegalStateException("Elasticsearch index template [$templateName] returned no acknowledgement."),
            ),
        ).flatMap { acknowledged ->
            if (acknowledged) {
                Mono.empty()
            } else {
                Mono.error(
                    IllegalStateException("Elasticsearch index template [$templateName] was not acknowledged."),
                )
            }
        }
    }
}
