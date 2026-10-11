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

import { useSearchParams } from 'react-router';
import { DashboardWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { BOARDS } from '../engine.ts';
import { VIEW_PARAM } from '../routes.ts';

/** The boards' workbench: panels over any dataset, the open board in the address. */
export default function BoardsPage() {
  const [params, setParams] = useSearchParams();
  return (
    <DashboardWorkbench
      definitionId={BOARDS.id}
      instanceId={params.get(VIEW_PARAM)}
      onInstanceChange={board =>
        setParams(board === null ? {} : { [VIEW_PARAM]: board })
      }
      landmark="region"
    />
  );
}
