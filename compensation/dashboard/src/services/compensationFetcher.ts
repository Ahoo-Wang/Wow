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

import { NamedFetcher } from "@ahoo-wang/fetcher";
import { type ApiMetadata } from "@ahoo-wang/fetcher-decorator";
import { cosecRequestInterceptor } from "./cosec.ts";

export const COMPENSATION_FETCHER_NAME = "compensation";
export const compensationFetcher = new NamedFetcher(COMPENSATION_FETCHER_NAME, {
  baseURL: import.meta.env.VITE_API_BASE_URL,
});
compensationFetcher.interceptors.request.use(cosecRequestInterceptor);
export const executionFailedClientOptions: ApiMetadata = {
  fetcher: compensationFetcher,
  basePath: "execution_failed",
};
