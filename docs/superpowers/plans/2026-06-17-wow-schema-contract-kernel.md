# Wow Schema 契约内核重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持 JSON Schema、OpenAPI Schema、RESTful API 兼容的前提下，清理 `wow-schema` 的生成内核、provider 注册、OpenAPI schema 适配和模板加载边界。

**Architecture:** 保留 `SchemaGeneratorBuilder`、`WowModule`、`OpenAPISchemaBuilder`、`WowSchemaLoader` 的 public entry point，通过 internal helper 和 registry 收口职责。每一步先加窄测试或使用现有 snapshot 作为护栏，再移动实现，最后运行 `:wow-schema:check` 和 `:wow-openapi:check`。

**Tech Stack:** Kotlin 2.3.20、Gradle、JUnit Jupiter、FluentAssert、VicTools JSON Schema Generator、Swagger Core、Jackson。

---

## 文件结构

- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/SchemaGeneratorBuilder.kt`  
  保留 public builder facade，把 VicTools config 创建委托给 internal factory，并消除重复 `configBuilder.build()`。
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/SchemaGeneratorConfigFactory.kt`  
  internal factory，集中创建 `SchemaGeneratorConfigBuilder`、安装模块、安装 options、执行 customizer。
- Create: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/SchemaGeneratorConfigFactoryTest.kt`  
  验证 factory 会应用 customizer，并能生成与 builder 生命周期一致的 config。
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowModule.kt`  
  保留 VicTools module entry point，把 typed provider 注册委托给 registry。
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistry.kt`  
  internal registry，按稳定顺序列出 Wow typed definition providers。
- Create: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistryTest.kt`  
  锁定 provider 注册顺序，防止 schema 输出顺序漂移。
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilder.kt`  
  保留 `resolveType()`、`generateSchema()`、`build()`，把 conversion 和 reference tracking 委托给 helper。
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaConverter.kt`  
  internal JSON Schema node 到 Swagger `Schema` 的转换器。
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/SchemaReferenceRegistry.kt`  
  internal reference tracking helper，集中跟踪并 merge reference schema。
- Create: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaConverterTest.kt`  
  验证转换器保留 schema type 和 properties。
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilderTest.kt`  
  保持现有 reference、inline、recursive schema 行为不变。
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/SchemaMerger.kt`  
  移除注释残留，保持字段级 merge 行为。
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/SchemaMergerTest.kt`  
  显式锁定当前不 merge `enum`、`examples` 的行为。
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowSchemaLoader.kt`  
  抽出 resource path 构造，保持资源路径兼容。
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowSchemaLoaderTest.kt`  
  增加成功加载、按类型加载、缺失资源失败路径测试。

## Task 1: 建立兼容性基线

**Files:**
- Read: `docs/superpowers/specs/2026-06-17-wow-schema-contract-kernel-design.md`
- Read: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/e2e/E2ESchemaGeneratorTest.kt`
- Read: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilderTest.kt`

- [ ] **Step 1: 确认当前分支和工作区状态**

Run:

```bash
git status --short --branch
```

Expected: 当前分支为 `codex/wow-schema-contract-kernel`，没有未提交实现改动。

- [ ] **Step 2: 运行 schema 基线检查**

Run:

```bash
./gradlew :wow-schema:check
```

Expected: `BUILD SUCCESSFUL`。如果失败，先记录失败测试和错误文本，不修改实现代码。

- [ ] **Step 3: 运行 OpenAPI contract 基线检查**

Run:

```bash
./gradlew :wow-openapi:check
```

Expected: `BUILD SUCCESSFUL`。如果失败，先记录失败测试和错误文本，不修改实现代码。

- [ ] **Step 4: 提交基线记录**

如果 Step 2 和 Step 3 都通过且没有文件改动，不需要提交。若只新增了基线说明文件，使用：

```bash
git add docs/superpowers/plans/2026-06-17-wow-schema-contract-kernel.md
git commit -m "docs(schema): add contract kernel implementation plan"
```

Expected: plan 文档独立提交，不包含实现代码。

## Task 2: 清理 SchemaGeneratorBuilder 的 config 生命周期

**Files:**
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/SchemaGeneratorConfigFactory.kt`
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/SchemaGeneratorBuilder.kt`
- Create: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/SchemaGeneratorConfigFactoryTest.kt`
- Test: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/SchemaGeneratorBuilderTest.kt`

- [ ] **Step 1: 写 factory 的窄测试**

Create `wow-schema/src/test/kotlin/me/ahoo/wow/schema/SchemaGeneratorConfigFactoryTest.kt`:

```kotlin
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

