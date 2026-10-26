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

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.asm.ClassWriter
import org.springframework.asm.Opcodes
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * The host makes the application's DataSource and MongoClient from their properties alone (decisions D310, D318),
 * unless the application has one of its own or Spring Boot's JDBC or MongoDB auto-configuration makes it.
 */
class DatabaseAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(DataSourceAutoConfiguration::class.java, MongoClientAutoConfiguration::class.java),
        )

    /** An application on the host alone: no Spring Boot JDBC or MongoDB. */
    private val hostAlone = runner

    /**
     * An application with Spring Boot's JDBC and MongoDB: their auto-configurations' classes are present, here empty
     * classes of their names, since the host's tests run without those modules.
     */
    private val withBoot = runner.withClassLoader(BootDatabases(DatabaseAutoConfigurationTest::class.java.classLoader))

    private class BootDatabases(parent: ClassLoader) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            if (name != BOOT_JDBC_AUTO_CONFIGURATION && name != BOOT_MONGO_AUTO_CONFIGURATION) {
                throw ClassNotFoundException(name)
            }
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null)
            writer.visitEnd()
            val bytes = writer.toByteArray()
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class OwnDatabases {
        @Bean(destroyMethod = "close")
        fun dataSource(): DataSource = HikariDataSource().apply { jdbcUrl = "jdbc:postgresql://own:5432/own" }

        @Bean(destroyMethod = "close")
        fun mongoClient(): MongoClient = MongoClients.create("mongodb://own:27017")
    }

    @Test
    fun `a datasource url should give a pooled DataSource of the spring datasource properties`() {
        hostAlone.withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://db:5432/shop",
            "spring.datasource.username=shop",
            "spring.datasource.password=secret",
            "spring.datasource.hikari.maximum-pool-size=7",
            "spring.datasource.hikari.pool-name=shop-pool",
        ).run { context ->
            val dataSource = context.getBean(DataSource::class.java) as HikariDataSource
            dataSource.jdbcUrl.assert().isEqualTo("jdbc:postgresql://db:5432/shop")
            dataSource.username.assert().isEqualTo("shop")
            dataSource.password.assert().isEqualTo("secret")
            dataSource.maximumPoolSize.assert().isEqualTo(7)
            dataSource.poolName.assert().isEqualTo("shop-pool")
            // The pool opens at the first connection, not at startup.
            dataSource.isRunning.assert().isFalse()
        }
    }

    @Test
    fun `a mongodb uri should give a MongoClient`() {
        hostAlone.withPropertyValues("spring.mongodb.uri=mongodb://mongo:27017/shop").run { context ->
            context.getBeansOfType(MongoClient::class.java).keys.assert().containsExactly("wowMongoClient")
        }
    }

    @Test
    fun `the application's own DataSource and MongoClient should win`() {
        hostAlone.withUserConfiguration(OwnDatabases::class.java).withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://db:5432/shop",
            "spring.mongodb.uri=mongodb://mongo:27017/shop",
        ).run { context ->
            context.getBeansOfType(DataSource::class.java).keys.assert().containsExactly("dataSource")
            context.getBeansOfType(MongoClient::class.java).keys.assert().containsExactly("mongoClient")
        }
    }

    @Test
    fun `Spring Boot's own JDBC and MongoDB should make them instead`() {
        withBoot.withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://db:5432/shop",
            "spring.mongodb.uri=mongodb://mongo:27017/shop",
        ).run { context ->
            context.getBeansOfType(DataSource::class.java).assert().isEmpty()
            context.getBeansOfType(MongoClient::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `nothing set, or no HikariCP or wow-mongo, should make no connection`() {
        hostAlone.run { context ->
            context.getBeansOfType(DataSource::class.java).assert().isEmpty()
            context.getBeansOfType(MongoClient::class.java).assert().isEmpty()
        }
        runner.withClassLoader(
            FilteredClassLoader(
                BOOT_JDBC_AUTO_CONFIGURATION,
                BOOT_MONGO_AUTO_CONFIGURATION,
                HIKARI_DATA_SOURCE,
                MONGO_MODULE
            ),
        ).withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://db:5432/shop",
            "spring.mongodb.uri=mongodb://mongo:27017/shop",
        ).run { context ->
            context.getBeansOfType(DataSource::class.java).assert().isEmpty()
            context.getBeansOfType(MongoClient::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `a host without a datasource url or mongodb uri should run in memory in a development node`() {
        startHost().use { host ->
            host.context.getBeansOfType(DataSource::class.java).assert().isEmpty()
            host.context.getBeansOfType(MongoClient::class.java).assert().isEmpty()
            host.context.getBean(HostBackends::class.java).type.assert().isEqualTo("memory")
        }
    }
}
