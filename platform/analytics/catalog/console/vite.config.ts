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
import { defineConfig } from "vite";

/** The analytics catalog server the dev server forwards the API to. */
const server = process.env.CATALOG_SERVER ?? "http://localhost:8087";

/**
 * The console is served by the analytics catalog server from its
 * distribution's `ui/` folder (`spring.web.resources.static-locations`), so
 * every API path is same-origin; in development Vite forwards them: the
 * catalog's routes and its relay (`/analytics-catalog`) and the framework's,
 * the datasets served among them (`/wow`).
 */
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    proxy: Object.fromEntries(
      ["/analytics-catalog", "/wow"].map((path) => [
        path,
        { target: server, changeOrigin: true },
      ]),
    ),
  },
});
