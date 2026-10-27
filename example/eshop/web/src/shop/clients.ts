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
import type { WowClientOptions } from "@ahoo-wang/wow-client";
import {
  BasketCommandClient,
  BasketStateClient,
} from "@/generated/basket/eshop-basket";
import {
  BrandCommandClient,
  BrandSnapshotQueryClient,
  CategoryCommandClient,
  CategorySnapshotQueryClient,
  ProductCommandClient,
  ProductSnapshotQueryClient,
} from "@/generated/catalog/eshop-catalog";
import {
  StockCommandClient,
  StockStateClient,
} from "@/generated/inventory/eshop-inventory";
import {
  OrderCommandClient,
  OrderSnapshotQueryClient,
  OrderStateClient,
} from "@/generated/ordering/eshop-ordering";
import { PaymentStateClient } from "@/generated/payment/eshop-payment";
import type { User } from "./session";

/**
 * The headers the servers read the caller from in development: a buyer owns
 * what it creates, an employee acts for the whole tenant.
 */
export function callerHeaders(user: User): Record<string, string> {
  return user.role === "employee"
    ? { "Wow-User": user.id, "Wow-Roles": "employee" }
    : { "Wow-User": user.id, "Wow-Owner": user.id };
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
 * The generated clients of the five services, all on one fetcher of the
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
    products: new ProductCommandClient(options),
    productQueries: new ProductSnapshotQueryClient(options),
    brands: new BrandCommandClient(options),
    brandQueries: new BrandSnapshotQueryClient(options),
    categories: new CategoryCommandClient(options),
    categoryQueries: new CategorySnapshotQueryClient(options),
    baskets: new BasketCommandClient(options),
    basketStates: new BasketStateClient(options),
    orders: new OrderCommandClient(options),
    orderQueries: new OrderSnapshotQueryClient(options),
    orderStates: new OrderStateClient(options),
    stocks: new StockCommandClient(options),
    stockStates: new StockStateClient(options),
    payments: new PaymentStateClient(options),
  };
}

export type ShopClients = ReturnType<typeof shopClients>;
