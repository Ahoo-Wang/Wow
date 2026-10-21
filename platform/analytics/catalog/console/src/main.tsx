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

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createHashRouter, RouterProvider } from "react-router";
import "@ahoo-wang/wow-view-engine/styles.css";
import "./index.css";
import { ServicesProvider } from "@/app/services.tsx";
import { I18nProvider } from "@/i18n.tsx";
import { ROUTES } from "@/router.tsx";
import { createCatalogServices } from "@/services/catalog.ts";

const root = document.getElementById("root");
if (!root) throw new Error("The console's root element #root was not found.");

const services = createCatalogServices();
const router = createHashRouter(ROUTES);

createRoot(root).render(
  <StrictMode>
    <I18nProvider>
      <ServicesProvider services={services}>
        <RouterProvider router={router} />
      </ServicesProvider>
    </I18nProvider>
  </StrictMode>,
);