package me.ahoo.wow.schema

import com.github.victools.jsonschema.generator.Option
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class SchemaGeneratorConfigFactoryTest {

    @Test
    fun `should apply customizer when creating config builder`() {
        val schemaGeneratorBuilder = SchemaGeneratorBuilder().customizer {
            it.with(Option.INLINE_ALL_SCHEMAS)
        }

        val config = SchemaGeneratorConfigFactory.create(schemaGeneratorBuilder).build()

        config.shouldInlineAllSchemas().assert().isTrue()
    }
}
```

- [ ] **Step 2: 运行测试确认缺少 factory**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.SchemaGeneratorConfigFactoryTest"
```

Expected: FAIL，错误包含 `Unresolved reference 'SchemaGeneratorConfigFactory'`。

- [ ] **Step 3: 创建 SchemaGeneratorConfigFactory**

Create `wow-schema/src/main/kotlin/me/ahoo/wow/schema/SchemaGeneratorConfigFactory.kt`:

```kotlin
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

package me.ahoo.wow.schema

import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder

internal object SchemaGeneratorConfigFactory {

    fun create(builder: SchemaGeneratorBuilder): SchemaGeneratorConfigBuilder {
        return SchemaGeneratorConfigBuilder(builder.schemaVersion, builder.optionPreset)
            .withModuleIfPresent(builder.jacksonModule)
            .withModuleIfPresent(builder.jakartaValidationModule)
            .withModuleIfPresent(builder.swagger2Module)
            .withModuleIfPresent(builder.kotlinModule)
            .withModuleIfPresent(builder.jodaMoneyModule)
            .withModuleIfPresent(builder.wowModule)
            .withModuleIfPresent(builder.schemaNamingModule)
            .withOptions(builder.options)
            .also { configBuilder ->
                configBuilder.forFields()
                builder.customizer?.accept(configBuilder)
            }
    }

    private fun SchemaGeneratorConfigBuilder.withModuleIfPresent(
        module: Module?
    ): SchemaGeneratorConfigBuilder {
        module?.let {
            with(it)
        }
        return this
    }

    private fun SchemaGeneratorConfigBuilder.withOptions(
        options: List<Option>
    ): SchemaGeneratorConfigBuilder {
        options.forEach {
            with(it)
        }
        return this
    }
}
```

- [ ] **Step 4: 修改 SchemaGeneratorBuilder 使用同一份 config**

Modify `wow-schema/src/main/kotlin/me/ahoo/wow/schema/SchemaGeneratorBuilder.kt`:

```kotlin
fun build(): SchemaGenerator {
    val config = SchemaGeneratorConfigFactory.create(this).build()
    typeContext = TypeContextFactory.createDefaultTypeContext(config)
    return SchemaGenerator(config, typeContext)
}
```

Remove the old companion object helper methods from `SchemaGeneratorBuilder`, because they now live in `SchemaGeneratorConfigFactory`.

- [ ] **Step 5: 运行窄测试**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.SchemaGeneratorConfigFactoryTest" --tests "me.ahoo.wow.schema.SchemaGeneratorBuilderTest"
```

Expected: PASS。

- [ ] **Step 6: 运行 schema snapshot 护栏**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.e2e.E2ESchemaGeneratorTest"
```

Expected: PASS，所有 e2e snapshot 字符串完全一致。

- [ ] **Step 7: 提交 Task 2**

Run:

```bash
git add wow-schema/src/main/kotlin/me/ahoo/wow/schema/SchemaGeneratorBuilder.kt \
  wow-schema/src/main/kotlin/me/ahoo/wow/schema/SchemaGeneratorConfigFactory.kt \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/SchemaGeneratorConfigFactoryTest.kt
git commit -m "refactor(schema): isolate generator config creation"
```

Expected: 一个只包含 generator config 生命周期清理的提交。

