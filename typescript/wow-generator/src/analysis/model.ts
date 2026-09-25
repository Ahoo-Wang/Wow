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

import type { HTTPMethod, Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import type { ModelInfo } from '../naming/modelInfo';

/**
 * What a document generates, decided before anything is written: every
 * name, file, parameter and kind of body or response, as plain data. It
 * holds no ts-morph node and no operation of the document; the emitters read
 * it, and only they resolve schemas to types, in this order, so the imports
 * of each module come out the way they always have.
 *
 * Files are relative to the output directory.
 */
export interface GenerationModel {
  /** The bounded contexts, each a file declaring its alias constant; sorted. */
  readonly contexts: readonly BoundedContextModel[];
  /** The models, in the order of the document's components. */
  readonly models: readonly ModelDeclaration[];
  /** The aggregates with a command and a query client, by bounded context. */
  readonly aggregates: readonly AggregateModel[];
  /** The API clients, one per tag, sorted by tag name. */
  readonly apiClients: readonly ApiClientModel[];
}

/** What a generation decided, and the warnings it gave on the way. */
export interface Analysis {
  readonly model: GenerationModel;
  /** One line each, in the order they arose. */
  readonly warnings: readonly string[];
}

/** A bounded context's alias constant: `export const SHOP_BOUNDED_CONTEXT_ALIAS = 'shop';`. */
export interface BoundedContextModel {
  readonly alias: string;
  readonly constantName: string;
  readonly file: string;
}

/** The constant a module imports to name its bounded context. */
export interface ContextReference {
  readonly alias: string;
  readonly constantName: string;
}

/** A model: one declaration (and its companions) of a component schema. */
export interface ModelDeclaration {
  /** The component key. */
  readonly key: string;
  /** Its name, and the package path of its file. */
  readonly info: ModelInfo;
  readonly file: string;
  /** The schema as the document has it; the types read this one. */
  readonly schema: Schema | Reference;
  /**
   * The schema its doc comment reads: with the title and description the
   * Wow metadata lends it over it, when it lends any.
   */
  readonly docSchema: Schema | Reference;
  /**
   * A command or event body that declares nothing - a Kotlin `data object` -
   * which generates `Record<string, never>` rather than any object.
   */
  readonly emptyMessageBody: boolean;
}

/** An aggregate: its command client and its query client. */
export interface AggregateModel {
  readonly contextAlias: string;
  readonly aggregateName: string;
  readonly context: ContextReference;
  readonly commandClient: CommandClientModel;
  readonly queryClient: QueryClientModel;
}

/** The command client of an aggregate, and its streaming twin. */
export interface CommandClientModel {
  readonly file: string;
  /** The enum of the command routes: `CartCommandEndpointPaths`. */
  readonly endpointPathsName: string;
  readonly className: string;
  readonly streamClassName: string;
  readonly commands: readonly CommandModel[];
}

/** A command of an aggregate. */
export interface CommandModel {
  /** The command's route, and the member of the route enum that holds it. */
  readonly path: string;
  readonly endpointMember: string;
  readonly httpMethod: HTTPMethod;
  readonly methodName: string;
  /**
   * The alias of the command's body type: `AddCartItemCommand`. None when
   * the body's name already ends in `Command`: its methods then take
   * `CommandBody<MountedCommand>` itself, so no name repeats the suffix.
   */
  readonly typeName?: string;
  /** The body's model; one of Wow's own lives in wow-client, with its type. */
  readonly body: ModelInfo;
  /** The component key of the body's schema. */
  readonly bodyKey: string;
  /** The body's properties a caller may leave out. */
  readonly optionalFields: readonly string[];
  /** Whether a request may leave out the body: it has no properties. */
  readonly requestOptional: boolean;
  /** The path parameters the caller passes. */
  readonly pathParameters: readonly PathParameterModel[];
  readonly docs: readonly (string | undefined)[];
}

/** A path parameter of a command method, in the order the route holds them. */
export interface PathParameterModel {
  /** The method's parameter: an identifier. */
  readonly name: string;
  /** The name in the route. */
  readonly pathName: string;
  readonly type: string;
}

/** The query client factory of an aggregate. */
export interface QueryClientModel {
  readonly file: string;
  /** The aggregate's route segment: its resource name. */
  readonly resourceName: string;
  /** A `ResourceAttributionPathSpec` member, as code. */
  readonly resourceAttribution: string;
  readonly state: ModelInfo;
  readonly fields: ModelInfo;
  /** The enum of the event titles: `CartDomainEventTypeMapTitle`. */
  readonly eventTitlesName: string;
  /** The union of the event types: `CartDomainEventType`. */
  readonly eventTypeName: string;
  readonly events: readonly EventModel[];
  readonly factoryName: string;
}

/** A domain event of an aggregate. */
export interface EventModel {
  /** Its member of the event title enum. */
  readonly memberName: string;
  readonly title: string;
  readonly body: ModelInfo;
}

/** The API client of a tag. */
export interface ApiClientModel {
  readonly tagName: string;
  readonly className: string;
  readonly file: string;
  /** The tag's description, the class's doc comment. */
  readonly description?: string;
  /** The bounded context whose alias is the base path, in a Wow document. */
  readonly basePath?: ContextReference;
  /** In the order of the document's operations, by operation id. */
  readonly methods: readonly ApiMethodModel[];
}

/** A method of an API client: one operation. */
export interface ApiMethodModel {
  readonly name: string;
  readonly httpMethod: HTTPMethod;
  readonly path: string;
  /**
   * The path, query and header parameters, in the order the types resolve:
   * path, then query, then header, each in document order. The method takes
   * the required ones first, then the body if required, then the optional
   * ones, then the body if optional.
   */
  readonly parameters: readonly ParameterModel[];
  readonly body?: BodyModel;
  readonly returns: ReturnModel;
  readonly docs: readonly (string | undefined)[];
}

/** A path, query or header parameter of an API method. */
export interface ParameterModel {
  /** The method's parameter: an identifier. */
  readonly name: string;
  readonly location: 'path' | 'query' | 'header';
  /** The name the request uses. */
  readonly parameterName: string;
  /** Its schema; a parameter without one is a string. */
  readonly schema?: Schema | Reference;
  readonly required: boolean;
}

/** The request body of an API method. */
export interface BodyModel {
  /** The method's parameter: `body`, unless a parameter took the name. */
  readonly name: string;
  readonly required: boolean;
  readonly content: BodyContent;
}

/**
 * What a body holds:
 *
 * - `json`: the schema's type, with the properties the schema does not
 *   require optional (`PartialBy<Item, 'id'>`);
 * - `formData`: `FormData`; `urlEncoded`: `URLSearchParams`;
 * - `text`: a string; `binary`: whatever a fetch request accepts.
 */
export type BodyContent =
  | {
      readonly kind: 'json';
      readonly schema: Schema | Reference;
      readonly optionalFields: readonly string[];
    }
  | { readonly kind: 'formData' | 'urlEncoded' | 'text' | 'binary' };

/**
 * What an API method returns, read from its success response:
 *
 * - `json`: the schema's type; a response of any media type (`wildcard`)
 *   whose schema resolves to `string` is text;
 * - `eventStream`: a JSON server-sent event stream of the items' type, the
 *   `data` of a `ServerSentEvent` model, or of anything without one;
 * - `text`: a string; `response`: the raw `Response`.
 */
export type ReturnModel =
  | {
      readonly kind: 'json';
      readonly schema: Schema | Reference;
      readonly wildcard: boolean;
    }
  | {
      readonly kind: 'eventStream';
      readonly items?: Reference;
      readonly serverSentEvent: boolean;
    }
  | { readonly kind: 'text' | 'response' };
