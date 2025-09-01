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

import { ClientOptions } from '../types';
import { CommandRequest } from './commandRequest';
import { CommandResult, CommandResultEventStream } from './commandResult';
import { ResultExtractor, ResultExtractors } from '@ahoo-wang/fetcher-decorator';
import { combineURLs, ContentTypeValues } from '@ahoo-wang/fetcher';

/**
 * Command Client for sending commands to the server.
 *
 * The CommandClient is responsible for sending commands to the server and handling the responses.
 * It provides methods for both regular command execution and streaming command results.
 * 
 * @example
 * ```typescript
 * // Create a client options configuration
 * const clientOptions: ClientOptions = {
 *   fetcher: new Fetcher({ baseURL: 'http://localhost:8080/' }),
 *   basePath: 'owner/{ownerId}/cart'
 * };
 *
 * // Create a command client instance
 * const commandClient = new CommandClient(clientOptions);
 *
 * // Define command endpoint
 * const addCartItem = 'add_cart_item';
 *
 * // Create a command request
 * const addCartItemCommand: CommandRequest<AddCartItem> = {
 *   method: HttpMethod.POST,
 *   headers: {
 *     [CommandHttpHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
 *   },
 *   body: {
 *     productId: 'productId',
 *     quantity: 1,
 *   }
 * };
 *
 * // Send command and get result
 * const commandResult = await commandClient.send(addCartItem, addCartItemCommand);
 *
 * // Send command and get result as stream
 * const commandResultStream = await commandClient.sendAndWaitStream(addCartItem, addCartItemCommand);
 * for await (const commandResultEvent of commandResultStream) {
 *   console.log('Received:', commandResultEvent.data);
 * }
 * ```
 */
export class CommandClient {
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
  protected async sendCommand<R>(
    path: string,
    commandHttpRequest: CommandRequest,
    extractor: ResultExtractor = ResultExtractors.Json,
  ): Promise<R> {
    const url = combineURLs(this.options.basePath, path);
    const request = {
      ...commandHttpRequest,
      url: url,
    };
    const exchange = await this.options.fetcher.request(request);
    return extractor(exchange);
  }

  /**
   * Send a command to the server and wait for the result.
   * 
   * @param path - The endpoint path to send the command to
   * @param commandHttpRequest - The command request to send
   * @returns A promise that resolves to the command execution result
   * 
   * @example
   * ```typescript
   * const commandResult = await commandClient.send('add_cart_item', {
   *   method: HttpMethod.POST,
   *   body: {
   *     productId: 'product-1',
   *     quantity: 2
   *   }
   * });
   * ```
   */
  send(
    path: string,
    commandHttpRequest: CommandRequest,
  ): Promise<CommandResult> {
    return this.sendCommand(path, commandHttpRequest);
  }

  /**
   * Send a command to the server and wait for the result as a stream.
   * This is useful for long-running commands that produce multiple events.
   * 
   * @param path - The endpoint path to send the command to
   * @param commandHttpRequest - The command request to send
   * @returns A promise that resolves to a stream of command execution results
   * 
   * @example
   * ```typescript
   * const commandResultStream = await commandClient.sendAndWaitStream('add_cart_item', {
   *   method: HttpMethod.POST,
   *   headers: {
   *     Accept: ContentTypeValues.TEXT_EVENT_STREAM
   *   },
   *   body: {
   *     productId: 'product-1',
   *     quantity: 2
   *   }
   * });
   * 
   * for await (const commandResultEvent of commandResultStream) {
   *   console.log('Received event:', commandResultEvent.data);
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
    return this.sendCommand(
      path,
      commandHttpRequest,
      ResultExtractors.JsonEventStream,
    );
  }
}