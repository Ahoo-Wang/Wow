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

package me.ahoo.wow.compiler.metadata

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.kspSourcesDir
import me.ahoo.wow.compiler.compileTest
import me.ahoo.wow.configuration.WOW_METADATA_RESOURCE_NAME
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.Path

class MetadataSymbolProcessorTest {
    @OptIn(ExperimentalCompilerApi::class)
    fun compileTestMetadataSymbolProcessor(
        sources: List<File>,
        consumer: (KotlinCompilation, JvmCompilationResult) -> Unit = { _, _ ->
        }
    ) {
        compileTest(sources, MetadataSymbolProcessorProvider(), consumer)
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun process() {
        val mockBoundedContextFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockBoundedContext.kt")
        val mockCompilerAggregateFile = File("src/test/kotlin/me/ahoo/wow/compiler/MockCompilerAggregate.kt")
        compileTestMetadataSymbolProcessor(
            listOf(
                mockBoundedContextFile,
                mockCompilerAggregateFile,
            )
        ) { compilation, _ ->
            val metadataFilePath = Path(compilation.kspSourcesDir.path, "resources", WOW_METADATA_RESOURCE_NAME)
            assertThat(
                metadataFilePath.toFile().readText(),
                equalTo(
                    """
                {
                  "contexts" : {
                    "mock" : {
                      "alias" : "mock",
                      "scopes" : [ "me.ahoo.wow.compiler" ],
                      "aggregates" : {
                        "mock_compiler_aggregate" : {
                          "scopes" : [ "me.ahoo.wow.compiler" ],
                          "type" : "me.ahoo.wow.compiler.MockCompilerAggregate",
                          "tenantId" : "mock",
                          "id" : "mock",
                          "commands" : [ "me.ahoo.wow.compiler.CreateAggregate", "me.ahoo.wow.compiler.ChangeAggregate", "me.ahoo.wow.compiler.ChangeAggregateDependExternalService", "me.ahoo.wow.compiler.MountedCommand" ],
                          "events" : [ "me.ahoo.wow.compiler.AggregateCreated", "me.ahoo.wow.compiler.AggregateChanged" ]
                        }
                      }
                    }
                  }
                }
                    """.trimIndent()
                )
            )
        }
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processJava() {
        val mockBoundedContextFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaBoundedContext.java")
        val mockCompilerAggregateFile = File("src/test/java/me/ahoo/wow/compiler/MockJavaCompilerAggregate.java")
        compileTestMetadataSymbolProcessor(
            listOf(
                mockBoundedContextFile,
                mockCompilerAggregateFile,
            )
        ) { compilation, _ ->
            val metadataFilePath = Path(compilation.kspSourcesDir.path, "resources", WOW_METADATA_RESOURCE_NAME)
            assertThat(
                metadataFilePath.toFile().readText(),
                equalTo(
                    """
                {
                  "contexts" : {
                    "mock_java" : {
                      "alias" : "mock_java",
                      "scopes" : [ "me.ahoo.wow.compiler" ],
                      "aggregates" : {
                        "mock_java_compiler_aggregate" : {
                          "scopes" : [ "me.ahoo.wow.compiler" ],
                          "type" : "me.ahoo.wow.compiler.MockJavaCompilerAggregate",
                          "tenantId" : "mock_java",
                          "id" : "mock_java",
                          "commands" : [ ],
                          "events" : [ ]
                        }
                      }
                    }
                  }
                }
                    """.trimIndent()
                )
            )
        }
    }

    @OptIn(ExperimentalCompilerApi::class)
    @Test
    fun processExample() {
        val exampleApiDir = File("../example/example-api/src/main/kotlin/me/ahoo/wow/example/api")
        val exampleApiFiles = exampleApiDir.walkTopDown().filter { it.isFile }.toList()
        val exampleDomainDir = File("../example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain")
        val exampleDomainFiles = exampleDomainDir.walkTopDown().filter { it.isFile }.toList()

        compileTestMetadataSymbolProcessor(
            exampleApiFiles + exampleDomainFiles,
        ) { compilation, _ ->
            val metadataFilePath = Path(compilation.kspSourcesDir.path, "resources", WOW_METADATA_RESOURCE_NAME)
            assertThat(
                metadataFilePath.toFile().readText(),
                equalTo(
                    """
                {
                  "contexts" : {
                    "example-service" : {
                      "alias" : "example",
                      "scopes" : [ "me.ahoo.wow.example.api", "me.ahoo.wow.example.domain" ],
                      "aggregates" : {
                        "order" : {
                          "scopes" : [ "me.ahoo.wow.example.api.order" ],
                          "type" : "me.ahoo.wow.example.domain.order.Order",
                          "tenantId" : null,
                          "id" : null,
                          "commands" : [ "me.ahoo.wow.example.api.order.CreateOrder", "me.ahoo.wow.example.api.order.ChangeAddress", "me.ahoo.wow.example.api.order.ShipOrder", "me.ahoo.wow.example.api.order.ReceiptOrder", "me.ahoo.wow.example.api.order.PayOrder" ],
                          "events" : [ "me.ahoo.wow.example.api.order.OrderCreated", "me.ahoo.wow.example.api.order.AddressChanged", "me.ahoo.wow.example.api.order.OrderPaid", "me.ahoo.wow.example.api.order.OrderShipped", "me.ahoo.wow.example.api.order.OrderReceived" ]
                        },
                        "cart" : {
                          "scopes" : [ "me.ahoo.wow.example.api.cart" ],
                          "type" : "me.ahoo.wow.example.domain.cart.Cart",
                          "tenantId" : "(0)",
                          "id" : null,
                          "commands" : [ "me.ahoo.wow.example.api.cart.AddCartItem", "me.ahoo.wow.example.api.cart.RemoveCartItem", "me.ahoo.wow.example.api.cart.ChangeQuantity", "me.ahoo.wow.example.api.cart.MountedCommand", "me.ahoo.wow.example.api.cart.ViewCart", "me.ahoo.wow.example.api.cart.MockVariableCommand" ],
                          "events" : [ "me.ahoo.wow.example.api.cart.CartItemAdded", "me.ahoo.wow.example.api.cart.CartQuantityChanged", "me.ahoo.wow.example.api.cart.CartItemRemoved" ]
                        }
                      }
                    }
                  }
                }
                    """.trimIndent()
                )
            )
        }
    }
}
