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

import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchTemplate
import org.springframework.data.elasticsearch.core.convert.ElasticsearchCustomConversions
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext

object TemplateInitializer {

    fun ReactiveElasticsearchClient.createElasticsearchTemplate(): ReactiveElasticsearchTemplate {
        val mappingContext = SimpleElasticsearchMappingContext()
        val converter = MappingElasticsearchConverter(mappingContext)
        converter.setConversions(ElasticsearchCustomConversions(emptyList<Any>()))
        return ReactiveElasticsearchTemplate(this, converter)
    }

    fun ReactiveElasticsearchClient.initEventStreamTemplate() {
        val elasticsearchTemplate = createElasticsearchTemplate()
        IndexTemplateInitializer(elasticsearchTemplate).initEventStreamTemplate().block()
    }

    fun ReactiveElasticsearchClient.initSnapshotTemplate() {
        val elasticsearchTemplate = createElasticsearchTemplate()
        IndexTemplateInitializer(elasticsearchTemplate).initSnapshotTemplate().block()
    }
}
