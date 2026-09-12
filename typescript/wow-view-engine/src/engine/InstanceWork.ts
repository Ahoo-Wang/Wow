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

import type { ViewSession, ViewInstance } from '../contracts/viewModel.js';
import { ViewServiceError } from '../contracts/viewServiceContract.js';

/** Only a definitive service rejection proves a dispatched write did not commit. */
export function hasUnknownWriteOutcome(error: unknown): boolean {
  return (
    !(error instanceof ViewServiceError) ||
    error.code === 'UNKNOWN_OUTCOME' ||
    error.code === 'UNAVAILABLE'
  );
}

interface CreateRequest {
  requestId: string;
  submitted: ViewInstance;
  knownIds: ReadonlySet<string>;
  source: ViewSession;
}
interface InstanceOperation {
  write?: symbol;
  reload?: AbortController;
  creation?: { request: CreateRequest; resultId?: string | null };
  deletion?: { revision: string };
}

/** Owns operation identities and recovery together; callers never mutate coordination maps. */
export class InstanceWork {
  private readonly operations = new Map<string, InstanceOperation>();
  private readonly deleted = new Set<string>();
  private order?: symbol;
  get ordering(): symbol | undefined {
    return this.order;
  }
  beginOrder(token: symbol): void {
    this.order = token;
  }
  finishOrder(token: symbol): void {
    if (this.order === token) this.order = undefined;
  }

  private operation(id: string): InstanceOperation {
    let operation = this.operations.get(id);
    if (!operation) {
      operation = {};
      this.operations.set(id, operation);
    }
    return operation;
  }
  private prune(id: string): void {
    const operation = this.operations.get(id);
    if (
      operation &&
      !operation.write &&
      !operation.reload &&
      !operation.creation &&
      !operation.deletion
    )
      this.operations.delete(id);
  }
  writeToken(id: string): symbol | undefined {
    return this.operations.get(id)?.write;
  }
  beginWrite(id: string, token: symbol): void {
    this.operation(id).write = token;
  }
  /** Release ownership before synchronous observers can issue the next command. */
  finishWrite(id: string, token: symbol, publish?: () => void): void {
    const operation = this.operations.get(id);
    if (operation?.write && operation.write !== token) return;
    if (operation) delete operation.write;
    this.prune(id);
    publish?.();
  }
  assertLoadable(): void {
    if (this.order) throw new Error('视图顺序正在保存，请等待操作完成');
    for (const operation of this.operations.values())
      if (operation.write && !operation.creation)
        throw new Error('实例正在写入，请等待操作完成后重新加载');
  }
  createRequest(id: string): Readonly<CreateRequest> | undefined {
    return this.operations.get(id)?.creation?.request;
  }
  beginCreate(id: string, request: CreateRequest): void {
    const operation = this.operation(id);
    if (operation.creation?.request !== request)
      operation.creation = { request };
  }
  unverifiedCreate(
    id: string,
  ): (Readonly<CreateRequest> & { id: string | null }) | undefined {
    const creation = this.operations.get(id)?.creation;
    return creation?.resultId === undefined
      ? undefined
      : { ...creation.request, id: creation.resultId };
  }
  markCreateUnverified(id: string, resultId: string | null = null): void {
    const creation = this.operations.get(id)?.creation;
    if (creation) creation.resultId = resultId;
  }
  createEntries(): readonly (readonly [string, Readonly<CreateRequest>])[] {
    return Array.from(this.operations).flatMap(([id, operation]) =>
      operation.creation ? [[id, operation.creation.request] as const] : [],
    );
  }
  finishCreate(id: string): void {
    const operation = this.operations.get(id);
    if (operation) delete operation.creation;
    this.prune(id);
  }
  preserveCreates(sessions: Readonly<Record<string, ViewSession>>): void {
    for (const [id, operation] of this.operations) {
      const creation = operation.creation;
      if (!creation) continue;
      if (Object.prototype.hasOwnProperty.call(sessions, id))
        creation.request.source = sessions[id];
      creation.resultId ??= null;
    }
  }
  reloadToken(id: string): AbortController | undefined {
    return this.operations.get(id)?.reload;
  }
  beginReload(
    id: string,
    controller: AbortController,
  ): AbortController | undefined {
    const operation = this.operation(id),
      previous = operation.reload;
    operation.reload = controller;
    return previous;
  }
  finishReload(
    id: string,
    controller: AbortController,
    publish?: () => void,
  ): void {
    const operation = this.operations.get(id);
    if (operation?.reload && operation.reload !== controller) return;
    if (operation) delete operation.reload;
    this.prune(id);
    publish?.();
  }
  unverifiedDelete(id: string): Readonly<{ revision: string }> | undefined {
    return this.operations.get(id)?.deletion;
  }
  markDeleteUnverified(id: string, revision: string): void {
    this.operation(id).deletion = { revision };
  }
  clearDelete(id: string): void {
    const operation = this.operations.get(id);
    if (operation) delete operation.deletion;
    this.prune(id);
  }
  clearDeletes(): void {
    for (const id of this.operations.keys()) this.clearDelete(id);
  }
  isDeleted(id: string): boolean {
    return this.deleted.has(id);
  }
  markDeleted(id: string): void {
    this.deleted.add(id);
    this.clearDelete(id);
  }
  forgetDeleted(id: string): void {
    this.deleted.delete(id);
  }

  assertWritable(
    session: ViewSession,
    replayingWrite = false,
    resolvingConflict = false,
  ): void {
    const id = session.instance.id;
    if (this.writeToken(id)) throw new Error('实例正在写入，请等待操作完成');
    if (this.reloadToken(id))
      throw new Error('实例正在重新加载，请等待加载完成');
    if (session.conflict && !resolvingConflict)
      throw new Error('视图存在冲突，请选择使用最新版本、另存或明确覆盖');
    if (session.requiresReload && !replayingWrite)
      throw new Error('写入结果需要核对，请先重新加载实例');
  }
  cancelReloads(): void {
    const controllers: AbortController[] = [];
    for (const [id, operation] of this.operations) {
      if (operation.reload) controllers.push(operation.reload);
      delete operation.reload;
      this.prune(id);
    }
    controllers.forEach(controller => controller.abort());
  }
  dispose(): void {
    this.order = undefined;
    this.deleted.clear();
    const controllers = Array.from(
      this.operations.values(),
      operation => operation.reload,
    );
    this.operations.clear();
    controllers.forEach(controller => controller?.abort());
  }
}
