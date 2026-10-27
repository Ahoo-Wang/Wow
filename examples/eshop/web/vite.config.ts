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

import { fileURLToPath } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, type ProxyOptions } from "vite";

/**
 * The five services, each by the path prefix of its context's routes and its
 * public port in the eShop README. `ESHOP_<CONTEXT>_URL` points one elsewhere
 * (the end-to-end test points them at the ports it reads back).
 */
export const SERVICES = {
  catalog: 8081,
  basket: 8082,
  ordering: 8083,
  inventory: 8084,
  payment: 8085,
} as const;

/** One proxy per context: `/eshop-<context>/…` goes to its service. */
export function serviceProxies(
  env: Record<string, string | undefined> = process.env,
): Record<string, ProxyOptions> {
  return Object.fromEntries(
    Object.entries(SERVICES).map(([context, port]) => [
      `/eshop-${context}/`,
      {
        target:
          env[`ESHOP_${context.toUpperCase()}_URL`] ??
          `http://127.0.0.1:${port}`,
        changeOrigin: true,
      },
    ]),
  );
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  server: { proxy: serviceProxies() },
  preview: { proxy: serviceProxies() },
});
