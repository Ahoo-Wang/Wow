/**
 * - key: example.Link
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "href": {
 *       "type": "string"
 *     },
 *     "templated": {
 *       "type": "boolean"
 *     }
 *   }
 * }
 * ```
 */
export interface Link {
    href: string;
    templated: boolean;
}

/**
 * - key: example.StringLinkMap
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "additionalProperties": {
 *     "$ref": "#/components/schemas/example.Link"
 *   }
 * }
 * ```
 */
export type StringLinkMap = globalThis.Record<string, Link>;
/**
 * - key: example.JsonNode
 * - schema: 
 * ```json
 * {}
 * ```
 */
export type JsonNode = any;
/**
 * - key: example.StringObjectMap
 * - schema: 
 * ```json
 * {
 *   "type": "object"
 * }
 * ```
 */
export type StringObjectMap = globalThis.Record<string, any>;
/**
 * - key: example.StringStringListMap
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "additionalProperties": {
 *     "type": "array",
 *     "items": {
 *       "type": "string"
 *     }
 *   }
 * }
 * ```
 */
export type StringStringListMap = globalThis.Record<string, string[]>;

/**
 * - key: example.TimeUnit
 * - schema: 
 * ```json
 * {
 *   "type": "string",
 *   "enum": [
 *     "NANOSECONDS",
 *     "MICROSECONDS",
 *     "MILLISECONDS",
 *     "SECONDS",
 *     "MINUTES",
 *     "HOURS",
 *     "DAYS"
 *   ]
 * }
 * ```
 */
export enum TimeUnit {
    NANOSECONDS = 'NANOSECONDS',
    MICROSECONDS = 'MICROSECONDS',
    MILLISECONDS = 'MILLISECONDS',
    SECONDS = 'SECONDS',
    MINUTES = 'MINUTES',
    HOURS = 'HOURS',
    DAYS = 'DAYS'
}
