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

import { combineURLs, ContentTypeValues } from '@ahoo-wang/fetcher';
import { CommandResult, CommandResultEventStream } from './commandResult';
import {
  ResultExtractor,
  ResultExtractors,
} from '@ahoo-wang/fetcher-decorator';
import { CommandHttpRequest } from './commandHttpRequest';
import { ClientOptions } from '../types';

export class CommandHttpClient {
  constructor(protected readonly options: ClientOptions) {
  }

  protected async sendCommand<R>(path: string, commandHttpRequest: CommandHttpRequest,
                                 extractor: ResultExtractor = ResultExtractors.Json): Promise<R> {
    const url = combineURLs(this.options.basePath, path);
    const request = {
      ...commandHttpRequest,
      url: url,
    };
    const exchange = await this.options.fetcher.request(request);
    return extractor(exchange);
  }

  send(
    path: string,
    commandHttpRequest: CommandHttpRequest,
  ): Promise<CommandResult> {
    return this.sendCommand(path, commandHttpRequest);
  }

  async sendAndWaitStream(
    path: string,
    commandHttpRequest: CommandHttpRequest,
  ): Promise<CommandResultEventStream> {
    commandHttpRequest.headers = {
      ...commandHttpRequest.headers,
      Accept: ContentTypeValues.TEXT_EVENT_STREAM,
    };
    return this.sendCommand(path, commandHttpRequest, ResultExtractors.JsonEventStream);
  }
}
