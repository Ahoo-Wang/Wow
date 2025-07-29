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

package me.ahoo.wow.spring.boot.starter.prepare

import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.EnvironmentAware
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.env.Environment
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.util.ClassUtils

class ApplicationBasePackageScanner : EnvironmentAware, BeanFactoryAware {
    private lateinit var env: Environment
    private lateinit var beanFactory: BeanFactory
    override fun setEnvironment(environment: Environment) {
        this.env = environment
    }

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    /**
     * 从Environment中获取指定key对应的字符串集合
     *
     * 该方法支持两种配置方式：
     * 1. 逗号分隔的单个属性值（如 "package1,package2,package3"）
     * 2. 数组形式的属性值（如 "key[0]=value1", "key[1]=value2"）
     *
     * @param key 配置项的键名
     * @return 解析后的字符串集合，如果未找到配置则返回空集合
     */
    fun getStringSet(key: String): Set<String> {
        val basePackages = env.getProperty(key)
        if (basePackages?.isNotBlank() == true) {
            return basePackages.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        var currentIndex = 0
        return buildSet {
            while (true) {
                val basePackage = env.getProperty("$key[$currentIndex]")
                if (basePackage.isNullOrBlank()) {
                    return@buildSet
                }
                add(basePackage.trim())
                currentIndex++
            }
        }
    }

    /**
     * 获取应用的基础包路径集合
     *
     * @return 应用配置的扫描基础包路径集合，如果没有配置则返回空集合
     */
    fun getApplicationBasePackages(): Set<String> {
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return setOf()
        }
        val autoBasePackages = AutoConfigurationPackages.get(beanFactory)
        val appBasePackages = mutableSetOf<String>()
        appBasePackages.addAll(autoBasePackages)
        for (autoBasePackage in autoBasePackages) {
            val scanner = SpringBootApplicationScanner(false, env)
            val candidates = scanner.findCandidateComponents(autoBasePackage)
            for (candidate in candidates) {
                val appBeanClass = Class.forName(candidate.beanClassName)
                val appAnnotation = appBeanClass.getAnnotation(SpringBootApplication::class.java)
                appBasePackages.addAll(appAnnotation.scanBasePackages)
                val typedBasePackages = appAnnotation.scanBasePackageClasses.map {
                    ClassUtils.getPackageName(it.java)
                }
                appBasePackages.addAll(typedBasePackages)
            }
        }
        return appBasePackages
    }

    internal class SpringBootApplicationScanner(useDefaultFilters: Boolean, environment: Environment) :
        ClassPathScanningCandidateComponentProvider(useDefaultFilters, environment) {
        init {
            addIncludeFilter(AnnotationTypeFilter(SpringBootApplication::class.java))
        }

        override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean {
            return beanDefinition.metadata.isConcrete
        }
    }
}
