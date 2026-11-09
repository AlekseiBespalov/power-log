type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
export declare class KitFailureText {
    private constructor();
    get message(): string;
    get nextStep(): string;
    get text(): string;
}
export declare namespace KitFailureText {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => KitFailureText;
    }
}
export declare class KitSourceText {
    private constructor();
    get label(): string;
    get tone(): string;
}
export declare namespace KitSourceText {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => KitSourceText;
    }
}
export declare class KitPermissionText {
    private constructor();
    get label(): string;
    get detail(): Nullable<string>;
}
export declare namespace KitPermissionText {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => KitPermissionText;
    }
}
export declare class KitStatusText {
    private constructor();
    get label(): string;
    get detail(): string;
}
export declare namespace KitStatusText {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => KitStatusText;
    }
}
export declare class KitDisplayUnit {
    private constructor();
    get symbol(): string;
    get decimals(): number;
    get factor(): number;
    get offset(): number;
}
export declare namespace KitDisplayUnit {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => KitDisplayUnit;
    }
}
export declare class KitAppearance {
    private constructor();
    get id(): string;
    get label(): string;
    get shortLabel(): string;
    get color(): string;
    get description(): Nullable<string>;
    get group(): string;
    get step(): boolean;
    get zero(): boolean;
    get selectable(): boolean;
    get semantics(): string;
    get allowsMean(): boolean;
    get origin(): string;
    get tailUs(): number;
}
export declare namespace KitAppearance {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => KitAppearance;
    }
}
export declare function textsFailure(code: string, detail: Nullable<string>, platform: string, sdkLevel: Nullable<number>): KitFailureText;
export declare function textsPhaseChip(phase: string): string;
export declare function textsHistoryPhase(phase: string, interrupted: boolean): string;
export declare function textsSource(kind: string, state: string, options: any, provider: Nullable<string>): Nullable<KitSourceText>;
export declare function textsHealth(health: any): string;
export declare function textsHealthReason(health: any): Nullable<string>;
export declare function textsHealthComponents(components: Array<any>, provider: Nullable<string>): Array<string>;
export declare function textsHistoryHealthLine(health: any, components: Array<any>): Nullable<string>;
export declare function textsPermission(need: string, action: string, provider: Nullable<string>, platform: string): Nullable<KitPermissionText>;
export declare function textsHealthSetup(options: any, request: any, provider: Nullable<string>): Nullable<string>;
export declare function textsDistanceSource(source: string, foregroundOnly: boolean): string;
export declare function textsControllerSpeedNote(controllerSpeed: Nullable<boolean>): Nullable<string>;
export declare function textsConnection(state: string, display: string): string;
export declare function textsControllerFault(faultCode: number): string;
export declare function textsProvider(provider: string): string;
export declare function textsRecordedOn(recordedBy: string, useWatch: boolean): string;
export declare function textsSetting(field: string): string;
export declare function textsChoice(field: string, value: string, platform: string): string;
export declare function textsViewName(view: string): string;
export declare function textsRange(range: string): string;
export declare function textsChartStatus(state: string): Nullable<KitStatusText>;
export declare function textsTraceStatus(state: string, events: number, stopReason: Nullable<string>): string;
export declare function textsStoragePersistence(state: string): string;
export declare function textsRideCount(count: number): string;
export declare function textsLapCount(laps: number): string;
export declare function unitsDisplay(metric: string, units: string, temperature: string): Nullable<KitDisplayUnit>;
export declare function formatMetric(metric: string, value: Nullable<number>, units: string, temperature: string): string;
export declare function formatExact(metric: string, value: number, units: string, temperature: string): string;
export declare function formatDifference(metric: string, a: Nullable<number>, b: Nullable<number>, units: string, temperature: string): string;
export declare function formatDuration(us: Nullable<number>): string;
export declare function formatElapsedPrecise(us: Nullable<number>): string;
export declare function formatDistance(distanceM: Nullable<number>, units: string): string;
export declare function formatDecimal(value: Nullable<number>, decimals: number): string;
export declare function formatIsoTimestamp(utcUs: number): string;
export declare function formatBikeName(model: Nullable<string>, advertisedName: Nullable<string>, bikeId: Nullable<string>): string;
export declare function metricAppearance(metric: string): Nullable<KitAppearance>;
export declare function metricAppearances(): Array<KitAppearance>;
export declare function metricCatalogShown(selected: Array<string>, available: Array<string>): Array<string>;
export declare function metricCatalogStored(metric: string): string;
export declare function rideDate(utcUs: number, nowUtcUs: number, uses24h: boolean, locale: string): string;
export declare function integer(value: number, locale: string): string;
export declare class ControllerIdentity {
    private constructor();
    get controllerModel(): string;
    get firmwareLabel(): string;
    get controllerProtocol(): string;
    get hasKnownSpeedUnit(): boolean;
    get capabilities(): ControllerCapabilities;
}
export declare namespace ControllerIdentity {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ControllerIdentity;
    }
}
export declare class TelemetryReading {
    private constructor();
    get identity(): Nullable<ControllerIdentity>;
    value(metric: string): Nullable<number>;
    get metrics(): Array<StoredMetric>;
}
export declare namespace TelemetryReading {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TelemetryReading;
    }
}
export declare class ControllerCapabilities {
    constructor(suppliedMetrics: Array<string>, hasKnownSpeedUnit: boolean);
    get hasKnownSpeedUnit(): boolean;
    get suppliedMetrics(): Array<string>;
    supplies(metric: string): boolean;
}
export declare namespace ControllerCapabilities {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ControllerCapabilities;
    }
}
export declare class StoredMetric {
    constructor(metric: string, value: number);
    get metric(): string;
    get value(): number;
}
export declare namespace StoredMetric {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => StoredMetric;
    }
}
export declare class ControllerProtocolApi {
    private constructor();
    requestFrame(request: string): Int8Array;
    feed(notification: Int8Array, identity: Nullable<ControllerIdentity>): ControllerDecodeResult;
    reset(): ControllerDecodeResult;
}
export declare namespace ControllerProtocolApi {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ControllerProtocolApi;
    }
}
export declare class ControllerDecodeResult {
    private constructor();
    get replies(): Array<ControllerResponse>;
    get discardedBytes(): string;
    get pendingBytes(): number;
}
export declare namespace ControllerDecodeResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ControllerDecodeResult;
    }
}
export declare class ControllerResponse {
    private constructor();
    get kind(): string;
    get identity(): Nullable<ControllerIdentity>;
    get reading(): Nullable<TelemetryReading>;
    get rejectionReason(): Nullable<string>;
}
export declare namespace ControllerResponse {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ControllerResponse;
    }
}
export declare class ControllerWireField {
    private constructor();
    get metric(): string;
    get bit(): number;
    get width(): number;
    get divisor(): number;
    get signed(): boolean;
}
export declare namespace ControllerWireField {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ControllerWireField;
    }
}
export declare function controllerSelectedMask(): number;
export declare function controllerWireFields(): Array<ControllerWireField>;
export declare function controllerCrc(bytes: Int8Array): number;
export declare function controllerProtocol(): ControllerProtocolApi;
export declare function startWebRuntime(archiveName: Nullable<string>, appVersion: string): WebSession;
export declare class WebSession {
    private constructor();
    call(op: string, request: string, reply: (p0: string) => void): void;
    next(stream: string, request: string, reply: (p0: string) => void): void;
    exportPage(request: string, reply: (p0: any) => void): void;
}
export declare namespace WebSession {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => WebSession;
    }
}
export {};
