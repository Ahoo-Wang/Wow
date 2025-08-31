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
import { CommandRequest } from './commandRequest';
import { ClientOptions } from '../types';

/**
 * HTTP client for sending commands to a remote service.
 * Provides methods for sending commands and handling command results,
 * including support for streaming responses.
 *
 * @example
 * ```typescript
 * // Create a fetcher instance
 * const wowFetcher = new Fetcher({
 *   baseURL: 'http://localhost:8080/',
 * });
 *
 * // Add interceptor to handle URL parameters if needed
 * wowFetcher.interceptors.request.use({
 *   name: 'UrlParamsInterceptor',
 *   order: URL_RESOLVE_INTERCEPTOR_ORDER - 1,
 *   intercept(exchange) {
 *     exchange.request.urlParams = {
 *       path: {
 *         ...exchange.request.urlParams?.path,
 *       },
 *       query: exchange.request.urlParams?.query,
 *     };
 *   },
 * });
 *
 * // Create CommandClient instance
 * const CommandClient = new CommandClient({
 *   fetcher: wowFetcher,
 *   basePath: 'owner/{ownerId}/cart'
 * });
 *
 * // Send a command
 * const command = {
 *   method: HttpMethod.POST,
 *   headers: {
 *     [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
 *   },
 *   urlParams: {
 *     path: {
 *       ownerId: 'ownerId',
 *     },
 *   },
 *   body: {
 *     productId: 'productId',
 *     quantity: 1,
 *   },
 * };
 *
 * const result = await CommandClient.send('add_cart_item', command);
 * ```
 */
export class CommandClient {
  /**
   * Creates a new CommandClient instance.
   * @param options - The client configuration options including the fetcher and base path
   *
   * @example
   * ```typescript
   * const CommandClient = new CommandClient({
   *   fetcher: wowFetcher,
   *   basePath: 'owner/{ownerId}/cart'
   * });
   * ```
   */
  constructor(protected readonly options: ClientOptions) {
  }

  /**
   * Sends a command to the specified path and returns the result.
   * This is a protected generic method that handles the common logic for sending commands.
   * @template R The type of the result to be returned
   * @param path - The endpoint path to send the command to
   * @param commandHttpRequest - The command HTTP request containing headers, method, and body
   * @param extractor - Function to extract the result from the response, defaults to JSON extractor
   * @returns A promise that resolves to the extracted result of type R
   */
  protected async sendCommand<R>(path: string, commandHttpRequest: CommandRequest,
                                 extractor: ResultExtractor = ResultExtractors.Json): Promise<R> {
    const url = combineURLs(this.options.basePath, path);
    const request = {
      ...commandHttpRequest,
      url: url,
    };
    const exchange = await this.options.fetcher.request(request);
    return extractor(exchange);
  }

  /**
   * Sends a command to the specified path and waits for a response.
   * 
   * @param path - The endpoint path to send the command to
   * @param commandHttpRequest - The command HTTP request containing headers, method, and body
   * @returns A promise that resolves to a CommandResult
   *
   * @example
   * ```typescript
   * const command = {
   *   method: HttpMethod.POST,
   *   headers: {
   *     [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
   *   },
   *   urlParams: {
   *     path: {
   *       ownerId: 'ownerId',
   *     },
   *   },
   *   body: {
   *     productId: 'productId',
   *     quantity: 1,
   *   },
   * };
   *
   * const result = await CommandClient.send('add_cart_item', command);
   * console.log('Command result:', result);
   * ```
   */
  send(
    path: string,
    commandHttpRequest: CommandRequest,
  ): Promise<CommandResult> {
    return this.sendCommand(path, commandHttpRequest);
  }

  /**
   * Sends a command to the specified path and waits for a streaming response.
   * Sets the Accept header to text/event-stream to indicate that the response should be streamed.
   * 
   * @param path - The endpoint path to send the command to
   * @param commandHttpRequest - The command HTTP request containing headers, method, and body
   * @returns A promise that resolves to a CommandResultEventStream for handling streaming responses
   *
   * @example
   * ```typescript
   * const command = {
   *   method: HttpMethod.POST,
   *   headers: {
   *     [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
   *   },
   *   urlParams: {
   *     path: {
   *       ownerId: 'ownerId',
   *     },
   *   },
   *   body: {
   *     productId: 'productId',
   *     quantity: 1,
   *   },
   * };
   *
   * const commandResultStream = await CommandClient.sendAndWaitStream('add_cart_item', command);
   * for await (const commandResultEvent of commandResultStream) {
   *   console.log('Received:', commandResultEvent.data);
   * }
   * ```
   */
  async sendAndWaitStream(
    path: string,
    commandHttpRequest: CommandRequest,
  ): Promise<CommandResultEventStream> {
    commandHttpRequest.headers = {
      ...commandHttpRequest.headers,
      Accept: ContentTypeValues.TEXT_EVENT_STREAM,
    };
    return this.sendCommand(path, commandHttpRequest, ResultExtractors.JsonEventStream);
  }
}