## Task 3: 为 WowModule 引入 provider registry

**Files:**
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistry.kt`
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowModule.kt`
- Create: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistryTest.kt`
- Test: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowModuleTest.kt`

- [ ] **Step 1: 写 provider 顺序测试**

Create `wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistryTest.kt`:

```kotlin
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

package me.ahoo.wow.schema

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class WowDefinitionProviderRegistryTest {

    @Test
    fun `should keep provider registration order stable`() {
        WowDefinitionProviderRegistry.providers.map {
            it::class.simpleName
        }.assert().containsExactly(
            "AggregateIdDefinitionProvider",
            "CommandDefinitionProvider",
            "DomainEventDefinitionProvider",
            "DomainEventStreamDefinitionProvider",
            "AggregatedDomainEventStreamDefinitionProvider",
            "AggregatedFieldsDefinitionProvider",
            "AggregatedListQueryDefinitionProvider",
            "AggregatedPagedQueryDefinitionProvider",
            "AggregatedSingleQueryDefinitionProvider",
            "StateAggregateDefinitionProvider",
            "SnapshotDefinitionProvider",
            "StateEventDefinitionProvider",
            "ServerSentEventCustomDefinitionProvider",
            "ConditionOptionsDefinitionProvider",
            "MapDefinitionProvider",
            "EnumTextDefinitionProvider"
        )
    }
}
```

- [ ] **Step 2: 运行测试确认 registry 未创建**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.WowDefinitionProviderRegistryTest"
```

Expected: FAIL，错误包含 `Unresolved reference 'WowDefinitionProviderRegistry'`。

- [ ] **Step 3: 创建 provider registry**

Create `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistry.kt`:

```kotlin
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

package me.ahoo.wow.schema

import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import me.ahoo.wow.schema.typed.AggregateIdDefinitionProvider
import me.ahoo.wow.schema.typed.AggregatedDomainEventStreamDefinitionProvider
import me.ahoo.wow.schema.typed.AggregatedFieldsDefinitionProvider
import me.ahoo.wow.schema.typed.CommandDefinitionProvider
import me.ahoo.wow.schema.typed.DomainEventDefinitionProvider
import me.ahoo.wow.schema.typed.DomainEventStreamDefinitionProvider
import me.ahoo.wow.schema.typed.EnumTextDefinitionProvider
import me.ahoo.wow.schema.typed.MapDefinitionProvider
import me.ahoo.wow.schema.typed.SnapshotDefinitionProvider
import me.ahoo.wow.schema.typed.StateAggregateDefinitionProvider
import me.ahoo.wow.schema.typed.StateEventDefinitionProvider
import me.ahoo.wow.schema.typed.query.AggregatedListQueryDefinitionProvider
import me.ahoo.wow.schema.typed.query.AggregatedPagedQueryDefinitionProvider
import me.ahoo.wow.schema.typed.query.AggregatedSingleQueryDefinitionProvider
import me.ahoo.wow.schema.typed.query.ConditionOptionsDefinitionProvider
import me.ahoo.wow.schema.web.ServerSentEventCustomDefinitionProvider

internal object WowDefinitionProviderRegistry {
    val providers: List<CustomDefinitionProviderV2> = listOf(
        AggregateIdDefinitionProvider,
        CommandDefinitionProvider,
        DomainEventDefinitionProvider,
        DomainEventStreamDefinitionProvider,
        AggregatedDomainEventStreamDefinitionProvider,
        AggregatedFieldsDefinitionProvider,
        AggregatedListQueryDefinitionProvider,
        AggregatedPagedQueryDefinitionProvider,
        AggregatedSingleQueryDefinitionProvider,
        StateAggregateDefinitionProvider,
        SnapshotDefinitionProvider,
        StateEventDefinitionProvider,
        ServerSentEventCustomDefinitionProvider,
        ConditionOptionsDefinitionProvider,
        MapDefinitionProvider,
        EnumTextDefinitionProvider
    )
}
```

- [ ] **Step 4: 修改 WowModule 使用 registry**

Modify `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowModule.kt` by replacing the direct provider registration block:

```kotlin
WowDefinitionProviderRegistry.providers.forEach {
    generalConfigPart.withCustomDefinitionProvider(it)
}
```

Remove typed provider imports that are no longer referenced by `WowModule`.

- [ ] **Step 5: 运行 registry 和 WowModule 测试**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.WowDefinitionProviderRegistryTest" --tests "me.ahoo.wow.schema.WowModuleTest"
```

