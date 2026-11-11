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

package me.ahoo.wow.api.annotation

/**
 * A command only the platform sends: the service's own sagas, schedules and in-process gateway, and the other services
 * that call it on its internal port, as `wow.callers.<service>` permits them. The Manifest lists it as `internal`, with
 * its routes as for any command; the internal port serves them, and the public port does not: a request to one of them
 * there is answered as an unknown path is, a 404 problem. The public port's OpenAPI document and the clients
 * `wow-generator` generates leave it out.
 *
 * Mark a command the service's own processes record, such as the outcome of a payment gateway's charge, which a client
 * must never send itself; its authorization still applies to the calls the internal port takes.
 */
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class InternalCommand
