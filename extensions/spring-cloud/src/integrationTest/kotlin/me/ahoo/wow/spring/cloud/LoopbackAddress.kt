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

package me.ahoo.wow.spring.cloud

import org.springframework.cloud.commons.util.InetUtils
import org.springframework.cloud.commons.util.InetUtilsProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers a node at the loopback address, so the test does not depend on how this machine routes its network
 * address (a proxy the JVM is given answers for it on some machines). CoSky registers the address Spring Cloud's
 * [InetUtils] finds, which skips loopback, and has no setting of its own for it; this replaces the bean Spring Cloud
 * gives when there is none.
 */
@Configuration(proxyBeanMethods = false)
class LoopbackAddress {
    @Bean
    fun inetUtils(): InetUtils = object : InetUtils(InetUtilsProperties()) {
        override fun findFirstNonLoopbackHostInfo(): HostInfo = HostInfo(LOOPBACK).apply { ipAddress = LOOPBACK }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
