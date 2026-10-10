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

package me.ahoo.wow.spring.boot.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.type.classreading.CachingMetadataReaderFactory

class NestedAutoConfigurationExcludeFilterTest {

    /** `@SpringBootApplication`'s component scan, without its auto-configuration, over the BI auto-configuration. */
    @SpringBootConfiguration
    @ComponentScan(
        basePackageClasses = [BiAutoConfiguration::class],
        excludeFilters = [
            ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
            ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
        ],
    )
    class ScanningHost

    private fun runScanningHost(initializers: Boolean): ConfigurableApplicationContext {
        val application = SpringApplicationBuilder(ScanningHost::class.java)
            .web(WebApplicationType.NONE)
            .properties("spring.main.banner-mode=off")
            .build()
        if (!initializers) {
            application.setInitializers(emptyList())
        }
        return application.run()
    }

    @Test
    fun `a scan over a starter package leaves the nested configurations to their auto-configuration`() {
        // BiAutoConfiguration is off (wow.bi.script.enabled is not set), so its route contract must not be offered.
        runScanningHost(initializers = true).use { context ->
            context.containsBeanDefinition(
                BiAutoConfiguration.RouteContractConfiguration::class.java.name
            ).assert().isFalse()
            context.getBeansOfType(RouteContributor::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `without the filter the scan registers a nested configuration past its outer conditions`() {
        runScanningHost(initializers = false).use { context ->
            context.getBeansOfType(RouteContributor::class.java).keys.assert()
                .containsExactly("generateBIScriptRouteContributor")
        }
    }

    @Test
    fun `matches only classes nested in Wow's auto-configurations`() {
        val filter = NestedAutoConfigurationExcludeFilter()
        val factory = CachingMetadataReaderFactory()
        fun matches(type: Class<*>): Boolean = filter.match(factory.getMetadataReader(type.name), factory)

        matches(BiAutoConfiguration.RouteContractConfiguration::class.java).assert().isTrue()
        matches(BiAutoConfiguration.WebFluxConfiguration.ClickHouseConfiguration::class.java).assert().isTrue()
        matches(BiAutoConfiguration::class.java).assert().isFalse()
        matches(ScanningHost::class.java).assert().isFalse()
        filter.assert().isEqualTo(NestedAutoConfigurationExcludeFilter())
    }

    @Test
    fun `the initializer registers the filter once`() {
        GenericApplicationContext().use { context ->
            val initializer = NestedAutoConfigurationExcludeFilterInitializer()
            initializer.initialize(context)
            initializer.initialize(context)
            context.beanFactory.getSingleton(NestedAutoConfigurationExcludeFilter.BEAN_NAME)
                .assert().isInstanceOf(NestedAutoConfigurationExcludeFilter::class.java)
        }
    }
}
