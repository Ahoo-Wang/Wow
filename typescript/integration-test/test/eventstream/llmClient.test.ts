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

import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import {
  LlmClient,
  createLlmFetcher, llmFetcherName,
} from '../../src/eventstream/llmClient';
import { fetcherRegistrar } from '@ahoo-wang/fetcher';

describe('LlmClient Integration Test', () => {
  let llmClient: LlmClient | null = null;

  beforeAll(() => {
    // Get LLM options from environment variables
    const baseURL = process.env.FETCHER_LLM_BASE_URL;
    const apiKey = process.env.FETCHER_LLM_API_KEY;
    const model = process.env.FETCHER_LLM_MODEL;

    // Skip tests if environment variables are not set
    if (!baseURL || !apiKey) {
      console.warn(
        'LLM_BASE_URL , LLM_API_KEY , LLM_MODEL environment variables not set. Skipping LLM tests.',
      );
      return;
    }

    const llmOptions = {
      baseURL,
      apiKey,
      model,
    };

    const llmFetcher = createLlmFetcher(llmOptions);
  });

  beforeEach(() => {
    if (fetcherRegistrar.get(llmFetcherName)) {
      llmClient = new LlmClient();
    }
  });

  it('should stream chat completion', async () => {
    // Skip the test if the client wasn't initialized
    if (!llmClient) {
      console.warn('LLM client not initialized. Skipping test.');
      return;
    }

    const chatRequest = {
      messages: [{ role: 'user', content: 'Hello, how are you?' }],
      stream: true,
      max_tokens: 1,
    };

    const stream = await llmClient.streamChat(chatRequest);
    expect(stream).toBeDefined();

    // Collect events from the stream
    const events: any[] = [];
    for await (const event of stream) {
      events.push(event);
      // Break after receiving a few events to avoid long-running test
      if (events.length >= 3) {
        break;
      }
    }

    expect(events.length).toBeGreaterThan(0);
  }, 30000); // 30 second timeout for LLM API calls

  it('should chat completion', async () => {
    // Skip the test if the client wasn't initialized
    if (!llmClient) {
      console.warn('LLM client not initialized. Skipping test.');
      return;
    }

    const chatRequest = {
      messages: [{ role: 'user', content: 'Hello, how are you?' }],
      stream: false,
      max_tokens: 1,
    };

    const response = await llmClient.chat(chatRequest);
    expect(response).toBeDefined();
    expect(response.choices).toBeDefined();
    expect(Array.isArray(response.choices)).toBe(true);
    expect(response.choices.length).toBeGreaterThan(0);

    const choice = response.choices[0];
    expect(choice.message).toBeDefined();
    if (choice.message) {
      expect(choice.message.content).toBeDefined();
      expect(typeof choice.message.content).toBe('string');
      if (choice.message.content) {
        expect(choice.message.content.length).toBeGreaterThan(0);
      }
    }
  }, 30000); // 30 second timeout for LLM API calls
});
