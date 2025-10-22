/** - key: compensation.ApiVersion */
export enum ApiVersion {
    V2 = 'V2',
    V3 = 'V3'
}

/** - key: compensation.Link */
export interface Link {
    href: string;
    templated: boolean;
}

/** - key: compensation.SecurityContext */
export type SecurityContext = Record<string, any>;
/** - key: compensation.StringLinkMap */
export type StringLinkMap = Record<string, Link>;
/** - key: compensation.StringObjectMap */
export type StringObjectMap = Record<string, any>;

/** - key: compensation.WebServerNamespace */
export interface WebServerNamespace {
    value: string;
}
