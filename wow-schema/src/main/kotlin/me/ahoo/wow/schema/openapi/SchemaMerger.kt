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

package me.ahoo.wow.schema.openapi

import io.swagger.v3.oas.models.media.Schema

object SchemaMerger {
    @Suppress("LongMethod")
    fun Schema<*>.mergeTo(target: Schema<*>) {
        target.name(name)
        target.title(title)
        target.multipleOf(multipleOf)
        target.maximum(maximum)
        target.exclusiveMaximum(exclusiveMaximum)
        target.minimum(minimum)
        target.exclusiveMinimum(exclusiveMinimum)
        target.maxLength(maxLength)
        target.minLength(minLength)
        target.pattern(pattern)
        target.maxItems(maxItems)
        target.minItems(minItems)
        target.uniqueItems(uniqueItems)
        target.maxProperties(maxProperties)
        target.minProperties(minProperties)
        target.required(required)
        target.type(type)
        target.not(not)
        target.properties(properties)
        target.additionalProperties(additionalProperties)
        target.description(description)
        target.format(format)
        target.`$ref`(`$ref`)
        target.nullable(nullable)
        target.readOnly(readOnly)
        target.writeOnly(writeOnly)
        target.example(example)
        target.externalDocs(externalDocs)
        target.deprecated(deprecated)
        target.xml(xml)
        target.extensions(extensions)
//        target._enum(enum)
        target.discriminator(discriminator)
        target.exampleSetFlag((exampleSetFlag))
        target.prefixItems(prefixItems)
        target.allOf(allOf)
        target.anyOf(anyOf)
        target.oneOf(oneOf)
        target.items(items)
        target._const(const)
        target.specVersion(specVersion)
        target.types(types)
        target.patternProperties(patternProperties)
        target.exclusiveMaximumValue(exclusiveMaximumValue)
        target.exclusiveMinimumValue(exclusiveMinimumValue)
        target.contains(contains)
        target.`$id`(`$id`)
        target.`$schema`(`$schema`)
        target.`$anchor`(`$anchor`)
        target.`$vocabulary`(`$vocabulary`)
        target.`$dynamicRef`(`$dynamicRef`)
        target.contentEncoding(contentEncoding)
        target.contentMediaType(contentMediaType)
        target.contentSchema(contentSchema)
        target.propertyNames(propertyNames)
        target.unevaluatedProperties(unevaluatedProperties)
        target.maxContains(maxContains)
        target.minContains(minContains)
        target.additionalItems(additionalItems)
        target.unevaluatedItems(unevaluatedItems)
        target._if(`if`)
        target._else(`else`)
        target.then(`then`)
        target.dependentSchemas(dependentSchemas)
        target.dependentRequired(dependentRequired)
        target.`$comment`(`$comment`)
//        target.examples(examples)
        target.booleanSchemaValue(booleanSchemaValue)
    }
}
