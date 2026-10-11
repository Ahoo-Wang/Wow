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

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router';
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
import './styles.css';
import { withCoSec } from './cosec.ts';
import { createAnalyticsRouter } from './router.tsx';

// Every request of the console, the view engine's and the view store's
// among them, goes out on the default fetcher with the CoSec token.
withCoSec();

const root = document.getElementById('root');
if (!root) throw new Error("The console's root element #root was not found");

createRoot(root).render(
  <StrictMode>
    <RouterProvider router={createAnalyticsRouter()} />
  </StrictMode>,
);
