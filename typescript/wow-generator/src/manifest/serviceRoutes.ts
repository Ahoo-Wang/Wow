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

import type { ServiceRouteManifest } from '@ahoo-wang/wow-client';
import type { ManifestContext } from './document';
import { routeMethodParameters, routeParameterIdentifier } from './names';
import type { TextNumbers, TextNumbersModel } from './textNumbers';

/** A parameter of a service route: a path variable or a query parameter, and its TypeScript type. */
export interface RouteParameterModel {
  /** As the route names it: its path variable or its query parameter. */
  readonly name: string;
  /** Its name in the method ({@link routeParameterIdentifier}). */
  readonly identifier: string;
  readonly location: 'path' | 'query';
  readonly type: string;
  readonly required: boolean;
}

/** A service route: its name, where it is served, its parameters and the types of its body and its answer. */
export interface ServiceRouteModel {
  readonly name: string;
  readonly method: string;
  readonly path: string;
  readonly description?: string;
  readonly deprecated: boolean;
  readonly parameters: readonly RouteParameterModel[];
  /** The body's type; absent when the route takes none. */
  readonly requestType?: string;
  /**
   * The answer's type, `| undefined` when the route may answer `204 No
   * Content` for an absent answer (`responseOptional`); absent when the route
   * answers 204 alone.
   */
  readonly responseType?: string;
  /** The exact numbers of its body and of its answer. */
  readonly textNumbers?: {
    readonly request?: TextNumbersModel;
    readonly response?: TextNumbersModel;
  };
}

/** What a service route's model needs of a Manifest's types: the TypeScript type of a schema. */
interface RouteTypes {
  type(schema: unknown, where: string): string;
}

/**
 * The model of one service route of `context`, its types read by `types`
 * and its exact numbers by `numbers`.
 */
export function serviceRouteModel(
  types: RouteTypes,
  numbers: Pick<TextNumbers, 'marks'>,
  context: Pick<ManifestContext, 'name'>,
  route: ServiceRouteManifest,
): ServiceRouteModel {
  const where = `${context.name} route ${route.name}`;
  const used = routeMethodParameters();
  return {
    name: route.name,
    method: route.route.method,
    path: route.route.path,
    ...(route.description ? { description: route.description } : {}),
    deprecated: route.deprecated === true,
    parameters: (route.parameters ?? []).map(parameter => ({
      name: parameter.name,
      identifier: routeParameterIdentifier(parameter, used),
      location: parameter.in === 'path' ? 'path' : 'query',
      type: types.type(parameter.schema, `${where} ${parameter.name}`),
      required: parameter.required,
    })),
    ...(route.request === undefined
      ? {}
      : { requestType: types.type(route.request, `${where} body`) }),
    ...(route.response === undefined
      ? {}
      : {
          responseType: answerType(
            types.type(route.response, `${where} answer`),
            route.responseOptional === true,
          ),
        }),
    ...routeNumbers(
      numbers.marks(route.request, `${where} body`),
      numbers.marks(route.response, `${where} answer`),
    ),
  };
}

/**
 * The type a route's method answers: its answer's, or `undefined` beside it
 * when the route may answer 204 for an absent one, which the client reads as
 * `undefined`. A generated type is never a function type, so a union with
 * `undefined` needs no parentheses around it.
 */
function answerType(type: string, optional: boolean): string {
  return optional ? `${type} | undefined` : type;
}

/** The `textNumbers` member of a route's model, when its body or its answer has exact numbers. */
function routeNumbers(
  request: TextNumbersModel | undefined,
  response: TextNumbersModel | undefined,
): Pick<ServiceRouteModel, 'textNumbers'> {
  if (request === undefined && response === undefined) return {};
  return {
    textNumbers: {
      ...(request === undefined ? {} : { request }),
      ...(response === undefined ? {} : { response }),
    },
  };
}
