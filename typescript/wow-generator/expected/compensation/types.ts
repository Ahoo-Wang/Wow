export enum ApiVersion {
    V2 = 'V2',
    V3 = 'V3'
}

export interface Link {
    href: string;
    templated: boolean;
}

export interface SecurityContext {
}

export interface StringLinkMap {
}

export interface StringObjectMap {
}

export interface WebServerNamespace {
    value: string;
}
