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

import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

/** The analytics server the dev server forwards the API to. */
const server = process.env.ANALYTICS_SERVER ?? "http://localhost:8080";

/**
 * The console is served by the analytics server from its `ui/` folder, so
 * every API path is same-origin; in development Vite forwards them.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: Object.fromEntries(
      ["/dataset", "/tenant", "/view-store", "/dataset-registry", "/actuator"].map(
        (path) => [path, { target: server, changeOrigin: true }],
      ),
    ),
  },
});
