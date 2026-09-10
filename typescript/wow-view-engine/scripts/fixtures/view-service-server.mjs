/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';

/** Test-only HTTP boundary. Identities are resolved by server-owned bearer sessions, never request bodies. */
export async function startViewService({
  Host,
  ServiceError,
  statuses,
  definition,
  instances,
  source,
  port = 0,
  allowedOrigin = 'http://127.0.0.1:6006',
}) {
  const values = new Map();
  const accounts = new Map([
    [
      'alice-token',
      {
        user: 'alice',
        service: 'tenant',
        writer: true,
        order: true,
        revision: 1,
      },
    ],
    [
      'bob-token',
      {
        user: 'bob',
        service: 'tenant',
        writer: false,
        order: true,
        revision: 1,
      },
    ],
    [
      'outsider-token',
      {
        user: 'alice',
        service: 'other-tenant',
        writer: true,
        order: true,
        revision: 1,
      },
    ],
  ]);
  const control = {
    delayNextRead: 0,
    delayNextPermissionResponse: 0,
    delayedPermissionResponses: 0,
    delayNextCreateResponse: 0,
    delayedCreateResponses: 0,
    dropNextCreateResponse: false,
    abortedReads: 0,
    delayedReads: 0,
    mutations: 0,
  };
  const hostFor = account =>
    new Host({
      definition,
      instances,
      store: values,
      serviceKey: account.service,
      scopeKey: account.user,
      resolveSource: () => source,
      instancePermissions: instance => ({
        save: account.writer || instance.scope.type === 'personal',
        rename: account.writer || instance.scope.type === 'personal',
        delete: account.writer || instance.scope.type === 'personal',
        saveAsPersonal: true,
        saveAsShared: account.writer,
      }),
      canReorder: () => account.order,
      permissionsRevision: () => account.revision,
    });
  const server = createServer(async (request, response) => {
    response.setHeader('Vary', 'Origin');
    if (request.headers.origin && request.headers.origin !== allowedOrigin) {
      response.writeHead(403).end();
      return;
    }
    response.setHeader('Access-Control-Allow-Origin', allowedOrigin);
    response.setHeader(
      'Access-Control-Allow-Headers',
      'authorization, content-type, idempotency-key, if-match',
    );
    response.setHeader(
      'Access-Control-Allow-Methods',
      'GET, POST, PUT, PATCH, DELETE, OPTIONS',
    );
    if (request.method === 'OPTIONS') {
      response.writeHead(204).end();
      return;
    }
    response.setHeader('Content-Type', 'application/json');
    response.setHeader('Cache-Control', 'no-store');
    const controller = new AbortController();
    request.on('aborted', () => controller.abort());
    response.on('close', () => {
      if (!response.writableEnded) controller.abort();
    });
    let host;
    const send = async (status, data, error) => {
      let permissions;
      if (host) {
        try {
          permissions = await host.permission.load(definition.id);
        } catch (permissionError) {
          if (status < 400) throw permissionError;
        }
      }
      if (
        request.url.endsWith('/permissions') &&
        control.delayNextPermissionResponse
      ) {
        const ms = control.delayNextPermissionResponse;
        control.delayNextPermissionResponse = 0;
        control.delayedPermissionResponses++;
        await delay(ms, undefined, { signal: controller.signal });
      }
      if (!response.destroyed)
        response.writeHead(status).end(
          JSON.stringify({
            data: data ?? null,
            permissions,
            ...(error ? { error } : {}),
          }),
        );
    };
    try {
      const token = request.headers.authorization?.replace(/^Bearer\s+/i, '');
      const account = accounts.get(token);
      if (!account) throw new ServiceError('UNAUTHENTICATED', '服务端会话无效');
      host = hostFor(account);
      const path = new URL(request.url, 'http://localhost').pathname
        .split('/')
        .filter(Boolean)
        .map(decodeURIComponent);
      if (
        path[0] !== 'view-service' ||
        path[1] !== 'definitions' ||
        path[2] !== definition.id
      )
        throw new ServiceError('NOT_FOUND', '视图定义不存在');
      if (request.method === 'GET' && control.delayNextRead) {
        control.delayedReads++;
        const ms = control.delayNextRead;
        control.delayNextRead = 0;
        try {
          await delay(ms, undefined, { signal: controller.signal });
        } catch (error) {
          if (controller.signal.aborted) control.abortedReads++;
          throw error;
        }
      }
      let text = '';
      for await (const chunk of request) {
        text += chunk;
        if (text.length > 1024 * 1024)
          throw new ServiceError('INVALID_ARGUMENT', '请求体过大');
      }
      let body;
      try {
        body = text ? JSON.parse(text) : undefined;
      } catch {
        throw new ServiceError('INVALID_ARGUMENT', 'JSON 无效');
      }
      const revision = () => {
        try {
          const value = JSON.parse(request.headers['if-match'] ?? 'null');
          if (typeof value !== 'string' || !value) throw new Error();
          return value;
        } catch {
          throw new ServiceError(
            'PRECONDITION_REQUIRED',
            '写入必须提供 If-Match revision',
          );
        }
      };
      let result;
      if (request.method === 'GET' && path.length === 3)
        result = await host.definition.load(definition.id, controller.signal);
      else if (
        request.method === 'GET' &&
        path[3] === 'permissions' &&
        path.length === 4
      )
        result = await host.permission.load(definition.id, controller.signal);
      else if (
        request.method === 'GET' &&
        path[3] === 'instances' &&
        path.length === 4
      )
        result = await host.instance.list(definition.id, controller.signal);
      else if (
        request.method === 'GET' &&
        path[3] === 'instances' &&
        path.length === 5
      )
        result = await host.instance.load(path[4], controller.signal);
      else if (
        request.method === 'POST' &&
        path[3] === 'instances' &&
        path.length === 4
      ) {
        result = await host.instance.create(body, {
          requestId: request.headers['idempotency-key'],
          signal: controller.signal,
        });
        control.mutations++;
        if (control.delayNextCreateResponse) {
          const ms = control.delayNextCreateResponse;
          control.delayNextCreateResponse = 0;
          control.delayedCreateResponses++;
          await delay(ms, undefined, { signal: controller.signal });
        }
        if (control.dropNextCreateResponse) {
          control.dropNextCreateResponse = false;
          response.destroy();
          return;
        }
      } else if (
        request.method === 'PUT' &&
        path[3] === 'instances' &&
        path.length === 5
      ) {
        if (!body || body.id !== path[4] || body.revision !== revision())
          throw new ServiceError('INVALID_ARGUMENT', '路径、正文和版本不一致');
        result = await host.instance.save(body);
        control.mutations++;
      } else if (
        request.method === 'PATCH' &&
        path[3] === 'instances' &&
        path[5] === 'name' &&
        path.length === 6
      ) {
        result = await host.instance.rename(path[4], body?.title, revision());
        control.mutations++;
      } else if (
        request.method === 'DELETE' &&
        path[3] === 'instances' &&
        path.length === 5
      ) {
        result = await host.instance.delete(path[4], revision());
        control.mutations++;
      } else if (
        request.method === 'PUT' &&
        path[3] === 'order' &&
        path.length === 4
      ) {
        result = await host.preference.saveOrder(
          definition.id,
          body?.instanceIds,
        );
        control.mutations++;
      } else if (
        request.method === 'PUT' &&
        path[3] === 'default' &&
        path.length === 4
      ) {
        result = await host.preference.saveDefault(
          definition.id,
          body?.instanceId,
        );
        control.mutations++;
      } else throw new ServiceError('NOT_FOUND', '接口不存在');
      await send(200, result);
    } catch (error) {
      if (controller.signal.aborted) return;
      const code = error instanceof ServiceError ? error.code : 'UNAVAILABLE';
      if (code === 'UNAUTHENTICATED')
        response.setHeader('WWW-Authenticate', 'Bearer');
      try {
        await send(statuses[code], null, { code, message: error.message });
      } catch {
        if (!response.destroyed)
          response.writeHead(503).end(
            JSON.stringify({
              error: { code: 'UNAVAILABLE', message: '服务存储不可用' },
            }),
          );
      }
    }
  });
  await new Promise(resolve => server.listen(port, '127.0.0.1', resolve));
  return {
    baseUrl: `http://127.0.0.1:${server.address().port}/view-service/`,
    control,
    hostFor: token => hostFor(accounts.get(token)),
    setWriter(token, writer) {
      const account = accounts.get(token);
      account.writer = writer;
      account.revision++;
    },
    async close() {
      server.closeAllConnections();
      await new Promise(resolve => server.close(resolve));
    },
  };
}
