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

/**
 * How the BI sync script of a Wow server before 8.1 stores message headers
 * in ClickHouse; the `wow.MessageHeaderSqlType` schema of those servers'
 * OpenAPI documents, which generated code names. Wow 8.1 and later always
 * use `Map(String, String)` and no longer have it.
 */
export enum MessageHeaderSqlType {
  /** `Map(String, String)` */
  MAP = 'MAP',
  /** `String` */
  STRING = 'STRING',
}
