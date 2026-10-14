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

// @vitest-environment node

import { describe, expect, it } from "vitest";
import { serviceProxies } from "../vite.config";

describe("the dev server's proxies", () => {
  it("send each context's routes to its service, on the README's ports", () => {
    const proxies = serviceProxies({});
    expect(Object.keys(proxies)).toEqual([
      "/eshop-catalog/",
      "/eshop-basket/",
      "/eshop-ordering/",
      "/eshop-inventory/",
      "/eshop-payment/",
    ]);
    expect(proxies["/eshop-ordering/"].target).toBe("http://127.0.0.1:8083");
  });

  it("take a service's address from ESHOP_<CONTEXT>_URL", () => {
    expect(
      serviceProxies({ ESHOP_PAYMENT_URL: "http://127.0.0.1:54321" })[
        "/eshop-payment/"
      ].target,
    ).toBe("http://127.0.0.1:54321");
  });
});
