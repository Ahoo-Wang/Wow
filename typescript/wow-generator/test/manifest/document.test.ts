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

import { describe, expect, it } from 'vitest';
import { GeneratorError } from '../../src/api/errors';
import { validateManifest } from '../../src/manifest/document';

/** The problems `validateManifest` names for a Manifest of one context `c`. */
function problemsOf(context: Record<string, unknown>, top = {}): string {
  try {
    validateManifest(
      { format: 1, contexts: [{ name: 'c', ...context }], ...top },
      'm.json',
    );
  } catch (error) {
    expect(error).toBeInstanceOf(GeneratorError);
    expect((error as GeneratorError).kind).toBe('specification');
    return (error as Error).message;
  }
  return '';
}

const AGGREGATE = { name: 'a', state: { schema: '#/$defs/S' } };

describe('a Manifest of a malformed shape', () => {
  it('is refused naming each list that is not one', () => {
    expect(
      problemsOf(
        {
          aggregates: { a: AGGREGATE },
          commands: {},
          errors: 'none',
          subscriptions: 1,
          routes: {},
        },
        { requires: 'routes' },
      ),
    ).toBe(
      'm.json is not a Manifest wow-generator can read: requires is not a list; c.aggregates is not a list; c.commands is not a list; c.errors is not a list; c.subscriptions is not a list; c.routes is not a list.',
    );
    expect(
      problemsOf({
        aggregates: [
          {
            ...AGGREGATE,
            commands: {},
            events: {},
            queries: {},
            access: {},
          },
        ],
      }),
    ).toBe(
      'm.json is not a Manifest wow-generator can read: c.a.commands is not a list; c.a.events is not a list; c.a.queries is not a list; c.a.access is not a list.',
    );
  });

  it("is refused naming a command's routes and produced events that are not what they should be", () => {
    expect(
      problemsOf({
        aggregates: [
          {
            ...AGGREGATE,
            commands: [
              { name: 'Do', schema: '#/$defs/D', routes: {} },
              {
                name: 'Go',
                schema: '#/$defs/D',
                routes: [{ method: 'POST' }],
                produces: 'Went',
              },
            ],
          },
        ],
        commands: [
          { routes: [{ method: 'POST', path: '/c/wow.schedule/{id}/x' }] },
          { name: 'wow.schedule.EnableJob', routes: [{}] },
        ],
      }),
    ).toBe(
      'm.json is not a Manifest wow-generator can read: c.a.Do.routes is not a list; c.a.Go.routes[0] has no method or path; c.a.Go.produces is not a list; c.commands[0] has routes but no name; c.wow.schedule.EnableJob.routes[0] has no method or path.',
    );
  });

  it('is refused naming an error without a code, or a subscription without a name', () => {
    expect(
      problemsOf({
        errors: [{ status: 409 }, 'c.y'],
        subscriptions: [{}, 'P.f'],
      }),
    ).toBe(
      'm.json is not a Manifest wow-generator can read: c.errors[0] has no code; c.errors[1] has no code; c.subscriptions[0] has no name; c.subscriptions[1] has no name.',
    );
  });

  it('is refused naming a service route without a name or a route, or whose parameters are not a list', () => {
    expect(
      problemsOf({
        routes: [
          { route: { method: 'GET', path: '/c' } },
          { name: 'ping' },
          { name: 'list', route: 'GET /c', parameters: {} },
        ],
      }),
    ).toBe(
      'm.json is not a Manifest wow-generator can read: c.routes[0] has no name; the route ping of c has no route of a method and a path; the route list of c has no route of a method and a path; the route list of c: parameters is not a list.',
    );
  });

  it('reads what an earlier version read, the members it reads as far as they go', () => {
    // Each of these was generated with exit 0 before the shape was checked: refusing one would break its build.
    expect(
      problemsOf({
        aggregates: [
          {
            ...AGGREGATE,
            commands: [
              {
                name: 'Do',
                schema: '#/$defs/D',
                aliases: 'Did',
                produces: [3],
                routes: [{ method: 'POST', path: '/c/a/{id}/do' }],
              },
              {
                name: 'Go',
                schema: '#/$defs/D',
                aliases: ['Went', 2],
              },
            ],
            queries: [{}, 'snapshot'],
            access: [{ name: 'tenant' }, { type: 'string' }, 'owner'],
          },
        ],
        commands: [{}, 'x', { name: 'wow.schedule.EnableJob', routes: {} }],
        errors: [{ code: 'c.x' }],
        routes: [
          {
            name: 'find',
            route: { method: 'GET', path: '/c/find' },
            parameters: [{ name: 'q' }, { in: 'query' }, 'r'],
          },
        ],
      }),
    ).toBe('');
  });

  it('reads a list given as null as absent, as an earlier version did', () => {
    // Each was read through `?? []` before its shape was checked.
    const command = { name: 'Do', schema: '#/$defs/D', routes: null };
    expect(
      problemsOf(
        {
          aggregates: [
            {
              ...AGGREGATE,
              commands: [{ ...command, aliases: null, produces: null }],
              events: null,
              queries: null,
              access: null,
            },
          ],
          commands: null,
          errors: null,
          subscriptions: null,
          routes: [
            {
              name: 'find',
              route: { method: 'GET', path: '/c/find' },
              parameters: null,
            },
          ],
        },
        { requires: null },
      ),
    ).toBe('');
    expect(problemsOf({ aggregates: null, routes: null })).toBe('');
    expect(problemsOf({ aggregates: [{ ...AGGREGATE, commands: null }] })).toBe(
      '',
    );
  });

  it('reads a Manifest of the shape it needs', () => {
    expect(
      problemsOf({
        aggregates: [
          {
            ...AGGREGATE,
            commands: [
              {
                name: 'Do',
                schema: '#/$defs/D',
                aliases: ['Did'],
                produces: ['Done'],
                routes: [{ method: 'POST', path: '/c/a/{id}/do' }],
              },
            ],
            events: [{ name: 'Done', schema: '#/$defs/E' }],
            queries: [{ model: 'snapshot', routes: [] }],
            access: [{ name: 'tenant', type: 'string' }],
          },
        ],
        errors: [{ code: 'c.x', status: 409 }],
        subscriptions: [{ name: 'P.f' }],
        routes: [
          {
            name: 'find',
            route: { method: 'GET', path: '/c/find' },
            parameters: [{ name: 'q', in: 'query' }],
          },
        ],
      }),
    ).toBe('');
  });
});
