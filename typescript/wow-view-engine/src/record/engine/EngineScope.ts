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

/** Lifetime and navigation identities shared by this engine's asynchronous work. */
export class EngineScope {
  loading = false;
  private generation = 0;
  private navigation = 0;
  private released = false;
  private selectionController?: AbortController;

  get version() {
    return this.generation;
  }
  get selection() {
    return this.navigation;
  }
  get disposed() {
    return this.released;
  }

  assertActive(): void {
    if (this.released) throw new Error('视图引擎已释放');
  }

  assertReady(): void {
    this.assertActive();
    if (this.loading) throw new Error('视图正在加载，请等待加载完成');
  }

  current(version: number): boolean {
    return !this.released && this.generation === version;
  }

  advanceSelection(): number {
    const selection = ++this.navigation;
    const previous = this.selectionController;
    this.selectionController = undefined;
    previous?.abort();
    return selection;
  }

  beginSelection(): { selection: number; controller: AbortController } {
    const selection = this.advanceSelection();
    const controller = new AbortController();
    if (this.navigation === selection && !this.released)
      this.selectionController = controller;
    else controller.abort();
    return { selection, controller };
  }

  restart(): number {
    this.assertActive();
    this.loading = true;
    const version = ++this.generation;
    this.advanceSelection();
    return version;
  }

  dispose(): void {
    if (this.released) return;
    this.released = true;
    this.loading = false;
    ++this.generation;
    this.advanceSelection();
  }
}