Expected: PASS。

- [ ] **Step 6: 运行 schema snapshot 护栏**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.e2e.E2ESchemaGeneratorTest"
```

Expected: PASS，provider 注册顺序变化为 0。

- [ ] **Step 7: 提交 Task 3**

Run:

```bash
git add wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowModule.kt \
  wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistry.kt \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistryTest.kt
git commit -m "refactor(schema): group wow definition providers"
```

Expected: 一个只包含 provider registry 的提交。

## Task 4: 拆出 OpenAPI schema 转换与 reference tracking

**Files:**
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaConverter.kt`
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/SchemaReferenceRegistry.kt`
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilder.kt`
- Create: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaConverterTest.kt`
- Test: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilderTest.kt`

- [ ] **Step 1: 写 converter 窄测试**

Create `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaConverterTest.kt`:

```kotlin
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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.WowSchemaLoader
import org.junit.jupiter.api.Test

class OpenAPISchemaConverterTest {

    @Test
    fun `should convert json schema node to swagger schema`() {
        val jsonNode = WowSchemaLoader.load("AggregateId")

        val schema = OpenAPISchemaConverter().toSchema(jsonNode)

        schema.types.assert().contains("object")
        schema.properties.assert().containsKey("aggregateId")
    }
}
```

- [ ] **Step 2: 运行测试确认 converter 未创建**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.openapi.OpenAPISchemaConverterTest"
```

Expected: FAIL，错误包含 `Unresolved reference 'OpenAPISchemaConverter'`。

- [ ] **Step 3: 创建 OpenAPISchemaConverter**

Create `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaConverter.kt`:

```kotlin
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

import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.serialization.toLinkedHashMap
import tools.jackson.databind.JsonNode

internal class OpenAPISchemaConverter {
    private val openAPIObjectMapper = ObjectMapperFactory.create(null, true)

    fun toSchema(jsonNode: JsonNode): Schema<*> {
        return openAPIObjectMapper.convertValue(jsonNode.toLinkedHashMap(), Schema::class.java)
    }
}
```

- [ ] **Step 4: 创建 SchemaReferenceRegistry**

Create `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/SchemaReferenceRegistry.kt`:

```kotlin
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

import com.fasterxml.classmate.ResolvedType
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.schema.openapi.SchemaMerger.mergeTo
import tools.jackson.databind.node.ObjectNode

internal class SchemaReferenceRegistry(
    private val schemaConverter: OpenAPISchemaConverter
) {
    private val references: MutableList<SchemaReference> = mutableListOf()

    fun track(type: ResolvedType, node: ObjectNode): Schema<*> {
        val reference = SchemaReference(type, schemaConverter.toSchema(node), node)
        references.add(reference)
        return reference.schema
    }

    fun mergeAll() {
        references.forEach {
            it.merge(schemaConverter)
        }
    }

    private class SchemaReference(
        val type: ResolvedType,
        val schema: Schema<*>,
        val node: ObjectNode
    ) {
        fun merge(schemaConverter: OpenAPISchemaConverter) {
            schemaConverter.toSchema(node).mergeTo(schema)
        }
    }
}
```

- [ ] **Step 5: 修改 OpenAPISchemaBuilder 委托 helper**

Modify `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilder.kt`:

```kotlin
private val schemaBuilder = schemaGenerator.buildMultipleSchemaDefinitions()
private val schemaConverter = OpenAPISchemaConverter()
private val schemaReferences = SchemaReferenceRegistry(schemaConverter)

