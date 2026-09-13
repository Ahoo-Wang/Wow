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
  projectSupportedInstance,
  requireSupportedInstance,
} from '../../src/contracts/viewServiceContract.js';
import { encodeViewResourceId } from './protocol.js';
import type {
  ViewInstance,
  ViewInstanceList,
} from '@ahoo-wang/fetcher-view-engine';
import type { ViewInstanceService } from '@ahoo-wang/fetcher-view-engine';
import {
  ViewServiceError,
  type ViewCreateContext,
  type ViewDeleteResult,
} from '@ahoo-wang/fetcher-view-engine';
import type { HttpViewTransport } from './HttpViewTransport.js';
export class HttpViewInstanceService implements ViewInstanceService {
  constructor(private readonly transport: HttpViewTransport) {}
  readonly list = async (
    id: string,
    signal?: AbortSignal,
  ): Promise<ViewInstanceList> => {
    this.transport.assertDefinition(id);
    const list = await this.transport.request<ViewInstanceList>(
      '/instances',
      'GET',
      undefined,
      signal,
    );
    const instances = list.instances
      .map(item =>
        projectSupportedInstance(item, this.transport.supportedFormats),
      )
      .filter((item): item is ViewInstance => item !== null);
    return {
      instances,
      defaultInstanceId: instances.some(
        item => item.id === list.defaultInstanceId,
      )
        ? list.defaultInstanceId
        : null,
    };
  };
  readonly load = async (
    id: string,
    signal?: AbortSignal,
  ): Promise<ViewInstance> => {
    return this.supported(
      this.transport.request<ViewInstance>(
        `/instances/${encodeViewResourceId(id)}`,
        'GET',
        undefined,
        signal,
      ),
    );
  };
  readonly create = async (
    instance: Omit<ViewInstance, 'id' | 'revision'>,
    context: ViewCreateContext,
  ): Promise<ViewInstance> => {
    if (typeof context?.requestId !== 'string' || !context.requestId.trim())
      return Promise.reject(
        new ViewServiceError('INVALID_ARGUMENT', '创建必须提供 requestId'),
      );
    return this.supported(
      this.transport.request<ViewInstance>(
        '/instances',
        'POST',
        instance,
        context.signal,
        {
          'Idempotency-Key': context.requestId,
        },
      ),
    );
  };
  readonly save = async (instance: ViewInstance): Promise<ViewInstance> => {
    return this.supported(
      this.transport.request<ViewInstance>(
        `/instances/${encodeViewResourceId(instance.id)}`,
        'PUT',
        instance,
        undefined,
        this.transport.revision(instance.revision),
      ),
    );
  };
  readonly rename = async (
    id: string,
    title: string,
    revision?: string,
  ): Promise<ViewInstance> => {
    return this.supported(
      this.transport.request<ViewInstance>(
        `/instances/${encodeViewResourceId(id)}/name`,
        'PATCH',
        { title },
        undefined,
        this.transport.revision(revision),
      ),
    );
  };
  readonly delete = async (
    id: string,
    revision?: string,
  ): Promise<ViewDeleteResult> => {
    const result = await this.transport.request<ViewDeleteResult>(
      `/instances/${encodeViewResourceId(id)}`,
      'DELETE',
      undefined,
      undefined,
      this.transport.revision(revision),
    );
    return {
      defaultInstance: projectSupportedInstance(
        result.defaultInstance,
        this.transport.supportedFormats,
      ),
    };
  };
  private async supported(
    result: Promise<ViewInstance>,
  ): Promise<ViewInstance> {
    return requireSupportedInstance(
      await result,
      this.transport.supportedFormats,
    );
  }
}
