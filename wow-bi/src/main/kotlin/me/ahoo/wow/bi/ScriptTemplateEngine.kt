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

package me.ahoo.wow.bi

import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import gg.jte.resolve.ResourceCodeResolver
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.NamedAggregate

object ScriptTemplateEngine {
    private const val TEMPLATE_ROOT = "clickhouse"
    private const val TEMPLATE_SUFFIX = ".kte"
    private const val NAMED_AGGREGATE = "namedAggregate"
    private const val EXPANSION_TABLES = "expansionTables"
    private const val TOPIC_PREFIX = "topicPrefix"
    private const val MESSAGE_HEADER_SQL_TYPE = "headerSqlType"
    private const val KAFKA_BOOTSTRAP_SERVERS = "kafkaBootstrapServers"
    const val DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9093"
    const val DEFAULT_TOPIC_PREFIX = Wow.WOW_PREFIX
    val DEFAULT_MESSAGE_HEADER_SQL_TYPE = MessageHeaderSqlType.MAP

    private val engine: TemplateEngine by lazy {
        val codeResolver = ResourceCodeResolver(TEMPLATE_ROOT)
        TemplateEngine.create(codeResolver, ContentType.Plain)
    }

    fun renderTemplate(template: String, params: Map<String, Any>): String {
        val output = StringOutput()
        engine.render(template, params, output)
        return output.toString().replace("\"", "").trim()
    }

    fun render(templateName: String, params: Map<String, Any>): String {
        return renderTemplate(templateName + TEMPLATE_SUFFIX, params)
    }

    fun renderGlobal(): String {
        val params: Map<String, Any> = emptyMap()
        return render("global", params)
    }

    fun renderClear(namedAggregate: NamedAggregate, expansionTables: List<String>): String {
        val params: Map<String, Any> = buildMap {
            put(NAMED_AGGREGATE, namedAggregate)
            put(EXPANSION_TABLES, expansionTables)
        }
        return render("clear", params).replace("\n\n", "\n")
    }

    fun renderCommand(
        namedAggregate: NamedAggregate,
        kafkaBootstrapServers: String = DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
        topicPrefix: String = DEFAULT_TOPIC_PREFIX,
        headerType: MessageHeaderSqlType = DEFAULT_MESSAGE_HEADER_SQL_TYPE
    ): String {
        val params: Map<String, Any> = buildMap {
            put(NAMED_AGGREGATE, namedAggregate)
            put(KAFKA_BOOTSTRAP_SERVERS, kafkaBootstrapServers)
            put(TOPIC_PREFIX, topicPrefix)
            put(MESSAGE_HEADER_SQL_TYPE, headerType)
        }
        return render("command", params)
    }

    fun renderStateEvent(
        namedAggregate: NamedAggregate,
        kafkaBootstrapServers: String = DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
        topicPrefix: String = DEFAULT_TOPIC_PREFIX,
        headerType: MessageHeaderSqlType = DEFAULT_MESSAGE_HEADER_SQL_TYPE
    ): String {
        val params: Map<String, Any> = buildMap {
            put(NAMED_AGGREGATE, namedAggregate)
            put(KAFKA_BOOTSTRAP_SERVERS, kafkaBootstrapServers)
            put(TOPIC_PREFIX, topicPrefix)
            put(MESSAGE_HEADER_SQL_TYPE, headerType)
        }
        return render("state-event".trimEnd(), params)
    }

    fun renderStateLast(
        namedAggregate: NamedAggregate,
        headerType: MessageHeaderSqlType = DEFAULT_MESSAGE_HEADER_SQL_TYPE
    ): String {
        val params: Map<String, Any> = buildMap {
            put(NAMED_AGGREGATE, namedAggregate)
            put(MESSAGE_HEADER_SQL_TYPE, headerType)
        }
        return render("state-last", params)
    }
}
