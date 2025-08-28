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
import { typicodeUserService } from '../../src';


describe('TypicodeUserService Integration Test', () => {

  it('should get user albums', async () => {
    const userId = '1';
    const albums = await typicodeUserService.getAlbums(userId);
    expect(albums).toBeDefined();
    expect(Array.isArray(albums)).toBe(true);

    if (albums.length > 0) {
      const album = albums[0];
      expect(album).toHaveProperty('id');
      expect(album).toHaveProperty('userId');
      expect(album).toHaveProperty('title');
      expect(album.userId).toBe(parseInt(userId));
    }
  });

  it('should get user todos', async () => {
    const userId = '1';
    const todos = await typicodeUserService.getTodos(userId);
    expect(todos).toBeDefined();
    expect(Array.isArray(todos)).toBe(true);

    if (todos.length > 0) {
      const todo = todos[0];
      expect(todo).toHaveProperty('id');
      expect(todo).toHaveProperty('userId');
      expect(todo).toHaveProperty('title');
      expect(todo).toHaveProperty('completed');
      expect(todo.userId).toBe(parseInt(userId));
    }
  });

  it('should get user posts', async () => {
    const userId = '1';
    const posts = await typicodeUserService.getPosts(userId);
    expect(posts).toBeDefined();
    expect(Array.isArray(posts)).toBe(true);

    if (posts.length > 0) {
      const post = posts[0];
      expect(post).toHaveProperty('id');
      expect(post).toHaveProperty('userId');
      expect(post).toHaveProperty('title');
      expect(post).toHaveProperty('body');
      expect(post.userId).toBe(parseInt(userId));
    }
  });
});
