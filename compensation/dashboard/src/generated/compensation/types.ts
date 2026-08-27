/**
 * - key: compensation.Link
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
 * - key: compensation.StringLinkMap
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "additionalProperties": {
 *     "$ref": "#/components/schemas/compensation.Link"
 *   }
 * }
 * ```
 */
export type StringLinkMap = Record<string, Link>;
/**
 * - key: compensation.JsonNode
 * - schema: 
 * ```json
 * {}
 * ```
 */
export type JsonNode = any;
/**
 * - key: compensation.StringObjectMap
 * - schema: 
 * ```json
 * {
 *   "type": "object"
 * }
 * ```
 */
export type StringObjectMap = Record<string, any>;
/**
 * - key: compensation.StringStringListMap
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
export type StringStringListMap = Record<string, string[]>;

/**
 * - key: compensation.TimeUnit
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
    NANOSECONDS = `NANOSECONDS`,
    MICROSECONDS = `MICROSECONDS`,
    MILLISECONDS = `MILLISECONDS`,
    SECONDS = `SECONDS`,
    MINUTES = `MINUTES`,
    HOURS = `HOURS`,
    DAYS = `DAYS`
}
