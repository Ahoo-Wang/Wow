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

import { fetcher, UrlBuilder } from "@ahoo-wang/fetcher";
import { coSecConfigurer } from "./cosec.ts";

/**
 * How long a request waits for the server to answer. Without it a server that
 * takes the connection and never answers holds the page for ever: a view
 * opening stays a skeleton, and four slow queries hold every slot the view
 * engine runs (its request runner), so the other panels of a board never ask.
 * Timed up to the response headers, so a stream that has started is not cut.
 */
export const REQUEST_TIMEOUT_MS = 60_000;

fetcher.urlBuilder = new UrlBuilder(import.meta.env.VITE_API_BASE_URL);
fetcher.timeout = REQUEST_TIMEOUT_MS;
coSecConfigurer.applyTo(fetcher);
