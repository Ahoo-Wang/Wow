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

import { analysisMessages } from './analysis.js';
import { buildingMessages } from './building.js';
import { capabilitiesMessages } from './capabilities.js';
import { bulkMessages } from './bulk.js';
import { clicksMessages } from './clicks.js';
import { configMessages } from './config.js';
import { dashboardMessages } from './dashboard.js';
import { definitionMessages } from './definition.js';
import { embedMessages } from './embed.js';
import { exportMessages } from './export.js';
import { filterMessages } from './filter.js';
import { filtersMessages } from './filters.js';
import { headerMessages } from './header.js';
import { manageMessages } from './manage.js';
import { recordMessages } from './record.js';
import { refreshMessages } from './refresh.js';
import { renderMessages } from './render.js';
import { reorderMessages } from './reorder.js';
import { saveMessages } from './save.js';
import { scopeMessages } from './scope.js';
import { statusMessages } from './status.js';
import { viewMessages } from './view.js';
import { workbenchMessages } from './workbench.js';

/**
 * English wording for everything this package can report, by area.
 *
 * Two namespaces share one flat map: an issue's `code`, and a `label.*` key
 * for the text a component writes itself. The files above hold one prefix
 * family each — a flat catalogue could not say which keys were still alive —
 * and this is the only place they are put back together, so `MessageKey` is
 * the union of every key the package knows.
 *
 * `test/messages.test.tsx` fails when a new issue code has no entry here, so
 * the catalogue cannot drift behind the kernels.
 */
export const en = {
  ...saveMessages,
  ...headerMessages,
  ...recordMessages,
  ...refreshMessages,
  ...exportMessages,
  ...filterMessages,
  ...configMessages,
  ...scopeMessages,
  ...viewMessages,
  ...manageMessages,
  ...analysisMessages,
  ...dashboardMessages,
  ...buildingMessages,
  ...filtersMessages,
  ...clicksMessages,
  ...embedMessages,
  ...statusMessages,
  ...definitionMessages,
  ...capabilitiesMessages,
  ...workbenchMessages,
  ...renderMessages,
  ...bulkMessages,
  ...reorderMessages,
} as const;