fun JsonNode.toSchema(): Schema<*> {
    return schemaConverter.toSchema(this)
}
```

Replace the non-inline branch of `generateSchema()`:

```kotlin
val refSchemaNode = schemaBuilder.createSchemaReference(resolvedType)
return schemaReferences.track(resolvedType, refSchemaNode)
```

Replace the merge loop inside `build()`:

```kotlin
fun build(): Map<String, Schema<*>> {
    val collectedDefs = schemaBuilder.collectDefinitions(definitionPath)
    schemaReferences.mergeAll()
    return collectedDefs.properties().associate { (name, node) ->
        name to schemaConverter.toSchema(node)
    }
}
```

Remove the old inner `SchemaReference` class and `ObjectMapperFactory` / `toLinkedHashMap` imports from `OpenAPISchemaBuilder`.

- [ ] **Step 6: 运行 OpenAPI schema 窄测试**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.openapi.OpenAPISchemaConverterTest" --tests "me.ahoo.wow.schema.openapi.OpenAPISchemaBuilderTest"
```

Expected: PASS，现有 reference、inline、recursive ref 行为不变。

- [ ] **Step 7: 运行 OpenAPI contract 护栏**

Run:

```bash
./gradlew :wow-openapi:check
```

Expected: PASS，OpenAPI snapshot 和 route contract 没有漂移。

- [ ] **Step 8: 提交 Task 4**

Run:

```bash
git add wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilder.kt \
  wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaConverter.kt \
  wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/SchemaReferenceRegistry.kt \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaConverterTest.kt
git commit -m "refactor(schema): isolate openapi schema adaptation"
```

Expected: 一个只包含 OpenAPI schema adapter 边界清理的提交。

## Task 5: 澄清 SchemaMerger 行为并移除注释残留

**Files:**
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/SchemaMerger.kt`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/SchemaMergerTest.kt`

- [ ] **Step 1: 写当前行为锁定测试**

Modify `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/SchemaMergerTest.kt` by adding:

```kotlin
@Test
fun `should keep enum and examples unchanged to preserve current merge behavior`() {
    val source = io.swagger.v3.oas.models.media.Schema<String>()
    source._enum(listOf("A", "B"))
    source.examples(listOf("sample"))

    val target = io.swagger.v3.oas.models.media.Schema<String>()
    source.mergeTo(target)

    target.enum.assert().isNull()
    target.examples.assert().isNull()
}
```

- [ ] **Step 2: 运行测试确认当前行为**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.openapi.SchemaMergerTest"
```

Expected: PASS，说明当前实现确实不 merge `enum` 和 `examples`。

- [ ] **Step 3: 移除 SchemaMerger 注释残留**

Modify `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/SchemaMerger.kt` by removing these commented lines:

```kotlin
//        target._enum(enum)
//        target.examples(examples)
```

Do not add `target._enum(enum)` or `target.examples(examples)` in this refactor.

- [ ] **Step 4: 运行 SchemaMerger 测试**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.openapi.SchemaMergerTest"
```

Expected: PASS。

- [ ] **Step 5: 运行 OpenAPI builder 护栏**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.openapi.OpenAPISchemaBuilderTest"
```

Expected: PASS。

- [ ] **Step 6: 提交 Task 5**

Run:

```bash
git add wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/SchemaMerger.kt \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/SchemaMergerTest.kt
git commit -m "test(schema): lock schema merger compatibility"
```

Expected: 一个只包含 merger 行为锁定和注释清理的提交。

## Task 6: 加强 WowSchemaLoader 模板加载边界

**Files:**
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowSchemaLoader.kt`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowSchemaLoaderTest.kt`

- [ ] **Step 1: 扩展 loader 测试**

Replace `wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowSchemaLoaderTest.kt` with:

```kotlin
package me.ahoo.wow.schema

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.AggregateId
import org.junit.jupiter.api.Test

class WowSchemaLoaderTest {

    @Test
    fun `should load schema resource as string`() {
        val schema = WowSchemaLoader.loadAsString("AggregateId")

        schema.assert().contains("\"aggregateId\"")
    }

    @Test
    fun `should load schema resource as object node`() {
        val schema = WowSchemaLoader.load("AggregateId")

        schema.get("properties").assert().isNotNull()
    }

    @Test
    fun `should load schema resource by type simple name`() {
        val schema = WowSchemaLoader.load(AggregateId::class.java)

        schema.get("properties").assert().isNotNull()
    }

    @Test
    fun `should throw when loading non-existent schema resource`() {
        assertThrownBy<IllegalArgumentException> {
            WowSchemaLoader.loadAsString("not_found")
        }
    }
}
```

- [ ] **Step 2: 运行 loader 测试**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.WowSchemaLoaderTest"
```

