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

package me.ahoo.wow.example.eshop.e2e.ops

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * A minimal ops service for the failure ledger's end-to-end test (work package 6.3f, decision D141): the Wow host over
 * `:ops-domain`'s binding index, serving the context `ops` alone (`wow.contexts.include`), since the test's class
 * path holds the eShop's servers too. The ops service proper is roadmap 7.1a's `platform/ops/ops-server`. Its package
 * is apart from the e2e tests', so its component scan finds none of their configurations.
 */
@SpringBootApplication(proxyBeanMethods = false)
class OpsTestServer
