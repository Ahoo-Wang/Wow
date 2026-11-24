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

package me.ahoo.wow.spring.boot.store

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.test.asserts.assert
import me.ahoo.wow.mongo.MongoPoolGauge
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
            AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                MongoPoolAutoConfiguration::class.java,
                MongoClientAutoConfiguration::class.java,
            ),
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
    fun `the DataSource's start-up line should name where it connects, never a password its URL carries`() {
        // PostgreSQL's driver takes the credentials as URL parameters as well as from username and password (#6848).
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val lines = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(lines)
        try {
            hostAlone.withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://db:5432/shop?user=shop&password=$SECRET"
            )
                .run { context ->
                    // The pool connects with the URL as it is.
                    (context.getBean(DataSource::class.java) as HikariDataSource).jdbcUrl.assert().contains(SECRET)
                }
        } finally {
            root.detachAppender(lines)
        }
        val logged = lines.list.map { it.formattedMessage }
        logged.single { "spring.datasource.url" in it }.assert()
            .isEqualTo("The host's DataSource connects to jdbc:postgresql://db:5432/shop (spring.datasource.url).")
        logged.filter { SECRET in it }.assert().isEmpty()
    }

    @Test
    fun `a JDBC URL without a credential should still show its hosts and database`() {
        mapOf(
            "jdbc:postgresql://db:5432/shop" to "jdbc:postgresql://db:5432/shop",
            "jdbc:postgresql://db1:5432,db2:5432/shop?ssl=true" to "jdbc:postgresql://db1:5432,db2:5432/shop",
            "jdbc:postgresql://[::1]:5432/shop" to "jdbc:postgresql://[::1]:5432/shop",
            "jdbc:postgresql://db" to "jdbc:postgresql://db",
            "jdbc:sqlserver://db:1433;databaseName=shop" to "jdbc:sqlserver://db:1433",
            "jdbc:db2://db:50000/shop:currentSchema=sales;" to "jdbc:db2://db:50000/shop",
            // The forms that may hold a credential anywhere show their driver alone (#6848).
            "jdbc:h2:mem:shop" to "jdbc:h2",
            "jdbc:oracle:thin:@//db:1521/shop" to "jdbc:oracle",
            "jdbc:postgresql://db/shop?user=a@b.example" to "jdbc:postgresql",
            // Not a JDBC URL, which no driver takes: nothing of it.
            "postgresql://db/shop?password=$SECRET" to "jdbc:",
        ).forEach { (url, shown) ->
            DataSourceAutoConfiguration.withoutCredentials(url).assert().describedAs(url).isEqualTo(shown)
        }
    }

    @Test
    fun `no part of a credential should be shown, whatever its URL's form, its place and what it is made of`() {
        val forms = listOf(
            "jdbc:postgresql://db:5432/shop",
            "jdbc:postgresql://db1:5432,db2:5432/shop",
            "jdbc:postgresql://[::1]:5432/shop",
            "jdbc:mysql://db:3306/shop",
            "jdbc:oracle:thin:@//db:1521/shop",
            "jdbc:oracle:thin:@db:1521:orcl",
            "jdbc:sqlserver://db:1433;databaseName=shop",
            "jdbc:h2:mem:shop",
            "jdbc:db2://db:50000/shop",
            "jdbc:informix-sqli://db:1533/shop:INFORMIXSERVER=srv",
        )
        // Where a driver takes a credential: user info, a `?` or `&` parameter, a `;` property, a `:` property after
        // the database (DB2's, Informix's).
        val places: List<(String, String) -> String?> = listOf(
            { url, secret ->
                when {
                    "thin:@" in url -> url.replace("thin:@", "thin:shop/$secret@")
                    "//" in url -> url.replaceFirst("//", "//shop:$secret@")
                    else -> null
                }
            },
            { url, secret -> "$url?password=$secret" },
            { url, secret -> "$url?ssl=true&password=$secret" },
            { url, secret -> "$url;password=$secret" },
            { url, secret -> "$url:password=$secret;user=shop;" },
        )
        // Letters no form holds, around each of a URL's delimiters.
        val secrets = listOf("@", "/", "//", "?", ";", ":", "#", "&", "=", "%").map { "ZQ${it}JK${it}W" }
        forms.forEach { form ->
            places.forEach { place ->
                secrets.forEach { secret ->
                    val url = place(form, secret) ?: return@forEach
                    val shown = DataSourceAutoConfiguration.withoutCredentials(url)
                    // Every substring of two characters or more, but the URL's own syntax ("//") alone.
                    val parts = (2..secret.length).flatMap { length -> secret.windowed(length) }
                        .filter { part -> part.any(Char::isLetter) }
                    parts.filter { it in shown }.assert().describedAs("$url shown as $shown").isEmpty()
                }
            }
        }
    }

    @Test
    fun `a datasource url alone should leave the rest to the driver, and a named driver should be taken`() {
        hostAlone.withPropertyValues("spring.datasource.url=jdbc:postgresql://db:5432/shop").run { context ->
            val dataSource = context.getBean(DataSource::class.java) as HikariDataSource
            dataSource.username.assert().isNull()
            dataSource.password.assert().isNull()
            dataSource.driverClassName.assert().isNull()
        }
        hostAlone.withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://db:5432/shop",
            "spring.datasource.driver-class-name=org.postgresql.Driver",
        ).run { context ->
            (context.getBean(DataSource::class.java) as HikariDataSource).driverClassName.assert()
                .isEqualTo("org.postgresql.Driver")
        }
    }

    @Test
    fun `a mongodb uri should give a MongoClient`() {
        hostAlone.withPropertyValues("spring.mongodb.uri=mongodb://mongo:27017/shop").run { context ->
            context.getBeansOfType(MongoClient::class.java).keys.assert().containsExactly("wowMongoClient")
            // The client is built with the stores' pool gauge: the driver's default pool of 100.
            context.getBean(MongoPoolGauge::class.java).maxConnections.assert().isEqualTo(100)
        }
        hostAlone.withPropertyValues("spring.mongodb.uri=mongodb://mongo:27017/shop?maxPoolSize=12").run { context ->
            context.getBean(MongoPoolGauge::class.java).maxConnections.assert().isEqualTo(12)
        }
    }

    @Test
    fun `an application's own MongoClient should leave the gauge to it`() {
        hostAlone.withUserConfiguration(OwnDatabases::class.java).run { context ->
            context.getBean(MongoPoolGauge::class.java).maxConnections.assert().isNull()
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

    private companion object {
        const val SECRET = "s3cret-pw"
    }
}