Expected: PASS with current behavior.

- [ ] **Step 3: 抽出 resource path 构造**

Modify `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowSchemaLoader.kt`:

```kotlin
object WowSchemaLoader {
    private const val WOW_SCHEMA_PATH_PREFIX = "META-INF/wow-schema/"

    private fun resourcePath(resourceName: String): String {
        return "$WOW_SCHEMA_PATH_PREFIX$resourceName.json"
    }

    fun loadAsString(resourceName: String): String {
        val resourcePath = resourcePath(resourceName)
        val resourceURL = this.javaClass.classLoader.getResource(resourcePath)
        requireNotNull(resourceURL) {
            "Can not find wow schema resource: $resourcePath"
        }
        return resourceURL.openStream().use {
            it.readAllBytes().toString(Charsets.UTF_8)
        }
    }

    fun load(resourceName: String): ObjectNode {
        return loadAsString(resourceName).toObject<ObjectNode>()
    }

    fun load(resourceType: Class<*>): ObjectNode {
        return load(resourceType.simpleName)
    }
}
```

- [ ] **Step 4: 运行 loader 测试**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.WowSchemaLoaderTest"
```

Expected: PASS。

- [ ] **Step 5: 运行 typed provider 护栏**

Run:

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.typed.TypedDefinitionProviderTest" --tests "me.ahoo.wow.schema.e2e.E2ESchemaGeneratorTest"
```

Expected: PASS，模板加载重构没有改变 typed schema 输出。

- [ ] **Step 6: 提交 Task 6**

Run:

```bash
git add wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowSchemaLoader.kt \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowSchemaLoaderTest.kt
git commit -m "test(schema): strengthen schema loader boundaries"
```

Expected: 一个只包含 loader 边界和测试增强的提交。

## Task 7: 最终验证与债务清理

**Files:**
- Check: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/**`
- Check: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/**`
- Check: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/**`

- [ ] **Step 1: 运行 schema 完整检查**

Run:

```bash
./gradlew :wow-schema:check
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 运行 OpenAPI 完整检查**

Run:

```bash
./gradlew :wow-openapi:check
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 搜索重构残留**

Run:

```bash
rg -n "TO""DO|FIX""ME|legacy|deprecated|compatibility shim|target\\._enum\\(|target\\.examples\\(" wow-schema/src/main wow-schema/src/test
```

Expected: no output。若存在命中，逐条判断是否属于本轮引入；本轮引入的残留必须清掉。

- [ ] **Step 4: 检查 public route 未触碰**

Run:

```bash
git diff origin/main -- wow-openapi/src/main wow-webflux/src/main
```

Expected: 只允许看到 `wow-schema` 被消费处因 schema helper 变动产生的必要差异；不得出现 route path、HTTP method、operationId、handler key、WebFlux route registration 的变化。

- [ ] **Step 5: 检查提交历史**

Run:

```bash
git log --oneline --decorate origin/main..HEAD
```

Expected: 能看到 plan/spec 提交和每个实现任务的聚焦提交。

- [ ] **Step 6: 最终提交清理**

If Step 3 required cleanup edits, commit them:

```bash
git add wow-schema/src/main wow-schema/src/test
git commit -m "chore(schema): clean refactor leftovers"
```

Expected: no cleanup commit when Step 3 has no residual findings; one focused cleanup commit when residual findings were removed.

- [ ] **Step 7: 准备 review 摘要**

Run:

```bash
git diff --stat origin/main..HEAD
```

Expected: diff 主要集中在 `wow-schema` 和 `docs/superpowers`，没有 unrelated files。

## Self-Review

- Spec coverage: 本计划覆盖了设计文档中的 generation kernel、Wow schema contributors、OpenAPI schema adapter、schema resource templates、JSON Schema snapshot、OpenAPI contract、RESTful API compatibility 和最终债务收口。
- Placeholder scan: 本计划没有未完成占位符、延后实现描述或空泛步骤。
- Type consistency: 计划中新增类型为 `SchemaGeneratorConfigFactory`、`WowDefinitionProviderRegistry`、`OpenAPISchemaConverter`、`SchemaReferenceRegistry`，后续任务引用名称一致。
