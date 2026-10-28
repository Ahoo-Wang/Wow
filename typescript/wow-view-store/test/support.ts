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

import { vi } from 'vitest';
import { Fetcher } from '@ahoo-wang/fetcher';
import { developmentCaller } from '@ahoo-wang/wow-client/testing';
import {
  fakeViewStore,
  type FakeViewStore,
  type FakeViewStoreOptions,
} from './support/fakeViewStore.js';

export const BASE_URL = 'http://view-store.test/';

/** Who asks: the headers the service's caller resolver reads. */
export interface Caller {
  user?: string;
  tenant?: string;
  roles?: readonly string[];
  app?: string;
}

/** Both writer roles of the service as shipped. */
export const WRITER_ROLES = [
  'view-store.shared-writer',
  'view-store.system-writer',
] as const;

/**
 * A Fetcher on {@link BASE_URL} that asks as `caller`, the owner of its
 * personal views; the anonymous caller without a user.
 */
export function actingAs({ user, ...caller }: Caller): Fetcher {
  const fetcher = new Fetcher({ baseURL: BASE_URL });
  if (user !== undefined)
    fetcher.interceptors.request.use(
      developmentCaller({ user, owner: user, ...caller }),
    );
  return fetcher;
}

/** Installs the fake service as the global `fetch`, for this test. */
export function installFake(options?: FakeViewStoreOptions): FakeViewStore {
  const fake = fakeViewStore(options);
  vi.stubGlobal('fetch', vi.fn(fake.fetch));
  return fake;
}
