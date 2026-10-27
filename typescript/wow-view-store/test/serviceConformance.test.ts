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

/**
 * The view store service's own rules (`describeWowViewStoreService`) over the
 * in-memory stand-in for the V10 service: the cases the real service passes
 * in `typescript/integration-test/test/view-store/service.test.ts`, here
 * without a JVM, so the fake answers as the service does.
 */

import { WowDatasetWordings, WowViewStore } from '../src/index.js';
import { describeWowViewStoreService } from './conformance/wowViewStoreService.js';
import { actingAs, installFake } from './support.js';

const CONFIGURED = {
  definitionId: 'service-configured',
  app: 'configured-app',
  id: 'configured-1',
  title: 'Configured',
};

describeWowViewStoreService({
  name: 'the fake V10 service',
  sharedWriter: 'view-store.shared-writer',
  systemWriter: 'view-store.system-writer',
  configured: CONFIGURED,
  connect: () => {
    installFake({
      systemViews: [
        {
          id: CONFIGURED.id,
          definitionId: CONFIGURED.definitionId,
          title: CONFIGURED.title,
          appId: CONFIGURED.app,
          config: { kind: 'record' },
        },
      ],
    });
    return ({ tenant, user, app, roles }) => {
      const fetcher = actingAs({ user, tenant, app, roles });
      return {
        views: new WowViewStore({ fetcher }),
        wordings: new WowDatasetWordings({ fetcher }),
      };
    };
  },
});
