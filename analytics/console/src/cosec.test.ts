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

import { Fetcher } from '@ahoo-wang/fetcher';
import { TokenStorage } from '@ahoo-wang/fetcher-cosec';
import {
  ADMIN_ROLE,
  currentUser,
  noRefresh,
  signIn,
  signOut,
  withCoSec,
} from './cosec.ts';

/** An unsigned JWT with `payload`: the console reads claims, the server verifies. */
export function jwt(payload: Record<string, unknown>): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value))
      .replace(/=+$/, '')
      .replace(/\+/g, '-')
      .replace(/\//g, '_');
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({
    jti: 't',
    iat: now,
    exp: now + 3600,
    ...payload,
  })}.signature`;
}

describe('currentUser', () => {
  it('is nobody before a sign-in', () => {
    expect(currentUser(new TokenStorage({ key: 'test-none' }))).toBeNull();
  });

  it('reads the subject, the tenant and the roles of the token', () => {
    const storage = new TokenStorage({ key: 'test-user' });
    const token = jwt({ sub: 'ann', tenantId: 't1', roles: [ADMIN_ROLE] });
    storage.signIn({ accessToken: token, refreshToken: token });
    expect(currentUser(storage)).toEqual({
      id: 'ann',
      tenantId: 't1',
      roles: [ADMIN_ROLE],
      admin: true,
    });
    storage.signOut();
    expect(currentUser(storage)).toBeNull();
  });

  it('is no admin without the role', () => {
    const storage = new TokenStorage({ key: 'test-analyst' });
    const token = jwt({ sub: 'bob' });
    storage.signIn({ accessToken: token, refreshToken: token });
    expect(currentUser(storage)).toMatchObject({
      id: 'bob',
      roles: [],
      admin: false,
    });
  });
});

describe('withCoSec', () => {
  /** What each request carried, answered 200. */
  function recording() {
    const sent: Headers[] = [];
    const target = new Fetcher({
      baseURL: 'http://localhost',
      fetch: (async (_input: RequestInfo | URL, init?: RequestInit) => {
        sent.push(new Headers(init?.headers));
        return new Response('[]', {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }) as typeof fetch,
    } as ConstructorParameters<typeof Fetcher>[0]);
    return { sent, target: withCoSec(target) };
  }

  it('sends the signed-in token and the application with every request', async () => {
    const token = jwt({ sub: 'ann', tenantId: 't1' });
    signIn(token);
    const { sent, target } = recording();
    await target.get('/dataset');
    expect(sent[0]?.get('Authorization')).toBe(`Bearer ${token}`);
    expect(sent[0]?.get('CoSec-App-Id')).toBe('analytics');
    signOut();
  });

  it('refreshes nothing: the enterprise issues the tokens', async () => {
    await expect(
      noRefresh.refresh({ accessToken: 'a', refreshToken: 'r' }),
    ).rejects.toThrow('sign in again');
  });
});
