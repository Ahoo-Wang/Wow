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

package me.ahoo.wow.ops.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The ops service on the Wow host (roadmap 7.1a, decisions D14, D141): the aggregation and operation surface of every
 * service's failure ledger. The host finds the `parked` model and `ParkedOperationSaga` through `ops-domain`'s binding
 * index and serves the ops context's routes:
 *
 * - each service publishes its ledger (`wow.ops.publish.enabled`) to `wow.ops.commands`, which the node's command
 *   intake records on the entry's `parked` aggregate (the `kafka` profile);
 * - an operator reads the entries through the V10 query routes, their snapshots being the read model
 *   (`POST /ops/parked/snapshot/query`, `GET /ops/parked/{id}/state`), on a backend that serves snapshot queries
 *   (the `postgres` profile, [PostgresDataSource]);
 * - an operator's Resume, Skip, MarkRecoverable and ApplyRetrySpec (`POST /ops/parked/{id}/resume`, ...) are
 *   authorized as the ledger's system commands ([OpsAuthorizer]), recorded on the entry, and sent by the saga to the
 *   owning service as `wow.ledger.*`, which runs them (decision D141): ops never retries or skips anything itself.
 *
 * Who calls is [OpsCallers]'s to say; a deployment puts CoSec's resolver and authorizer in their place.
 */
@SpringBootApplication(proxyBeanMethods = false)
class OpsServer

fun main(args: Array<String>) {
    runApplication<OpsServer>(*args)
}
