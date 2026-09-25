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
 * The one shape a Wow server's error takes in an application: `WowError`,
 * carrying the server's `ErrorInfo` (`errorCode`, `errorMsg`,
 * `bindingErrors`) and the HTTP status.
 *
 * It arrives two ways, which meet in `toWowError`:
 * - a server-sent event stream errors with a `WowError` itself when the
 *   server sends an error event midway (the extractors of `transport/`);
 * - any other failed request rejects with the fetcher's `ExchangeError`,
 *   because the fetcher validates the status before a result extractor runs;
 *   `toWowError(error)` reads the `ErrorInfo` body, or the `Wow-Error-Code`
 *   header, from a clone of its response.
 *
 * `toWowError` accepts both, so an application handles every server error
 * after one `await toWowError(error)`. Nothing here imports a fetcher
 * package: `toWowError` reads the fetcher error by its shape.
 */
export * from './errorInfo.js';
export * from './wowError.js';
export * from './headers.js';
