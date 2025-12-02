/**
 * - key: example.ApiVersion
 * - schema: 
 * ```json
 * {
 *   "type": "string",
 *   "enum": [
 *     "V2",
 *     "V3"
 *   ]
 * }
 * ```
 */
export enum ApiVersion {
    V2 = `V2`,
    V3 = `V3`
}

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
 * - key: example.SecurityContext
 * - schema: 
 * ```json
 * {
 *   "type": "object"
 * }
 * ```
 */
export type SecurityContext = Record<string, any>;
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
export type StringLinkMap = Record<string, Link>;
/**
 * - key: example.StringObjectMap
 * - schema: 
 * ```json
 * {
 *   "type": "object"
 * }
 * ```
 */
export type StringObjectMap = Record<string, any>;

/**
 * - key: example.WebServerNamespace
 * - schema: 
 * ```json
 * {
 *   "type": "object",
 *   "properties": {
 *     "value": {
 *       "type": "string"
 *     }
 *   }
 * }
 * ```
 */
export interface WebServerNamespace {
    value: string;
}
