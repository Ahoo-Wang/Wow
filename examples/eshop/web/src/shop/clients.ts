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

import {
  Fetcher,
  type FetchExchange,
  type RequestInterceptor,
} from "@ahoo-wang/fetcher";
import {
  developmentCallerHeaders,
  type WowClientOptions,
} from "@ahoo-wang/wow-client";
import { EshopBasketClient } from "@/generated/basket/eshop-basket";
import { EshopCatalogClient } from "@/generated/catalog/eshop-catalog";
import { EshopInventoryClient } from "@/generated/inventory/eshop-inventory";
import { EshopOrderingClient } from "@/generated/ordering/eshop-ordering";
import { EshopPaymentClient } from "@/generated/payment/eshop-payment";
import type { User } from "./session";

/**
 * The headers the servers read the caller from in development: a buyer owns
 * what it creates, an employee acts for the whole tenant.
 */
export function callerHeaders(user: User): Record<string, string> {
  return developmentCallerHeaders(
    user.role === "employee"
      ? { user: user.id, roles: ["employee"] }
      : { user: user.id, owner: user.id },
  );
}

// region caller
/** Sends the caller's headers on every request, as a host's CoSec interceptor sends its token. */
class CallerHeaders implements RequestInterceptor {
  readonly name = "EshopCaller";
  readonly order = 0;

  constructor(private readonly user: User) {}

  intercept(exchange: FetchExchange): void {
    Object.assign(exchange.ensureRequestHeaders(), callerHeaders(this.user));
  }
}
// endregion

/**
 * The generated clients of the five services, one per context, all on one fetcher of the
 * page's origin: each context's routes start with `/eshop-<context>/`, and the
 * dev server forwards each prefix to its service (vite.config.ts).
 */
export function shopClients(user: User, transport?: typeof fetch) {
  const fetcher = new Fetcher({
    baseURL: "",
    ...(transport ? { fetch: transport } : {}),
  });
  fetcher.interceptors.request.use(new CallerHeaders(user));
  const options: WowClientOptions = { fetcher };
  return {
    catalog: new EshopCatalogClient(options),
    basket: new EshopBasketClient(options),
    ordering: new EshopOrderingClient(options),
    inventory: new EshopInventoryClient(options),
    payment: new EshopPaymentClient(options),
  };
}

export type ShopClients = ReturnType<typeof shopClients>;
