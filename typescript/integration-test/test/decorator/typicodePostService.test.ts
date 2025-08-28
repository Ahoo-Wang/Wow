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
import { Post, typicodePostService } from '../../src';


describe('TypicodePostService Integration Test', () => {
  it('should get posts from typicode API', async () => {
    const posts = await typicodePostService.getPosts();
    expect(posts).toBeDefined();
    expect(Array.isArray(posts)).toBe(true);
    expect(posts.length).toBeGreaterThan(0);

    const post = posts[0];
    expect(post).toHaveProperty('id');
    expect(post).toHaveProperty('userId');
    expect(post).toHaveProperty('title');
    expect(post).toHaveProperty('body');
  });

  it('should get a single post by ID', async () => {
    const postId = '1';
    const post = await typicodePostService.getPost(postId);
    expect(post).toBeDefined();
    expect(post.id).toBe(parseInt(postId));
    expect(post).toHaveProperty('userId');
    expect(post).toHaveProperty('title');
    expect(post).toHaveProperty('body');
  });

  it('should filter posts by userId', async () => {
    const userId = '1';
    const posts = await typicodePostService.filterPosts(userId);
    expect(posts).toBeDefined();
    expect(Array.isArray(posts)).toBe(true);

    if (posts.length > 0) {
      posts.forEach(post => {
        expect(post.userId).toBe(parseInt(userId));
      });
    }
  });

  it('should create a new post', async () => {
    const newPost: Partial<Post> = {
      userId: 1,
      title: 'Test Post',
      body: 'This is a test post',
    };

    const createdPost = await typicodePostService.createPost(newPost as Post);
    expect(createdPost).toBeDefined();
    expect(createdPost).toHaveProperty('id');
    expect(createdPost.title).toBe(newPost.title);
    expect(createdPost.body).toBe(newPost.body);
    expect(createdPost.userId).toBe(newPost.userId);
  });

  it('should update a post', async () => {
    const postId = '1';
    const updatedPost: Partial<Post> = {
      userId: 1,
      title: 'Updated Post',
      body: 'This post has been updated',
    };

    const post = await typicodePostService.updatePost(postId, updatedPost as Post);
    expect(post).toBeDefined();
    expect(post.id).toBe(parseInt(postId));
    expect(post.title).toBe(updatedPost.title);
    expect(post.body).toBe(updatedPost.body);
    expect(post.userId).toBe(updatedPost.userId);
  });

  it('should create a new post', async () => {
    const newPost: Post = {
      userId: 1,
      title: 'Test Post',
      body: 'This is a test post',
    } as Post;

    const createdPost = await typicodePostService.createPost(newPost);
    expect(createdPost).toBeDefined();
    expect(createdPost).toHaveProperty('id');
    expect(createdPost.title).toBe(newPost.title);
    expect(createdPost.body).toBe(newPost.body);
    expect(createdPost.userId).toBe(newPost.userId);
  });

  it('should update a post', async () => {
    const postId = '1';
    const updatedPost: Post = {
      userId: 1,
      title: 'Updated Post',
      body: 'This post has been updated',
    } as Post;

    const post = await typicodePostService.updatePost(postId, updatedPost);
    expect(post).toBeDefined();
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

    const post = await typicodePostService.patchPost(postId, patchedPost);
    expect(post).toBeDefined();
    expect(post.id).toBe(parseInt(postId));
    expect(post.title).toBe(patchedPost.title);
  });

  it('should delete a post', async () => {
    const postId = '1';
    const emptyResult = await typicodePostService.deletePost(postId);
    expect(emptyResult).toBeDefined();
    expect(emptyResult).toEqual({});
    // This should not throw an error
  });
});
