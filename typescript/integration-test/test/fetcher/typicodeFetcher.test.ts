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
import { typicodeFetcher } from '../../src';
import { HttpMethod, ResultExtractors } from '@ahoo-wang/fetcher';

describe('typicodeFetcher Integration Test', () => {
  it('should fetch posts from typicode API', async () => {
    const response = await typicodeFetcher.get('/posts');
    expect(response).toBeDefined();
    const posts = await response.json();
    expect(Array.isArray(posts)).toBe(true);
    expect(posts.length).toBeGreaterThan(0);

    const post = posts[0];
    expect(post).toHaveProperty('id');
    expect(post).toHaveProperty('userId');
    expect(post).toHaveProperty('title');
    expect(post).toHaveProperty('body');
  });

  it('get Twice', async () => {
    const awaitPosts = typicodeFetcher.request(
      {
        method: HttpMethod.GET,
        url: '/posts',
      },
      { resultExtractor: ResultExtractors.Json },
    );
    const posts = await awaitPosts;
    const _posts = await awaitPosts;
    expect(posts).toBe(_posts);
  });

  it('should fetch a single post by ID', async () => {
    const postId = '1';
    const response = await typicodeFetcher.get(`/posts/${postId}`);
    expect(response).toBeDefined();
    const post = await response.json();
    expect(post.id).toBe(parseInt(postId));
    expect(post).toHaveProperty('userId');
    expect(post).toHaveProperty('title');
    expect(post).toHaveProperty('body');
  });

  it('should fetch posts filtered by userId', async () => {
    const userId = '1';
    const response = await typicodeFetcher.get(`/posts?userId=${userId}`);
    expect(response).toBeDefined();
    const posts = await response.json();
    expect(Array.isArray(posts)).toBe(true);

    if (posts.length > 0) {
      posts.forEach((post: any) => {
        expect(post.userId).toBe(parseInt(userId));
      });
    }
  });

  it('should create a new post', async () => {
    const newPost = {
      userId: 1,
      title: 'Test Post',
      body: 'This is a test post',
    };

    const response = await typicodeFetcher.post('/posts', {
      body: JSON.stringify(newPost),
    });
    expect(response).toBeDefined();
    const post = await response.json();
    expect(post).toHaveProperty('id');
    expect(post.title).toBe(newPost.title);
    expect(post.body).toBe(newPost.body);
    expect(post.userId).toBe(newPost.userId);
  });

  it('should update a post', async () => {
    const postId = '1';
    const updatedPost = {
      userId: 1,
      title: 'Updated Post',
      body: 'This post has been updated',
    };

    const response = await typicodeFetcher.put(`/posts/${postId}`, {
      body: updatedPost,
    });
    expect(response).toBeDefined();
    const post = await response.json();
    expect(post.id).toBe(parseInt(postId));
    expect(post.title).toBe(updatedPost.title);
    expect(post.body).toBe(updatedPost.body);
    expect(post.userId).toBe(updatedPost.userId);
  });

  it('should patch a post', async () => {
    const postId = '1';
    const patchedPost = {
      title: 'Patched Post Title',
    };

    const response = await typicodeFetcher.patch(`/posts/${postId}`, {
      body: patchedPost,
    });
    expect(response).toBeDefined();
    const post = await response.json();
    expect(post.id).toBe(parseInt(postId));
    expect(post.title).toBe(patchedPost.title);
  });

  it('should delete a post', async () => {
    const postId = '1';
    const response = await typicodeFetcher.delete(`/posts/${postId}`);
    expect(response).toBeDefined();
    // The JSONPlaceholder API returns an empty object for DELETE requests
    const result = await response.json();
    expect(result).toEqual({});
  });
});
