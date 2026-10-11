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

import { useState, type FormEvent } from 'react';
import { signIn } from '../cosec.ts';
import { HOME_PATH } from '../routes.ts';

/**
 * Signs in with a token the enterprise's CoSec issued. A single sign-on that
 * stores its token where CoSec front ends do (`cosec-token`) never shows
 * this page.
 */
export default function LoginPage() {
  const [token, setToken] = useState('');
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const value = token.trim().replace(/^Bearer\s+/i, '');
    if (!value) return;
    signIn(value);
    // A full load: the engine and the datasets follow the new principal.
    window.location.assign(HOME_PATH);
  };
  return (
    <main className="login">
      <form onSubmit={submit} aria-label="登录">
        <h1>Wow 分析</h1>
        <label htmlFor="token">访问令牌</label>
        <textarea
          id="token"
          value={token}
          onChange={event => setToken(event.target.value)}
          placeholder="粘贴企业 CoSec 签发的访问令牌"
          rows={6}
        />
        <button type="submit" disabled={!token.trim()}>
          登录
        </button>
      </form>
    </main>
  );
}
