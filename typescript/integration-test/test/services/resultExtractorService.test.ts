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

import { describe, it, expect } from 'vitest';
import { ResultExtractorService } from '../../src';

describe('ResultExtractorService Integration Test', () => {
  const service = new ResultExtractorService();

  it('should get posts as JSON (default)', async () => {
    const posts = await service.getPosts();
    expect(posts).toBeDefined();
    expect(Array.isArray(posts)).toBe(true);
    expect(posts.length).toBeGreaterThan(0);

    const post = posts[0];
    expect(post).toHaveProperty('id');
    expect(post).toHaveProperty('userId');
    expect(post).toHaveProperty('title');
    expect(post).toHaveProperty('body');
  });

  it('should get posts as Response', async () => {
    const response = await service.getPostsAsResponse();
    expect(response).toBeDefined();
    expect(response instanceof Response).toBe(true);
    expect(response.ok).toBe(true);
    expect(response.status).toBe(200);

    // Can still parse the response as JSON
    const posts = await response.json();
    expect(Array.isArray(posts)).toBe(true);
    expect(posts.length).toBeGreaterThan(0);
  });

  it('should get posts as FetchExchange', async () => {
    const exchange = await service.getPostsAsExchange();
    expect(exchange).toBeDefined();
    expect(exchange).toHaveProperty('request');
    expect(exchange).toHaveProperty('response');
    expect(exchange).toHaveProperty('requiredResponse');

    // Verify the response is successful
    expect(exchange.requiredResponse.ok).toBe(true);
    expect(exchange.requiredResponse.status).toBe(200);

    // Can parse the response body
    const posts = await exchange.requiredResponse.json();
    expect(Array.isArray(posts)).toBe(true);
    expect(posts.length).toBeGreaterThan(0);
  });

  it('should get posts as Text', async () => {
    const text = await service.getPostsAsText();
    expect(text).toBeDefined();
    expect(typeof text).toBe('string');
    expect(text.length).toBeGreaterThan(0);

    // Should be valid JSON string
    const posts = JSON.parse(text);
    expect(Array.isArray(posts)).toBe(true);
    expect(posts.length).toBeGreaterThan(0);

    const post = posts[0];
    expect(post).toHaveProperty('id');
    expect(post).toHaveProperty('userId');
    expect(post).toHaveProperty('title');
    expect(post).toHaveProperty('body');
  });

  it('should demonstrate different result extractor behaviors', async () => {
    // Test that all result extractors return equivalent data
    const jsonPosts = await service.getPosts();
    const response = await service.getPostsAsResponse();
    const text = await service.getPostsAsText();

    // Parse all responses to JSON for comparison
    const responsePosts = await response.json();
    const textPosts = JSON.parse(text);

    // All should return the same data
    expect(jsonPosts).toEqual(responsePosts);
    expect(jsonPosts).toEqual(textPosts);
    expect(responsePosts).toEqual(textPosts);

    // Verify data structure
    expect(Array.isArray(jsonPosts)).toBe(true);
    expect(jsonPosts.length).toBeGreaterThan(0);

    const post = jsonPosts[0];
    expect(post).toHaveProperty('id');
    expect(post).toHaveProperty('userId');
    expect(post).toHaveProperty('title');
    expect(post).toHaveProperty('body');
  });
});
