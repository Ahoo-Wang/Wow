---
title: 'Business errors and document utilities'
description: 'Business errors and document utilities — @ahoo-wang/wow-client'
---

# Business errors and document utilities

A successful HTTP response can carry a failed Wow business result. Inspect its errorCode before treating a write as completed. These helpers classify wire data; they do not throw a domain exception or choose a retry policy for you.

| Contract                                           | Meaning, default and boundary                                                                                                                             |
| -------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ErrorInfo`                                        | Required errorCode/errorMsg; optional bindingErrors array. Each `BindingError` has name/msg for a field-level validation issue.                           |
| `ErrorCodes.isSucceeded(code)`                     | Strict equality with `SUCCEEDED`, the string `'Ok'`; success message defaults to the constant empty string. It is not an HTTP status check.               |
| `ErrorCodes.isError(code)`                         | Negation of isSucceeded; an unknown code is an error. Constants below retain exact server identifiers, including duplicate request and version conflicts. |
| `RecoverableType`                                  | RECOVERABLE, UNKNOWN or UNRECOVERABLE metadata; even RECOVERABLE does not prove a repeated command is idempotent.                                         |
| `DynamicDocument` / `DynamicDocumentArray`         | `Record<string, any>` / its array. Use only when the response schema is intentionally unknown; no runtime validation.                                     |
| `getPropertyValue<T>(object, path, defaultValue?)` | Dotted string or segment array; absent/null final value returns defaultValue (undefined if omitted). Empty path returns the object itself.                |

Dotted empty segments are removed, whereas segment arrays preserve them. Numeric segments index arrays; an invalid array index or missing/null intermediate returns the default. Ordinary object access includes inherited properties, and a throwing getter is not caught. Do not treat this helper as a safe authorization filter for untrusted property paths. It allocates no resources needing cleanup.

```ts
import { ErrorCodes, getPropertyValue } from '@ahoo-wang/wow-client';
console.assert(ErrorCodes.isSucceeded('Ok'));
console.assert(ErrorCodes.isError('DuplicateRequestId'));
console.assert(
  getPropertyValue({ rows: [{ name: 'Ada' }] }, 'rows.0.name') === 'Ada',
);
console.assert(
  getPropertyValue({ name: null }, 'name', 'unknown') === 'unknown',
);
```

Read [command results](./commands) for the surrounding execution stage and [failure boundaries](https://fetcher.ahoo.me/architecture/failure-model) for transport/JSON errors.

## Exact contracts

### DynamicDocument {#api-DynamicDocument}

```ts
export type DynamicDocument = Record<string, any>;
```

[typescript/wow-client/src/query/types.ts:14](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/types.ts#L14)

### DynamicDocumentArray {#api-DynamicDocumentArray}

```ts
export type DynamicDocumentArray = DynamicDocument[];
```

[typescript/wow-client/src/query/types.ts:16](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/types.ts#L16)

### RecoverableType {#api-RecoverableType}

```ts
export enum RecoverableType {
  RECOVERABLE = 'RECOVERABLE',
  UNKNOWN = 'UNKNOWN',
  UNRECOVERABLE = 'UNRECOVERABLE',
}
```

[typescript/wow-client/src/types/error.ts:22](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L22)

### BindingError {#api-BindingError}

```ts
export interface BindingError {
  name: string;
  msg: string;
}
```

[typescript/wow-client/src/types/error.ts:55](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L55)

### ErrorInfo {#api-ErrorInfo}

```ts
export interface ErrorInfo {
  errorCode: string;
  errorMsg: string;
  bindingErrors?: BindingError[];
}
```

[typescript/wow-client/src/types/error.ts:66](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L66)

### ErrorCodes {#api-ErrorCodes}

::: details Expand all fields and members

```ts
export class ErrorCodes {
  static readonly SUCCEEDED = 'Ok';
  static readonly SUCCEEDED_MESSAGE = '';
  static readonly NOT_FOUND = 'NotFound';
  static readonly NOT_FOUND_MESSAGE = 'Not found resource!';
  static readonly BAD_REQUEST = 'BadRequest';
  static readonly ILLEGAL_ARGUMENT = 'IllegalArgument';
  static readonly ILLEGAL_STATE = 'IllegalState';
  static readonly REQUEST_TIMEOUT = 'RequestTimeout';
  static readonly TOO_MANY_REQUESTS = 'TooManyRequests';
  static readonly DUPLICATE_REQUEST_ID = 'DuplicateRequestId';
  static readonly COMMAND_VALIDATION = 'CommandValidation';
  static readonly REWRITE_NO_COMMAND = 'RewriteNoCommand';
  static readonly EVENT_VERSION_CONFLICT = 'EventVersionConflict';
  static readonly DUPLICATE_AGGREGATE_ID = 'DuplicateAggregateId';
  static readonly COMMAND_EXPECT_VERSION_CONFLICT =
    'CommandExpectVersionConflict';
  static readonly SOURCING_VERSION_CONFLICT = 'SourcingVersionConflict';
  static readonly ILLEGAL_ACCESS_DELETED_AGGREGATE =
    'IllegalAccessDeletedAggregate';
  static readonly ILLEGAL_ACCESS_OWNER_AGGREGATE =
    'IllegalAccessOwnerAggregate';
  static readonly ILLEGAL_ACCESS_SPACE_AGGREGATE =
    'IllegalAccessSpaceAggregate';
  static readonly INTERNAL_SERVER_ERROR = 'InternalServerError';
  static isSucceeded(errorCode: string): boolean;
  static isError(errorCode: string): boolean;
}
```

:::

[typescript/wow-client/src/types/error.ts:85](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/types/error.ts#L85)

### getPropertyValue {#api-getPropertyValue}

```ts
export function getPropertyValue<T = any>(
  object: any,
  propertyName: string | string[],
  defaultValue?: T,
): T | undefined;
```

[typescript/wow-client/src/getPropertyValue.ts:50](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/getPropertyValue.ts#L50)

[Complete symbol index](./symbols)
