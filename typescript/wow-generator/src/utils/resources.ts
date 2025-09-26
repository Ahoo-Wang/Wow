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


import { readFile } from 'fs';

export function loadResource(path: string): Promise<string> {
  if (path.startsWith('http://') || path.startsWith('https://')) {
    return loadHttpResource(path);
  }
  return loadFile(path);
}

export async function loadHttpResource(url: string): Promise<string> {
  const response = await fetch(url);
  return await response.text();
}

export function loadFile(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    readFile(path, 'utf-8', (err, data) => {
      if (err) {
        reject(err);
      } else {
        resolve(data);
      }
    });
  });
}