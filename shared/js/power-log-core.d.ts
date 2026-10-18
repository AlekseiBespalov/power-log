type Nullable<T> = T | null | undefined
declare function KtSingleton<T>(): T & (abstract new() => any);
export declare interface KtList<out E> /* extends Collection<E> */ {
    asJsReadonlyArrayView(): ReadonlyArray<E>;
    readonly __doNotUseOrImplementIt: {
        readonly "kotlin.collections.KtList": unique symbol;
    };
}
export declare namespace KtList {
    function fromJsArray<E>(array: ReadonlyArray<E>): KtList<E>;
}
export declare abstract class ExportNumericKey {
    private constructor();
    static get elapsedSeconds(): ExportNumericKey & {
        get name(): "elapsedSeconds";
        get ordinal(): 0;
    };
    static get producerSequence(): ExportNumericKey & {
        get name(): "producerSequence";
        get ordinal(): 1;
    };
    static get active(): ExportNumericKey & {
        get name(): "active";
        get ordinal(): 2;
    };
    static get interval(): ExportNumericKey & {
        get name(): "interval";
        get ordinal(): 3;
    };
    static get humanPowerW(): ExportNumericKey & {
        get name(): "humanPowerW";
        get ordinal(): 4;
    };
    static get cadenceRpm(): ExportNumericKey & {
        get name(): "cadenceRpm";
        get ordinal(): 5;
    };
    static get motorInputPowerW(): ExportNumericKey & {
        get name(): "motorInputPowerW";
        get ordinal(): 6;
    };
    static get batteryVoltageV(): ExportNumericKey & {
        get name(): "batteryVoltageV";
        get ordinal(): 7;
    };
    static get batteryCurrentA(): ExportNumericKey & {
        get name(): "batteryCurrentA";
        get ordinal(): 8;
    };
    static get motorCurrentA(): ExportNumericKey & {
        get name(): "motorCurrentA";
        get ordinal(): 9;
    };
    static get motorRpm(): ExportNumericKey & {
        get name(): "motorRpm";
        get ordinal(): 10;
    };
    static get pedalTorqueNm(): ExportNumericKey & {
        get name(): "pedalTorqueNm";
        get ordinal(): 11;
    };
    static get controllerTempC(): ExportNumericKey & {
        get name(): "controllerTempC";
        get ordinal(): 12;
    };
    static get motorTempC(): ExportNumericKey & {
        get name(): "motorTempC";
        get ordinal(): 13;
    };
    static get consumedAh(): ExportNumericKey & {
        get name(): "consumedAh";
        get ordinal(): 14;
    };
    static get consumedWh(): ExportNumericKey & {
        get name(): "consumedWh";
        get ordinal(): 15;
    };
    static get throttleVoltageV(): ExportNumericKey & {
        get name(): "throttleVoltageV";
        get ordinal(): 16;
    };
    static get faultCode(): ExportNumericKey & {
        get name(): "faultCode";
        get ordinal(): 17;
    };
    static get assistLevel(): ExportNumericKey & {
        get name(): "assistLevel";
        get ordinal(): 18;
    };
    static get controllerSpeedMps(): ExportNumericKey & {
        get name(): "controllerSpeedMps";
        get ordinal(): 19;
    };
    static get raceMode(): ExportNumericKey & {
        get name(): "raceMode";
        get ordinal(): 20;
    };
    static get speedRaw(): ExportNumericKey & {
        get name(): "speedRaw";
        get ordinal(): 21;
    };
    static get latitude(): ExportNumericKey & {
        get name(): "latitude";
        get ordinal(): 22;
    };
    static get longitude(): ExportNumericKey & {
        get name(): "longitude";
        get ordinal(): 23;
    };
    static get altitudeMeters(): ExportNumericKey & {
        get name(): "altitudeMeters";
        get ordinal(): 24;
    };
    static get verticalAccuracyM(): ExportNumericKey & {
        get name(): "verticalAccuracyM";
        get ordinal(): 25;
    };
    static get originalAltitudeMeters(): ExportNumericKey & {
        get name(): "originalAltitudeMeters";
        get ordinal(): 26;
    };
    static get originalVerticalAccuracyM(): ExportNumericKey & {
        get name(): "originalVerticalAccuracyM";
        get ordinal(): 27;
    };
    static get horizontalAccuracyM(): ExportNumericKey & {
        get name(): "horizontalAccuracyM";
        get ordinal(): 28;
    };
    static get speedMps(): ExportNumericKey & {
        get name(): "speedMps";
        get ordinal(): 29;
    };
    static get speedAccuracyMps(): ExportNumericKey & {
        get name(): "speedAccuracyMps";
        get ordinal(): 30;
    };
    static get courseDegrees(): ExportNumericKey & {
        get name(): "courseDegrees";
        get ordinal(): 31;
    };
    static get courseAccuracyDegrees(): ExportNumericKey & {
        get name(): "courseAccuracyDegrees";
        get ordinal(): 32;
    };
    static get distanceBarrier(): ExportNumericKey & {
        get name(): "distanceBarrier";
        get ordinal(): 33;
    };
    static get heartRateBpm(): ExportNumericKey & {
        get name(): "heartRateBpm";
        get ordinal(): 34;
    };
    static get activeEnergyKcal(): ExportNumericKey & {
        get name(): "activeEnergyKcal";
        get ordinal(): 35;
    };
    static get basalEnergyKcal(): ExportNumericKey & {
        get name(): "basalEnergyKcal";
        get ordinal(): 36;
    };
    static get distanceMeters(): ExportNumericKey & {
        get name(): "distanceMeters";
        get ordinal(): 37;
    };
    static get riderPowerW(): ExportNumericKey & {
        get name(): "riderPowerW";
        get ordinal(): 38;
    };
    static get value(): ExportNumericKey & {
        get name(): "value";
        get ordinal(): 39;
    };
    static get sampleCount(): ExportNumericKey & {
        get name(): "sampleCount";
        get ordinal(): 40;
    };
    static get timerSeconds(): ExportNumericKey & {
        get name(): "timerSeconds";
        get ordinal(): 41;
    };
    static get interrupted(): ExportNumericKey & {
        get name(): "interrupted";
        get ordinal(): 42;
    };
    static get controllerSequence(): ExportNumericKey & {
        get name(): "controllerSequence";
        get ordinal(): 43;
    };
    static get meters(): ExportNumericKey & {
        get name(): "meters";
        get ordinal(): 44;
    };
    static get segment(): ExportNumericKey & {
        get name(): "segment";
        get ordinal(): 45;
    };
    static get startSpeed(): ExportNumericKey & {
        get name(): "startSpeed";
        get ordinal(): 46;
    };
    static get endSpeed(): ExportNumericKey & {
        get name(): "endSpeed";
        get ordinal(): 47;
    };
    static values(): [typeof ExportNumericKey.elapsedSeconds, typeof ExportNumericKey.producerSequence, typeof ExportNumericKey.active, typeof ExportNumericKey.interval, typeof ExportNumericKey.humanPowerW, typeof ExportNumericKey.cadenceRpm, typeof ExportNumericKey.motorInputPowerW, typeof ExportNumericKey.batteryVoltageV, typeof ExportNumericKey.batteryCurrentA, typeof ExportNumericKey.motorCurrentA, typeof ExportNumericKey.motorRpm, typeof ExportNumericKey.pedalTorqueNm, typeof ExportNumericKey.controllerTempC, typeof ExportNumericKey.motorTempC, typeof ExportNumericKey.consumedAh, typeof ExportNumericKey.consumedWh, typeof ExportNumericKey.throttleVoltageV, typeof ExportNumericKey.faultCode, typeof ExportNumericKey.assistLevel, typeof ExportNumericKey.controllerSpeedMps, typeof ExportNumericKey.raceMode, typeof ExportNumericKey.speedRaw, typeof ExportNumericKey.latitude, typeof ExportNumericKey.longitude, typeof ExportNumericKey.altitudeMeters, typeof ExportNumericKey.verticalAccuracyM, typeof ExportNumericKey.originalAltitudeMeters, typeof ExportNumericKey.originalVerticalAccuracyM, typeof ExportNumericKey.horizontalAccuracyM, typeof ExportNumericKey.speedMps, typeof ExportNumericKey.speedAccuracyMps, typeof ExportNumericKey.courseDegrees, typeof ExportNumericKey.courseAccuracyDegrees, typeof ExportNumericKey.distanceBarrier, typeof ExportNumericKey.heartRateBpm, typeof ExportNumericKey.activeEnergyKcal, typeof ExportNumericKey.basalEnergyKcal, typeof ExportNumericKey.distanceMeters, typeof ExportNumericKey.riderPowerW, typeof ExportNumericKey.value, typeof ExportNumericKey.sampleCount, typeof ExportNumericKey.timerSeconds, typeof ExportNumericKey.interrupted, typeof ExportNumericKey.controllerSequence, typeof ExportNumericKey.meters, typeof ExportNumericKey.segment, typeof ExportNumericKey.startSpeed, typeof ExportNumericKey.endSpeed];
    static valueOf(value: string): ExportNumericKey;
    get name(): "elapsedSeconds" | "producerSequence" | "active" | "interval" | "humanPowerW" | "cadenceRpm" | "motorInputPowerW" | "batteryVoltageV" | "batteryCurrentA" | "motorCurrentA" | "motorRpm" | "pedalTorqueNm" | "controllerTempC" | "motorTempC" | "consumedAh" | "consumedWh" | "throttleVoltageV" | "faultCode" | "assistLevel" | "controllerSpeedMps" | "raceMode" | "speedRaw" | "latitude" | "longitude" | "altitudeMeters" | "verticalAccuracyM" | "originalAltitudeMeters" | "originalVerticalAccuracyM" | "horizontalAccuracyM" | "speedMps" | "speedAccuracyMps" | "courseDegrees" | "courseAccuracyDegrees" | "distanceBarrier" | "heartRateBpm" | "activeEnergyKcal" | "basalEnergyKcal" | "distanceMeters" | "riderPowerW" | "value" | "sampleCount" | "timerSeconds" | "interrupted" | "controllerSequence" | "meters" | "segment" | "startSpeed" | "endSpeed";
    get ordinal(): 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 | 18 | 19 | 20 | 21 | 22 | 23 | 24 | 25 | 26 | 27 | 28 | 29 | 30 | 31 | 32 | 33 | 34 | 35 | 36 | 37 | 38 | 39 | 40 | 41 | 42 | 43 | 44 | 45 | 46 | 47;
}
export declare namespace ExportNumericKey {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ExportNumericKey;
    }
}
export declare abstract class ExportStringKey {
    private constructor();
    static get timestamp(): ExportStringKey & {
        get name(): "timestamp";
        get ordinal(): 0;
    };
    static get clockEpoch(): ExportStringKey & {
        get name(): "clockEpoch";
        get ordinal(): 1;
    };
    static get connection(): ExportStringKey & {
        get name(): "connection";
        get ordinal(): 2;
    };
    static get producer(): ExportStringKey & {
        get name(): "producer";
        get ordinal(): 3;
    };
    static get originalAltitudeDatum(): ExportStringKey & {
        get name(): "originalAltitudeDatum";
        get ordinal(): 4;
    };
    static get identifier(): ExportStringKey & {
        get name(): "identifier";
        get ordinal(): 5;
    };
    static get unit(): ExportStringKey & {
        get name(): "unit";
        get ordinal(): 6;
    };
    static get representation(): ExportStringKey & {
        get name(): "representation";
        get ordinal(): 7;
    };
    static get sampleStart(): ExportStringKey & {
        get name(): "sampleStart";
        get ordinal(): 8;
    };
    static get provider(): ExportStringKey & {
        get name(): "provider";
        get ordinal(): 9;
    };
    static get providerParentId(): ExportStringKey & {
        get name(): "providerParentId";
        get ordinal(): 10;
    };
    static get dataOrigin(): ExportStringKey & {
        get name(): "dataOrigin";
        get ordinal(): 11;
    };
    static get originalEventId(): ExportStringKey & {
        get name(): "originalEventId";
        get ordinal(): 12;
    };
    static get originalEndTimestamp(): ExportStringKey & {
        get name(): "originalEndTimestamp";
        get ordinal(): 13;
    };
    static get memberIndex(): ExportStringKey & {
        get name(): "memberIndex";
        get ordinal(): 14;
    };
    static get logicalKey(): ExportStringKey & {
        get name(): "logicalKey";
        get ordinal(): 15;
    };
    static get targetEventId(): ExportStringKey & {
        get name(): "targetEventId";
        get ordinal(): 16;
    };
    static get originRevision(): ExportStringKey & {
        get name(): "originRevision";
        get ordinal(): 17;
    };
    static get valueBits(): ExportStringKey & {
        get name(): "valueBits";
        get ordinal(): 18;
    };
    static get evidence(): ExportStringKey & {
        get name(): "evidence";
        get ordinal(): 19;
    };
    static get connectionEpoch(): ExportStringKey & {
        get name(): "connectionEpoch";
        get ordinal(): 20;
    };
    static get channel(): ExportStringKey & {
        get name(): "channel";
        get ordinal(): 21;
    };
    static get startUs(): ExportStringKey & {
        get name(): "startUs";
        get ordinal(): 22;
    };
    static get endUs(): ExportStringKey & {
        get name(): "endUs";
        get ordinal(): 23;
    };
    static get observationRevision(): ExportStringKey & {
        get name(): "observationRevision";
        get ordinal(): 24;
    };
    static get originalStartTimestamp(): ExportStringKey & {
        get name(): "originalStartTimestamp";
        get ordinal(): 25;
    };
    static get action(): ExportStringKey & {
        get name(): "action";
        get ordinal(): 26;
    };
    static values(): [typeof ExportStringKey.timestamp, typeof ExportStringKey.clockEpoch, typeof ExportStringKey.connection, typeof ExportStringKey.producer, typeof ExportStringKey.originalAltitudeDatum, typeof ExportStringKey.identifier, typeof ExportStringKey.unit, typeof ExportStringKey.representation, typeof ExportStringKey.sampleStart, typeof ExportStringKey.provider, typeof ExportStringKey.providerParentId, typeof ExportStringKey.dataOrigin, typeof ExportStringKey.originalEventId, typeof ExportStringKey.originalEndTimestamp, typeof ExportStringKey.memberIndex, typeof ExportStringKey.logicalKey, typeof ExportStringKey.targetEventId, typeof ExportStringKey.originRevision, typeof ExportStringKey.valueBits, typeof ExportStringKey.evidence, typeof ExportStringKey.connectionEpoch, typeof ExportStringKey.channel, typeof ExportStringKey.startUs, typeof ExportStringKey.endUs, typeof ExportStringKey.observationRevision, typeof ExportStringKey.originalStartTimestamp, typeof ExportStringKey.action];
    static valueOf(value: string): ExportStringKey;
    get name(): "timestamp" | "clockEpoch" | "connection" | "producer" | "originalAltitudeDatum" | "identifier" | "unit" | "representation" | "sampleStart" | "provider" | "providerParentId" | "dataOrigin" | "originalEventId" | "originalEndTimestamp" | "memberIndex" | "logicalKey" | "targetEventId" | "originRevision" | "valueBits" | "evidence" | "connectionEpoch" | "channel" | "startUs" | "endUs" | "observationRevision" | "originalStartTimestamp" | "action";
    get ordinal(): 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 | 18 | 19 | 20 | 21 | 22 | 23 | 24 | 25 | 26;
}
export declare namespace ExportStringKey {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ExportStringKey;
    }
}
export declare function analyticsTimeUs(seconds: number): string;
export declare function analyticsQuantize(metric: string, kind: string, value: string): Nullable<string>;
export declare function analyticsDivide(numerator: string, denominator: string): number;
export declare function analyticsGPSDistance(latitude0: number, longitude0: number, latitude1: number, longitude1: number): number;
export declare function analyticsValid(metric: string, value: Nullable<number>, accuracy: Nullable<number>): boolean;
export declare class AnalyticsTimeline {
    constructor(endUs: string, lifecycle: Array<AnalyticsItem>);
    get endUs(): string;
    get startsUs(): Array<string>;
    get endsUs(): Array<string>;
    get lapEndsUs(): Array<string>;
    get timerUs(): string;
    get activeThroughEnd(): boolean;
    activeInterval(item: AnalyticsItem): Nullable<number>;
    interruptionIndex(item: AnalyticsItem): number;
    assign(elapsed: Float64Array, kind: string, producers: Nullable<Array<Nullable<string>>>, sequences: Nullable<Float64Array>, epochs: Nullable<Array<Nullable<string>>>): AnalyticsAssignment;
    rangeTimerUs(startUs: string, endUs: string): string;
}
export declare namespace AnalyticsTimeline {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsTimeline;
    }
}
export declare class AnalyticsAssignment {
    private constructor();
    get interval(): Int32Array;
    get interruption(): Int32Array;
}
export declare namespace AnalyticsAssignment {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsAssignment;
    }
}
export declare class AnalyticsFitStatistics {
    constructor(endUs: string);
    telemetry(elapsed: Float64Array, watts: Float64Array, rpm: Float64Array, battery: Float64Array, intervals: Int32Array, runs: Int32Array): void;
    heartRate(time: number, value: number, interval: number, interruption: number, clock: Nullable<string>, connection: Nullable<string>): void;
    gpsSpeed(time: number, value: number, interval: number): void;
    maximumSpeed(): Nullable<number>;
    powerResult(): AnalyticsStatistics;
    cadenceResult(): AnalyticsStatistics;
    motorResult(): AnalyticsStatistics;
    heartRateResult(): AnalyticsStatistics;
}
export declare namespace AnalyticsFitStatistics {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsFitStatistics;
    }
}
export declare class AnalyticsOriginal {
    private constructor();
    get observationId(): string;
    get eventId(): string;
    get timestamp(): string;
    get elapsedSeconds(): number;
    get originalElapsedSeconds(): Nullable<number>;
    get value(): number;
    get exactValue(): Nullable<string>;
}
export declare namespace AnalyticsOriginal {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsOriginal;
    }
}
export declare class AnalyticsStatistics {
    private constructor();
    get count(): string;
    get min(): Nullable<AnalyticsOriginal>;
    get max(): Nullable<AnalyticsOriginal>;
    get sampleMean(): Nullable<number>;
    get coveredSeconds(): Nullable<number>;
    get integral(): Nullable<number>;
    get average(): Nullable<number>;
    get coveredUs(): string;
    get integralQUs(): Nullable<string>;
    get sumQ(): Nullable<string>;
}
export declare namespace AnalyticsStatistics {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsStatistics;
    }
}
export declare class AnalyticsMetricReducer {
    constructor(metric: string, startUs: string, endUs: string);
    append(timeUs: string, observationId: string, eventId: string, timestamp: string, elapsedSeconds: number, scalarKind: string, value: Nullable<string>, activeInterval: Nullable<number>, clockEpoch: Nullable<string>, connectionEpoch: Nullable<string>, interruptionIndex: number, selected: boolean, accuracy: Nullable<number>): void;
    result(): Nullable<AnalyticsStatistics>;
    outcome(): string;
}
export declare namespace AnalyticsMetricReducer {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsMetricReducer;
    }
}
export declare class AnalyticsSource {
    constructor(source: string, device: string, provider: Nullable<string>, dataOrigin: Nullable<string>);
    get source(): string;
    get device(): string;
    get provider(): Nullable<string>;
    get dataOrigin(): Nullable<string>;
}
export declare namespace AnalyticsSource {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsSource;
    }
}
export declare class AnalyticsItem {
    constructor(collectionUs: string, collectionElapsedSeconds: number, eventId: string, observationId: string, producer: string, sequence: string, kind: string, source: string, timestamp: string, metricIds: Array<string>, scalarKinds: Array<string>, scalarValues: Array<string>, originalElapsedSeconds: Nullable<number>, representation: Nullable<string>, clockEpoch: Nullable<string>, connectionEpoch: Nullable<string>, interruptionIndex: number, action: Nullable<string>, distanceBarrier: boolean, interrupted: boolean, controllerSequence: Nullable<string>, hasElapsedMapping: boolean, origin?: Nullable<AnalyticsOrigin>, declaredMetrics?: Nullable<Array<string>>);
    get collectionUs(): string;
    get collectionElapsedSeconds(): number;
    get eventId(): string;
    get observationId(): string;
    get producer(): string;
    get sequence(): string;
    get kind(): string;
    get source(): string;
    get timestamp(): string;
    get metricIds(): Array<string>;
    get scalarKinds(): Array<string>;
    get scalarValues(): Array<string>;
    get originalElapsedSeconds(): Nullable<number>;
    get representation(): Nullable<string>;
    get clockEpoch(): Nullable<string>;
    get connectionEpoch(): Nullable<string>;
    get interruptionIndex(): number;
    get action(): Nullable<string>;
    get distanceBarrier(): boolean;
    get interrupted(): boolean;
    get controllerSequence(): Nullable<string>;
    get hasElapsedMapping(): boolean;
    get origin(): Nullable<AnalyticsOrigin>;
    get declaredMetrics(): Nullable<Array<string>>;
}
export declare namespace AnalyticsItem {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsItem;
    }
}
export declare class AnalyticsMetadata {
    constructor(id: string, startedAt: string, elapsedSeconds: number, indoor: boolean, watchEnabled: boolean, recordGPS: boolean, saveToHealth: boolean, sampleHz: number, endedAt: Nullable<string>, stopElapsedSeconds: Nullable<number>, sources: Array<AnalyticsSource>, completeEncodedMetadata: string, phase?: string, healthOutcome?: Nullable<string>, healthReadbackPending?: boolean);
    get id(): string;
    get startedAt(): string;
    get elapsedSeconds(): number;
    get indoor(): boolean;
    get watchEnabled(): boolean;
    get recordGPS(): boolean;
    get saveToHealth(): boolean;
    get sampleHz(): number;
    get endedAt(): Nullable<string>;
    get stopElapsedSeconds(): Nullable<number>;
    get sources(): Array<AnalyticsSource>;
    get completeEncodedMetadata(): string;
    get phase(): string;
    get healthOutcome(): Nullable<string>;
    get healthReadbackPending(): boolean;
}
export declare namespace AnalyticsMetadata {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsMetadata;
    }
}
export declare class AnalyticsPin {
    constructor(metadata: AnalyticsMetadata, observationRevision: string, distanceSelection: string);
    identity(): string;
    filename(): string;
    mayPublish(current: AnalyticsPin): boolean;
}
export declare namespace AnalyticsPin {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsPin;
    }
}
export declare class AnalyticsDistanceProfile {
    constructor(source: string, label: string, estimated: boolean, partial: boolean, coveredUs: string, uncoveredUs: string, distanceQ: string);
    get source(): string;
    get label(): string;
    get estimated(): boolean;
    get partial(): boolean;
    get coveredUs(): string;
    get uncoveredUs(): string;
    get distanceQ(): string;
    get coveredSeconds(): number;
    get uncoveredSeconds(): number;
    get distanceMeters(): number;
}
export declare namespace AnalyticsDistanceProfile {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsDistanceProfile;
    }
}
export declare class AnalyticsPrepared {
    private constructor();
    get actualEndUs(): string;
    get timerUs(): string;
    get lapEndsUs(): Array<string>;
    activeInterval(item: AnalyticsItem): Nullable<number>;
    activeIntervalAt(collectionUs: string, producer: string, sequence: string, clockEpoch: Nullable<string>): Nullable<number>;
    rangeTimerUs(startUs: string, endUs: string): string;
}
export declare namespace AnalyticsPrepared {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsPrepared;
    }
}
export declare class AnalyticsPreparation {
    constructor(pin: AnalyticsPin);
    append(item: AnalyticsItem): void;
    energy(evidence: AnalyticsEnergy): void;
    finish(selection: string, profiles: Array<AnalyticsDistanceProfile>): AnalyticsPrepared;
}
export declare namespace AnalyticsPreparation {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsPreparation;
    }
}
export declare class AnalyticsDistance {
    private constructor();
    get selection(): string;
    get selected(): Nullable<AnalyticsDistanceProfile>;
    get available(): Array<AnalyticsDistanceProfile>;
}
export declare namespace AnalyticsDistance {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsDistance;
    }
}
export declare class AnalyticsComposition {
    constructor(prepared: AnalyticsPrepared, selection: string, profiles: Array<AnalyticsDistanceProfile>, healthSelections: Array<AnalyticsHealthSelection>);
    append(item: AnalyticsItem): void;
    energy(evidence: AnalyticsEnergy): void;
    cancel(): void;
    summaryJson(lifecycleCount: string): Nullable<string>;
    outcome(): string;
}
export declare namespace AnalyticsComposition {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsComposition;
    }
}
export declare class AnalyticsOrigin {
    constructor(provider: Nullable<string>, dataOrigin: Nullable<string>, providerObservationId: Nullable<string>, originEventId: string, producer: string, sequence: string, originRevision: Nullable<string>);
    get provider(): Nullable<string>;
    get dataOrigin(): Nullable<string>;
    get providerObservationId(): Nullable<string>;
    get originEventId(): string;
    get producer(): string;
    get sequence(): string;
    get originRevision(): Nullable<string>;
}
export declare namespace AnalyticsOrigin {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsOrigin;
    }
}
export declare class AnalyticsEnergy {
    constructor(metric: string, kind: string, source: string, origin: AnalyticsOrigin, observationRevision: string, collectionUs: string, startUs: string, endUs: string, originalStartTimestamp: string, originalEndTimestamp: string, valueKcal: number);
    get metric(): string;
    get kind(): string;
    get source(): string;
    get origin(): AnalyticsOrigin;
    get observationRevision(): string;
    get startUs(): string;
    get endUs(): string;
    get originalStartTimestamp(): string;
    get originalEndTimestamp(): string;
    get valueKcal(): number;
    get collectionUs(): string;
}
export declare namespace AnalyticsEnergy {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsEnergy;
    }
}
export declare function analyticsHealthSource(owner: string, available: Array<string>): Nullable<string>;
export declare function analyticsEnergyAvailable(evidence: AnalyticsEnergy): boolean;
export declare class AnalyticsInterval {
    private constructor();
    get startUs(): string;
    get endUs(): string;
    get distanceQ(): string;
    get segment(): number;
    get activeInterval(): number;
    get start(): AnalyticsOriginal;
    get end(): AnalyticsOriginal;
    get speed0Q(): Nullable<string>;
    get speed1Q(): Nullable<string>;
    get indivisible(): boolean;
}
export declare namespace AnalyticsInterval {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsInterval;
    }
}
export declare class AnalyticsDistanceRange {
    private constructor();
    get distanceQ(): string;
    get coveredUs(): string;
    get distanceMeters(): number;
    get coveredSeconds(): number;
    get unresolvedBoundary(): boolean;
    get partial(): boolean;
}
export declare namespace AnalyticsDistanceRange {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsDistanceRange;
    }
}
export declare class AnalyticsDistanceReducer {
    constructor(startUs: string, endUs: string, distanceQ?: string, coveredUs?: string);
    append(interval: AnalyticsInterval): void;
    result(timerUs: string): AnalyticsDistanceRange;
}
export declare namespace AnalyticsDistanceReducer {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsDistanceReducer;
    }
}
export declare class AnalyticsGPSKernel {
    constructor(fullContinuity: boolean);
    get accepted(): boolean;
    set accepted(value: boolean);
    get startsSegment(): boolean;
    set startsSegment(value: boolean);
    get segment(): number;
    get altitudeSegments(): string;
    append(item: AnalyticsItem, activeInterval: Nullable<number>): Nullable<AnalyticsInterval>;
    reset(): void;
    restoreDistance(previous: Nullable<AnalyticsItem>, activeInterval: Nullable<number>, geometryPrevious: Nullable<AnalyticsItem>, geometryActiveInterval: Nullable<number>, segment: number): void;
    previousObservationId(): Nullable<string>;
    geometryPreviousObservationId(): Nullable<string>;
    distanceQ(): Nullable<string>;
    ascentQ(): Nullable<string>;
    descentQ(): Nullable<string>;
}
export declare namespace AnalyticsGPSKernel {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsGPSKernel;
    }
}
export declare class AnalyticsHealthSelection {
    constructor(metric: string, source: string, dataOrigin: Nullable<string>, coveredUs: string, origins: Array<AnalyticsOrigin>, startUs: string, endUs: string, workoutIdentity: Nullable<string>);
    get metric(): string;
    get source(): string;
    get dataOrigin(): Nullable<string>;
    get coveredUs(): string;
    get origins(): Array<AnalyticsOrigin>;
    get startUs(): string;
    get endUs(): string;
    get workoutIdentity(): Nullable<string>;
}
export declare namespace AnalyticsHealthSelection {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsHealthSelection;
    }
}
export declare class AnalyticsCoverage {
    private constructor();
    get dataOrigin(): string;
    get coveredUs(): string;
}
export declare namespace AnalyticsCoverage {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsCoverage;
    }
}
export declare class AnalyticsEnergyOrigins {
    constructor(source: string, startUs: string, endUs: string);
    append(metric: string, evidence: AnalyticsEnergy): void;
    selected(): Nullable<AnalyticsCoverage>;
}
export declare namespace AnalyticsEnergyOrigins {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsEnergyOrigins;
    }
}
export declare class AnalyticsControllerKernel {
    constructor();
    restore(item: Nullable<AnalyticsItem>, activeInterval: Nullable<number>, model: Nullable<string>, protocol: Nullable<string>, identity: Nullable<string>, continuity: Nullable<string>, segment: number, monotonicSeconds?: Nullable<number>, counter?: Nullable<string>): void;
    previousObservationId(): Nullable<string>;
    segment(): number;
    append(item: AnalyticsItem, activeInterval: Nullable<number>, model: Nullable<string>, protocol: Nullable<string>, deviceIdentity: Nullable<string>, continuityIdentity: Nullable<string>, monotonicSeconds: Nullable<number>, counter: Nullable<string>): Nullable<AnalyticsInterval>;
}
export declare namespace AnalyticsControllerKernel {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsControllerKernel;
    }
}
export declare function analyticsStoredInterval(startUs: string, endUs: string, distanceQ: string, segment: number, activeInterval: number, start: AnalyticsItem, end: AnalyticsItem, speed0Q: Nullable<string>, speed1Q: Nullable<string>, indivisible: boolean): AnalyticsInterval;
export declare function analyticsDistanceSelection(selection: string, profiles: Array<AnalyticsDistanceProfile>, indoor: boolean): AnalyticsDistance;
export declare function analyticsProfileTotals(source: string, distanceQ: string, coveredUs: string, timerUs: string, sources: Array<AnalyticsSource>): Nullable<AnalyticsDistanceProfile>;
export declare function analyticsDistanceLabel(source: string, sources: Array<AnalyticsSource>): string;
export declare class AnalyticsHeartRateOrigins {
    constructor(source: string, startUs: string, endUs: string);
    append(collectionUs: string, origin: AnalyticsOrigin, value: Nullable<number>, clockEpoch: Nullable<string>, connectionEpoch: Nullable<string>): void;
    selected(): Nullable<string>;
}
export declare namespace AnalyticsHeartRateOrigins {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsHeartRateOrigins;
    }
}
export declare function analyticsCollectionAvailable(collectionUs: string): boolean;
export declare function distanceResolveAtStart(selection: string, recordGPS: boolean, indoor: boolean): string;
export declare class CoreExportColumn {
    private constructor();
    get name(): string;
    get type(): string;
    get platforms(): Array<string>;
    get consumers(): Array<string>;
}
export declare namespace CoreExportColumn {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CoreExportColumn;
    }
}
export declare class CoreExportProjection {
    private constructor();
    get name(): string;
    get kinds(): Array<string>;
    get columns(): Array<CoreExportColumn>;
}
export declare namespace CoreExportProjection {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CoreExportProjection;
    }
}
export declare class CoreExportField {
    private constructor();
    get name(): string;
    get type(): string;
    get description(): string;
    get reads(): Array<string>;
    get unit(): Nullable<string>;
    get vendor(): Nullable<string>;
}
export declare namespace CoreExportField {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CoreExportField;
    }
}
export declare class CoreExportTable {
    private constructor();
    get name(): string;
    get path(): string;
    get projection(): string;
    get fields(): Array<CoreExportField>;
}
export declare namespace CoreExportTable {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CoreExportTable;
    }
}
export declare function coreExportProjections(): Array<CoreExportProjection>;
export declare function coreExportTables(): Array<CoreExportTable>;
export declare function coreRequiredSampleColumns(): Array<string>;
export declare function coreSampleIdentityColumns(): Array<string>;
export declare class CompletionSnapshotFields {
    private constructor();
    get collectionRevision(): Nullable<string>;
    get exportReady(): boolean;
    get historyIdentity(): string;
    get controlIdentity(): string;
}
export declare namespace CompletionSnapshotFields {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CompletionSnapshotFields;
    }
}
export declare function completionSnapshotFields(payload: any): CompletionSnapshotFields;
export declare function completionExportReady(fields: CompletionSnapshotFields): boolean;
export declare function completionCanDelete(targetId: string, targetPhase: string, current: any, facts?: Nullable<any>): boolean;
export declare function completionIdentity(fields: CompletionSnapshotFields, includeActiveFinality?: boolean): string;
export declare class CompletionUpdateCursor {
    constructor();
    beginRefresh(): number;
    receive(fields: CompletionSnapshotFields): boolean;
    finishRefresh(request: number, fields: CompletionSnapshotFields): boolean;
}
export declare namespace CompletionUpdateCursor {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CompletionUpdateCursor;
    }
}
export declare function completionOpenExport(json: string, selectedRideId: string, pinRevision: string): boolean;
export declare class AnalyticsConsumptionRange {
    private constructor();
    get amount(): number;
    get coveredUs(): string;
    get coveredSeconds(): number;
    get available(): boolean;
    get unresolvedBoundary(): boolean;
    get partial(): boolean;
}
export declare namespace AnalyticsConsumptionRange {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsConsumptionRange;
    }
}
export declare class AnalyticsConsumptionInterval {
    constructor(metric: string, startUs: string, endUs: string, startValue: number, endValue: number, rate0: Nullable<number>, rate1: Nullable<number>, indivisible: boolean, segment: number, activeInterval: number, baseline: boolean);
    get metric(): string;
    get startUs(): string;
    get endUs(): string;
    get startValue(): number;
    get endValue(): number;
    get rate0(): Nullable<number>;
    get rate1(): Nullable<number>;
    get indivisible(): boolean;
    get segment(): number;
    get activeInterval(): number;
    get baseline(): boolean;
    range(startUs: string, endUs: string): AnalyticsConsumptionRange;
}
export declare namespace AnalyticsConsumptionInterval {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsConsumptionInterval;
    }
}
export declare class AnalyticsConsumptionKernel {
    constructor(checkpoint?: Nullable<string>);
    checkpoint(): string;
    append(timeUs: string, activeInterval: Nullable<number>, continuityIdentity: Nullable<string>, chargeAh: Nullable<number>, energyWh: Nullable<number>, currentA: Nullable<number>, voltageV: Nullable<number>): Array<AnalyticsConsumptionInterval>;
}
export declare namespace AnalyticsConsumptionKernel {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsConsumptionKernel;
    }
}
export declare class AnalyticsConsumptionReducer {
    constructor(metric: string, startUs: string, endUs: string);
    append(interval: AnalyticsConsumptionInterval): void;
    result(timerUs: string): AnalyticsConsumptionRange;
}
export declare namespace AnalyticsConsumptionReducer {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => AnalyticsConsumptionReducer;
    }
}
export declare function controllerStreamStatus(requested: boolean, phase: string, age: Nullable<number>): string;
export declare function gpsStreamStatus(requested: boolean, phase: string, age: Nullable<number>, accuracy: Nullable<number>, providerStatus: Nullable<string>): string;
export declare class HealthEnergyConsumption {
    constructor(source: string, endUs: string, dataOrigin: Nullable<string>, metric?: string);
    append(evidence: AnalyticsEnergy): void;
    value(): Nullable<number>;
}
export declare namespace HealthEnergyConsumption {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => HealthEnergyConsumption;
    }
}
export declare class GPSLocationInput {
    constructor(timestamp: string, latitude: number, longitude: number, horizontalAccuracyM: number);
    get timestamp(): string;
    get latitude(): number;
    get longitude(): number;
    get horizontalAccuracyM(): number;
}
export declare namespace GPSLocationInput {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => GPSLocationInput;
    }
}
export declare class GPSTimelineInput {
    constructor(epoch: string, startedAtUs: string, monotonicOrigin: number, epochStart: Nullable<number>, stopMonotonic: Nullable<number>, uncertain: boolean, startedAtSubmicrosecondNanos?: number);
    get epoch(): string;
    get startedAtUs(): string;
    get monotonicOrigin(): number;
    get epochStart(): Nullable<number>;
    get stopMonotonic(): Nullable<number>;
    get uncertain(): boolean;
    get startedAtSubmicrosecondNanos(): number;
}
export declare namespace GPSTimelineInput {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => GPSTimelineInput;
    }
}
export declare class GPSMappedFix {
    private constructor();
    get timestamp(): string;
    get latitude(): number;
    get longitude(): number;
    get horizontalAccuracyM(): number;
    get elapsedSeconds(): number;
    get elapsedUs(): string;
    get acquisitionMonotonic(): number;
    get epoch(): string;
    get uncertain(): boolean;
}
export declare namespace GPSMappedFix {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => GPSMappedFix;
    }
}
export declare class GPSMappedBatch {
    private constructor();
    get fixes(): Array<GPSMappedFix>;
    get discontinuity(): boolean;
}
export declare namespace GPSMappedBatch {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => GPSMappedBatch;
    }
}
export declare class GPSAcquisitionClock {
    constructor();
    mapBatch(originals: Array<GPSLocationInput>, receiptUtcUs: string, monotonicSeconds: number, timeline: GPSTimelineInput): GPSMappedBatch;
    admit(fix: GPSMappedFix): boolean;
    mapBatchAt(originals: Array<GPSLocationInput>, receiptTimestamp: string, monotonicSeconds: number, timeline: GPSTimelineInput): GPSMappedBatch;
}
export declare namespace GPSAcquisitionClock {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => GPSAcquisitionClock;
    }
}
export declare class InspectionMetricPolicy {
    constructor(metric: string);
    get channel(): string;
    get plotGapUs(): string;
    get integrationGapUs(): string;
    get suppressed(): boolean;
    get minimum(): Nullable<number>;
    get maximum(): Nullable<number>;
}
export declare namespace InspectionMetricPolicy {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionMetricPolicy;
    }
}
export declare class InspectionTargetInput {
    constructor(timeUs: string, observationId: Nullable<string>, anchorTimeUs: Nullable<string>);
    get timeUs(): string;
    get observationId(): Nullable<string>;
    get anchorTimeUs(): Nullable<string>;
    withNearbyOriginal(metric: string, originals: Array<InspectionOriginalInput>): InspectionTargetInput;
}
export declare namespace InspectionTargetInput {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionTargetInput;
    }
}
export declare class InspectionDistanceBoundary {
    constructor(item: AnalyticsItem, segment: number, startUs: string, endUs: string, tieKey: string, plotSegment?: number, metric?: string);
}
export declare namespace InspectionDistanceBoundary {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionDistanceBoundary;
    }
}
export declare function inspectionDistanceOriginal(target: InspectionTargetInput, before: Nullable<InspectionDistanceBoundary>, after: Nullable<InspectionDistanceBoundary>, anchored: Nullable<InspectionDistanceBoundary>): Nullable<AnalyticsOriginal>;
export declare class InspectionQueryResult {
    private constructor();
    get outcome(): string;
    get statistics(): Nullable<AnalyticsStatistics>;
    get selection(): Nullable<AnalyticsOriginal>;
    get reference(): Nullable<AnalyticsOriginal>;
}
export declare namespace InspectionQueryResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionQueryResult;
    }
}
export declare class InspectionMetricQuery {
    constructor(metric: string, startUs: string, endUs: string, rideEndUs: string, lifecycle: Array<AnalyticsItem>, selection: Nullable<InspectionTargetInput>, reference: Nullable<InspectionTargetInput>, allowTrailing: boolean);
    append(item: AnalyticsItem, selected: boolean): void;
    appendInspection(item: AnalyticsItem, selected: boolean): void;
    appendRetained(item: AnalyticsItem, activeInterval: Nullable<number>, interruptionBoundary?: boolean): void;
    appendPreparedInspection(item: InspectionOriginalInput, selected: boolean, retained: boolean, boundary: boolean): void;
    checkpoint(): Nullable<InspectionMetricCheckpoint>;
    canMerge(checkpoint: InspectionMetricCheckpoint): boolean;
    mergeOwned(checkpoint: InspectionMetricCheckpoint): void;
    restore(checkpoint: InspectionMetricCheckpoint): void;
    result(): InspectionQueryResult;
}
export declare namespace InspectionMetricQuery {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionMetricQuery;
    }
}
export declare class InspectionMetricCheckpoint {
    constructor(payload: Nullable<string>);
    get payload(): string;
}
export declare namespace InspectionMetricCheckpoint {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionMetricCheckpoint;
    }
}
export declare class InspectionMetricBucket {
    constructor(metric: string, startUs: string, endUs: string, rideEndUs: string, lifecycle: Array<AnalyticsItem>, predecessor: Nullable<AnalyticsItem>);
    append(item: AnalyticsItem, selected: boolean): void;
    appendPrepared(item: InspectionOriginalInput, selected: boolean): void;
    inheritPredecessor(checkpoint: InspectionMetricCheckpoint): void;
    canMerge(checkpoint: InspectionMetricCheckpoint): boolean;
    mergeOwned(checkpoint: InspectionMetricCheckpoint): void;
    checkpoint(): Nullable<InspectionMetricCheckpoint>;
}
export declare namespace InspectionMetricBucket {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionMetricBucket;
    }
}
export declare class InspectionCacheEncoder {
    constructor();
    statistics(checkpoint: InspectionMetricCheckpoint): string;
    plot(cache: InspectionPlotCache): string;
}
export declare namespace InspectionCacheEncoder {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionCacheEncoder;
    }
}
export declare class InspectionPlotCache {
    constructor(payload: Nullable<string>);
    get payload(): string;
}
export declare namespace InspectionPlotCache {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionPlotCache;
    }
}
export declare class InspectionOriginalInput {
    constructor(item: AnalyticsItem);
    isReading(metric: string): boolean;
    plotValid(metric: string): boolean;
}
export declare namespace InspectionOriginalInput {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionOriginalInput;
    }
}
export declare class InspectionPlotBucket {
    constructor(metric: string, startUs: string, endUs: string);
    append(item: AnalyticsItem, selected: boolean): void;
    appendOriginal(item: InspectionOriginalInput, selected: boolean): void;
    appendDistance(point: InspectionDistanceBoundary): void;
    canMerge(aggregate: InspectionPlotCache): boolean;
    merge(aggregate: InspectionPlotCache): void;
    aggregate(): InspectionPlotCache;
}
export declare namespace InspectionPlotBucket {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionPlotBucket;
    }
}
export declare class InspectionPlotOriginal {
    private constructor();
    get original(): AnalyticsOriginal;
    get timeUs(): string;
    get segment(): number;
}
export declare namespace InspectionPlotOriginal {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionPlotOriginal;
    }
}
export declare class InspectionPlotQuery {
    constructor(metric: string, startUs: string, endUs: string, buckets: number, pointLimit: number, alignedSlotUs?: Nullable<string>);
    append(item: AnalyticsItem, selected: boolean, interruptionBoundary?: boolean): void;
    appendDistance(point: InspectionDistanceBoundary): void;
    canMerge(aggregate: InspectionPlotCache): boolean;
    merge(aggregate: InspectionPlotCache): void;
    result(): Array<InspectionPlotOriginal>;
}
export declare namespace InspectionPlotQuery {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionPlotQuery;
    }
}
export declare class InspectionRequestContext {
    constructor(sourceId: string, revision: string, distanceSelection: string, interactionEpoch: string, interactionSequence: string);
    get sourceId(): string;
    get revision(): string;
    get distanceSelection(): string;
    get interactionEpoch(): string;
    get interactionSequence(): string;
}
export declare namespace InspectionRequestContext {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionRequestContext;
    }
}
export declare class InspectionRequestTicket {
    private constructor();
    get generation(): string;
}
export declare namespace InspectionRequestTicket {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionRequestTicket;
    }
}
export declare class InspectionRequestGate {
    constructor();
    begin(context: InspectionRequestContext): InspectionRequestTicket;
    cancel(): void;
    accepts(ticket: InspectionRequestTicket): boolean;
    acceptsGeneration(ticket: InspectionRequestTicket, generation: string): boolean;
}
export declare namespace InspectionRequestGate {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => InspectionRequestGate;
    }
}
export declare function inspectionContextsMatch(current: InspectionRequestContext, result: InspectionRequestContext): boolean;
export declare function inspectionHeldTail(metric: string, observationId: string, originalSeconds: number, value: number, evidenceObservationId: string, acquisitionMonotonic: Nullable<number>, monotonicNow: number, elapsedNow: number): Nullable<number>;
export declare function inspectionRenderedSegment(metric: string, previousSeconds: number, previousValue: number, seconds: number, value: number, explicit: Nullable<boolean>): boolean;
export declare function inspectionRenderedOriginal(metric: string, items: Array<InspectionOriginalInput>, segments: Int32Array, timeUs: string): Nullable<AnalyticsOriginal>;
export declare function presentationFormatPoint(id: string, value: Nullable<number>, exactValue: Nullable<string>, speedUnit: string): string;
export declare function presentationFormatDifference(id: string, a: Nullable<number>, b: Nullable<number>, aExact: Nullable<string>, bExact: Nullable<string>, speedUnit: string): string;
export declare function presentationMetricAllowsMean(id: string): boolean;
export declare function presentationTransportDisplay(connection: string, acquiredAt: Nullable<number>, now: number): string;
export declare function presentationReadingAvailable(acquiredAt: Nullable<number>, now: Nullable<number>, cutoff: number): boolean;
export declare class ControllerPreviewResult {
    private constructor();
    get display(): string;
    get faultCode(): Nullable<number>;
    get nextDeadline(): Nullable<number>;
}
export declare namespace ControllerPreviewResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ControllerPreviewResult;
    }
}
export declare class ControllerPreviewPresentation {
    constructor();
    clear(): void;
    observe(connection: string, sequence: number, acquiredAt: number, faultCode: number, mapped: Nullable<number>, now: number): boolean;
    get acquiredAt(): Nullable<number>;
    view(connection: string, mapped: Nullable<number>, now: number): ControllerPreviewResult;
}
export declare namespace ControllerPreviewPresentation {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => ControllerPreviewPresentation;
    }
}
export declare function presentationConnectionLabel(connection: string, display: string): string;
export declare function presentationStreamStatus(status: Nullable<string>): string;
export declare function presentationMetric(id: string, speedUnit: string): CoreMetricDeclaration;
export declare function presentationFormatMetric(id: string, value: Nullable<number>, speedUnit: string): string;
export declare function presentationDuration(seconds: Nullable<number>): string;
export declare function presentationProvider(provider: Nullable<string>): string;
export declare function presentationPhysiology(label: string, provider: Nullable<string>): string;
export declare function presentationLocationPermission(status: string): string;
export declare function presentationDistanceCaption(label: string, source: Nullable<string>, partial: boolean, estimated: boolean): string;
export declare function presentationDistanceOptionLabel(source: string, foregroundOnly: boolean): string;
export declare class PresentationCapabilities {
    constructor(phoneWorkout: boolean, watchWorkout: boolean, phoneHealth: boolean, watchHealth: boolean, gps: boolean, healthProvider: Nullable<string>, foregroundOnly: boolean);
    get phoneWorkout(): boolean;
    get watchWorkout(): boolean;
    get phoneHealth(): boolean;
    get watchHealth(): boolean;
    get gps(): boolean;
    get healthProvider(): Nullable<string>;
    get foregroundOnly(): boolean;
}
export declare namespace PresentationCapabilities {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationCapabilities;
    }
}
export declare class PresentationOptions {
    constructor(indoor: boolean, useWatch: Nullable<boolean>, saveToHealth: Nullable<boolean>, recordGPS: Nullable<boolean>, sampleHz: Nullable<number>, distanceSource?: string, batteryConsumptionSource?: string);
    get indoor(): boolean;
    get useWatch(): Nullable<boolean>;
    get saveToHealth(): Nullable<boolean>;
    get recordGPS(): Nullable<boolean>;
    get sampleHz(): Nullable<number>;
    get batteryConsumptionSource(): string;
}
export declare namespace PresentationOptions {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationOptions;
    }
}
export declare class PresentationFrozenOptions {
    private constructor();
    get indoor(): boolean;
    get useWatch(): boolean;
    get saveToHealth(): boolean;
    get recordGPS(): boolean;
    get batteryConsumptionSource(): string;
    get sampleHz(): number;
    get recordingOwner(): string;
    get healthWriter(): Nullable<string>;
    get distanceSource(): string;
}
export declare namespace PresentationFrozenOptions {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationFrozenOptions;
    }
}
export declare function presentationResolveOptions(options: PresentationOptions, capabilities: PresentationCapabilities): PresentationFrozenOptions;
export declare function presentationSampleRates(): Int32Array;
export declare class PresentationStartContext {
    constructor(surface: string, hasSession: boolean, endingOwner: boolean, recoveredForeignOwner: boolean, unresolvedOwner: boolean, finishing: boolean, busy: boolean, recovering: boolean, deletionProbeInFlight: boolean);
}
export declare namespace PresentationStartContext {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationStartContext;
    }
}
export declare class PresentationReadiness {
    private constructor();
    get available(): boolean;
    get error(): Nullable<string>;
}
export declare namespace PresentationReadiness {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationReadiness;
    }
}
export declare function presentationStartReadiness(options: PresentationFrozenOptions, capabilities: PresentationCapabilities, bikeReady: boolean, watchInstalled: boolean, phase: Nullable<string>, startContext: PresentationStartContext, error: Nullable<string>): PresentationReadiness;
export declare class PresentationPermissions {
    constructor(healthAvailable: boolean, provider: Nullable<string>, requiredWrites: Nullable<Array<string>>, writeTypes: Array<string>, writeAuthorization: Array<string>, location: string, servicesEnabled: boolean, accuracy: string);
}
export declare namespace PresentationPermissions {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationPermissions;
    }
}
export declare class PresentationPermissionAction {
    private constructor();
    get action(): string;
    get label(): string;
    get detail(): string;
    get settingsTarget(): Nullable<string>;
}
export declare namespace PresentationPermissionAction {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationPermissionAction;
    }
}
export declare function presentationPermissionAction(status: Nullable<PresentationPermissions>, options: PresentationOptions): PresentationPermissionAction;
export declare class PresentationSource {
    private constructor();
    get label(): string;
    get detail(): string;
    get tone(): string;
}
export declare namespace PresentationSource {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationSource;
    }
}
export declare class PresentationSources {
    private constructor();
    get bike(): PresentationSource;
    get heartRate(): PresentationSource;
    get gps(): PresentationSource;
}
export declare namespace PresentationSources {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationSources;
    }
}
export declare function presentationSources(phase: string, selected: PresentationFrozenOptions, bikeReady: boolean, controllerStatus: string, heartStatus: string, gpsStatus: string, gpsSource: string, gpsAccuracy: Nullable<number>, healthProvider: Nullable<string>): PresentationSources;
export declare function presentationStateSources(phase: string, indoor: boolean, useWatch: boolean, saveToHealth: boolean, recordGPS: boolean, bikeReady: boolean, controllerStatus: string, heartStatus: string, gpsStatus: string, gpsSource: string, gpsAccuracy: Nullable<number>, healthProvider: Nullable<string>, controllerDisplay: Nullable<string>): PresentationSources;
export declare function presentationHealthSetup(options: PresentationOptions, capabilities: PresentationCapabilities): PresentationSource;
export declare function presentationOwnership(foreignOwner: Nullable<string>): Nullable<string>;
export declare class PresentationActionResult {
    private constructor();
    get admitted(): boolean;
    get error(): Nullable<string>;
    get haptic(): string;
    get busy(): boolean;
    get pendingCommandId(): Nullable<string>;
    get pendingRideId(): Nullable<string>;
}
export declare namespace PresentationActionResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationActionResult;
    }
}
export declare class RideActionPresentation {
    constructor();
    admit(commandId: string, rideId: Nullable<string>, action: string, phase: string, currentRideId: Nullable<string>, foreignOwner: boolean, confirmedRideId: Nullable<string>, nativePendingAction: Nullable<string>, startContext: PresentationStartContext, surface?: string, adapterBusy?: boolean): PresentationActionResult;
    availability(rideId: Nullable<string>, action: string, phase: string, currentRideId: Nullable<string>, foreignOwner: boolean, confirmedRideId: Nullable<string>, nativePendingAction: Nullable<string>, startContext: PresentationStartContext, surface: string, adapterBusy: boolean): PresentationActionResult;
    complete(commandId: string, rideId: Nullable<string>, error: Nullable<string>): PresentationActionResult;
    clearError(): void;
    get busy(): boolean;
    get error(): Nullable<string>;
}
export declare namespace RideActionPresentation {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => RideActionPresentation;
    }
}
export declare class PresentationExternalResult {
    private constructor();
    get outcome(): string;
    get command(): Nullable<string>;
    get error(): Nullable<string>;
}
export declare namespace PresentationExternalResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationExternalResult;
    }
}
export declare function presentationExternalControl(currentRideId: Nullable<string>, currentToken: string, phase: string, pendingAction: Nullable<string>, rideId: string, token: string, expectedPhase: string, action: string, confirmed: boolean): PresentationExternalResult;
export declare class PresentationWaitResult {
    private constructor();
    get outcome(): string;
    get pending(): boolean;
    get error(): Nullable<string>;
}
export declare namespace PresentationWaitResult {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationWaitResult;
    }
}
export declare function presentationWait(kind: string, beganAtUs: string, nowUs: string): PresentationWaitResult;
export declare function presentationWaitDeadline(kind: string, beganAtUs: string): string;
export declare class PresentationPreset {
    constructor(numbers: Array<string>, charts: Array<string>, range: string, webColumns: number);
    get numbers(): Array<string>;
    get charts(): Array<string>;
    get range(): string;
    get webColumns(): number;
}
export declare namespace PresentationPreset {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationPreset;
    }
}
export declare class PresentationPreferences {
    private constructor();
    get storedJson(): string;
    get activeView(): string;
    get historyView(): Nullable<string>;
    get historyRange(): string;
    get speedUnit(): string;
    get distanceSource(): string;
    get batteryConsumptionSource(): string;
    get fitPowerSource(): string;
    get sampleHz(): number;
    get options(): PresentationOptions;
    get startOptions(): PresentationOptions;
    view(id: string): PresentationPreset;
    withPreset(id: string, preset: PresentationPreset): PresentationPreferences;
    withSelections(live: string, history: Nullable<string>, range: string): PresentationPreferences;
    withOptions(options: PresentationOptions, sampleHz: number): PresentationPreferences;
    withBatteryConsumptionSource(source: string): PresentationPreferences;
    withFitPowerSource(source: string): PresentationPreferences;
    withDisplay(speedUnit: string, distanceSource: string): PresentationPreferences;
}
export declare namespace PresentationPreferences {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationPreferences;
    }
}
export declare function presentationDefaultPreferences(): PresentationPreferences;
export declare function presentationPresetLabel(id: string): string;
export declare function presentationParsePreferences(text: string): PresentationPreferences;
export declare class PresentationPreferenceLoad {
    private constructor();
    get preferences(): PresentationPreferences;
    get error(): Nullable<string>;
    get writable(): boolean;
    get shouldSave(): boolean;
    change(replacement: PresentationPreferences): PresentationPreferenceLoad;
}
export declare namespace PresentationPreferenceLoad {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationPreferenceLoad;
    }
}
export declare function presentationLoadPreferences(previous: PresentationPreferences, storedJson: Nullable<string>, loadError: Nullable<string>): PresentationPreferenceLoad;
export declare function presentationRecoveryStopOffered(phase: string, pendingAction: Nullable<string>): boolean;
export declare class PresentationFailure {
    private constructor();
    get code(): string;
    get message(): string;
    get nextStep(): string;
    get text(): string;
}
export declare namespace PresentationFailure {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => PresentationFailure;
    }
}
export declare function presentationFailure(code: Nullable<string>): PresentationFailure;
export declare function presentationFailureCodes(): Array<string>;
export declare function protocolTimestampMicroseconds(timestamp: string): string;
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
export declare function controllerRestoreIdentity(controllerModel: string, firmwareLabel: string, controllerProtocol: string): ControllerIdentity;
export declare function controllerRestoreReading(metrics: Array<StoredMetric>, identity: ControllerIdentity): TelemetryReading;
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
export declare class TelemetryNotifications {
    private constructor();
    get action(): string;
    get replies(): Array<ControllerResponse>;
    get discardedBytes(): string;
    get pendingBytes(): number;
}
export declare namespace TelemetryNotifications {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TelemetryNotifications;
    }
}
export declare class TelemetryTimingPolicy {
    private constructor();
    get telemetryTimeoutSeconds(): number;
    get discoverySeconds(): number;
    get attemptSeconds(): number;
    get cancellationGraceSeconds(): number;
    get displayHoldSeconds(): number;
    get stableTelemetrySeconds(): number;
}
export declare namespace TelemetryTimingPolicy {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TelemetryTimingPolicy;
    }
}
export declare function telemetryTimingPolicy(): TelemetryTimingPolicy;
export declare class TelemetryRecoveryPlan {
    private constructor();
    get delaySeconds(): number;
    get adoptSystemReconnect(): boolean;
    get cancelBeforeRetry(): boolean;
}
export declare namespace TelemetryRecoveryPlan {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TelemetryRecoveryPlan;
    }
}
export declare function telemetryRecoveryPlan(attempt: number, activeRide: boolean, callbackCompleted: boolean, peerOrSystem: boolean, stableTelemetrySeconds: Nullable<number>, systemIsReconnecting: boolean, systemAttemptPending: boolean, peripheralIsDisconnected: boolean): Nullable<TelemetryRecoveryPlan>;
export declare class TelemetryTransportSession {
    constructor(protocol: ControllerProtocolApi);
    get discardedBytes(): string;
    get pendingBytes(): number;
    get generation(): string;
    get stage(): string;
    get attemptDeadline(): Nullable<number>;
    get reconnectDue(): Nullable<number>;
    get cancellationDeadline(): Nullable<number>;
    get cancellationPending(): boolean;
    get cancellationFailed(): boolean;
    get identity(): Nullable<ControllerIdentity>;
    requestFrame(request: string): Int8Array;
    notifications(generation: string, request: string, bytes: Int8Array, sentAt: number, now: number): TelemetryNotifications;
    begin(now: number, systemInitiated: boolean): boolean;
    advance(generation: string, expected: string, next: string, now: number): string;
    check(now: number, responseSentAt: Nullable<number>, writeBlockedAt: Nullable<number>, nativeDisconnecting?: boolean): string;
    scheduleRetry(plan: TelemetryRecoveryPlan, now: number): void;
    cancelDuringBackoff(): void;
    disconnected(generation: string, now: number): void;
    manualDisconnect(cancellationRequired: boolean): void;
    radioUnavailable(): void;
}
export declare namespace TelemetryTransportSession {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TelemetryTransportSession;
    }
}
export declare class TelemetryDiscoveryWindow {
    constructor(generation: string, now: number);
    get deadline(): number;
    accepts(generation: string, now: number): boolean;
    expired(now: number): boolean;
}
export declare namespace TelemetryDiscoveryWindow {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => TelemetryDiscoveryWindow;
    }
}
export declare function telemetryReplyAdmissible(sentAt: number, now: number): boolean;
export declare function telemetryWriteExpired(blockedAt: number, now: number): boolean;
export declare class CoreMetricDeclaration {
    private constructor();
    get id(): string;
    get label(): string;
    get shortLabel(): string;
    get unit(): string;
    get group(): string;
    get color(): string;
    get decimals(): number;
    get gapSeconds(): number;
    get scale(): number;
    get kind(): Nullable<string>;
    get zero(): Nullable<boolean>;
    get semantics(): Nullable<string>;
    get description(): Nullable<string>;
}
export declare namespace CoreMetricDeclaration {
    /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
    namespace $metadata$ {
        const constructor: abstract new () => CoreMetricDeclaration;
    }
}
export declare function coreMetricDeclarations(): Array<CoreMetricDeclaration>;
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