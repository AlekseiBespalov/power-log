package app.powerlog.core.health

import app.powerlog.core.Sha256
import app.powerlog.core.analytics.*
import app.powerlog.core.protocol.*

object HealthCodec {
    private fun document(text: String): Json = HealthDocuments.parse(text)

    internal fun canonicalContent(value: NormalizedContent): NormalizedContent =
        fromNormalizedContent(HealthDocuments.parse(JsonCodec.encode(toNormalizedContent(value))))

    private fun fields(vararg pairs: Pair<String, Json?>): Obj =
        Obj(pairs.filter { it.second != null }.associate { it.first to requireNotNull(it.second) })

    private fun decimal(value: Json): Long {
        val text = value.string()
        val number = text.toLongOrNull() ?: invalid()
        checkValue(number.toString() == text)
        return number
    }

    private fun int32(value: Json): Int {
        val n = value.number()
        checkValue(n >= Int.MIN_VALUE.toDouble() && n <= Int.MAX_VALUE.toDouble() && n == n.toInt().toDouble())
        return n.toInt()
    }

    @Throws(ProtocolException::class)
    fun contentDigest(content: NormalizedContent): String =
        Sha256.hash(encodeNormalizedContent(content).encodeToByteArray())

    private fun fromHealthKind(json: Json): HealthKind =
        HealthKind.entries.firstOrNull { it.wire == json.string() } ?: invalid()

    private fun fromComponent(json: Json): Component =
        Component.entries.firstOrNull { it.wire == json.string() } ?: invalid()

    private fun fromEndpointRole(json: Json): EndpointRole =
        EndpointRole.entries.firstOrNull { it.name == json.string() } ?: invalid()

    private fun fromDevice(json: Json): Device = Device.entries.firstOrNull { it.name == json.string() } ?: invalid()

    private fun fromProvider(json: Json): Provider =
        Provider.entries.firstOrNull { it.name == json.string() } ?: invalid()

    private fun fromSource(json: Json): Source = Source.entries.firstOrNull { it.name == json.string() } ?: invalid()

    private fun fromAbsence(json: Json): Absence = Absence.entries.firstOrNull { it.name == json.string() } ?: invalid()

    private fun fromControllerAbsence(json: Json): ControllerAbsence =
        ControllerAbsence.entries.firstOrNull { it.name == json.string() } ?: invalid()

    private fun fromComponentState(json: Json): ComponentState =
        ComponentState.entries.firstOrNull { it.name == json.string() } ?: invalid()

    private fun fromHealthReason(json: Json): HealthReason =
        HealthReason.entries.firstOrNull { it.name == json.string() } ?: invalid()

    private fun fromQuantity(json: Json): Quantity =
        Quantity.entries.firstOrNull { it.name == json.string() } ?: invalid()

    internal fun toMeasurementSource(v: MeasurementSource): Obj =
        fields(
            "source" to Str(v.source.name),
            "device" to Str(v.device.name),
            "provider" to v.provider?.let { Str(it.name) },
            "dataOrigin" to v.dataOrigin?.let { Str(it) },
        )

    internal fun fromMeasurementSource(json: Json): MeasurementSource {
        val o = json.obj()
        o.closed("source device", "provider dataOrigin")
        val result =
            MeasurementSource(
                fromSource(o.required("source")),
                fromDevice(o.required("device")),
                o["provider"]?.let { fromProvider(it) },
                o["dataOrigin"]?.let { it.string() },
            )
        validate(result)
        return result
    }

    @Throws(ProtocolException::class)
    fun encodeOrigin(value: Origin): String {
        val json = toOrigin(value)
        fromOrigin(json)
        return HealthDocuments.encode(json)
    }

    @Throws(ProtocolException::class) fun decodeOrigin(text: String): Origin = fromOrigin(document(text))

    internal fun toOrigin(v: Origin): Obj =
        fields(
            "provider" to v.provider?.let { Str(it.name) },
            "dataOrigin" to v.dataOrigin?.let { Str(it) },
            "providerObservationId" to v.providerObservationId?.let { Str(it) },
            "originEventId" to Str(v.originEventId),
            "producer" to Str(v.producer.name),
            "sequence" to Str(v.sequence.toString()),
            "originRevision" to v.originRevision?.let { Str(it) },
        )

    internal fun fromOrigin(json: Json): Origin {
        val o = json.obj()
        o.closed("originEventId producer sequence", "provider dataOrigin providerObservationId originRevision")
        val result =
            Origin(
                o["provider"]?.let { fromProvider(it) },
                o["dataOrigin"]?.let { it.string() },
                o["providerObservationId"]?.let { it.string() },
                o.required("originEventId").string(),
                fromSource(o.required("producer")),
                decimal(o.required("sequence")),
                o["originRevision"]?.let { it.string() },
            )
        validate(result)
        return result
    }

    internal fun toSecondValue(v: SecondValue): Obj =
        fields(
            "n" to Str(v.n.toString()),
            "startUs" to Str(v.startUs.toString()),
            "endUs" to Str(v.endUs.toString()),
            "validOriginalCount" to Str(v.validOriginalCount.toString()),
            "integralQUs" to Str(v.integralQUs.toString()),
            "coveredUs" to Str(v.coveredUs.toString()),
            "value" to Num(v.value),
            "source" to toMeasurementSource(v.source),
        )

    internal fun fromSecondValue(json: Json): SecondValue {
        val o = json.obj()
        o.closed("n startUs endUs validOriginalCount integralQUs coveredUs value source", "")
        val result =
            SecondValue(
                decimal(o.required("n")),
                decimal(o.required("startUs")),
                decimal(o.required("endUs")),
                decimal(o.required("validOriginalCount")),
                decimal(o.required("integralQUs")),
                decimal(o.required("coveredUs")),
                o.required("value").number(),
                fromMeasurementSource(o.required("source")),
            )
        validate(result)
        return result
    }

    internal fun toPublicationMetadata(v: PublicationMetadata): Obj =
        fields(
            "rideId" to Str(v.rideId),
            "recordingOwner" to Str(v.recordingOwner.name),
            "ownerEndpointId" to Str(v.ownerEndpointId),
            "healthPublisher" to Str(v.healthPublisher.name),
            "publisherEndpointId" to Str(v.publisherEndpointId),
            "indoor" to Bool(v.indoor),
            "recordGPS" to Bool(v.recordGPS),
        )

    internal fun fromPublicationMetadata(json: Json): PublicationMetadata {
        val o = json.obj()
        o.closed("rideId recordingOwner ownerEndpointId healthPublisher publisherEndpointId indoor recordGPS", "")
        val result =
            PublicationMetadata(
                o.required("rideId").string(),
                fromEndpointRole(o.required("recordingOwner")),
                o.required("ownerEndpointId").string(),
                fromEndpointRole(o.required("healthPublisher")),
                o.required("publisherEndpointId").string(),
                o.required("indoor").boolean(),
                o.required("recordGPS").boolean(),
            )
        validate(result)
        return result
    }

    internal fun toQuantityContent(v: QuantityContent): Obj =
        fields(
            "kind" to Str(v.kind.wire),
            "startUs" to Str(v.startUs.toString()),
            "endUs" to Str(v.endUs.toString()),
            "value" to Num(v.value),
            "unit" to Str(v.unit),
            "source" to toMeasurementSource(v.source),
            "metadata" to toPublicationMetadata(v.metadata),
        )

    internal fun fromQuantityContent(json: Json): QuantityContent {
        val o = json.obj()
        o.closed("kind startUs endUs value unit source metadata", "")
        val result =
            QuantityContent(
                fromHealthKind(o.required("kind")),
                decimal(o.required("startUs")),
                decimal(o.required("endUs")),
                o.required("value").number(),
                o.required("unit").string(),
                fromMeasurementSource(o.required("source")),
                fromPublicationMetadata(o.required("metadata")),
            )
        validate(result)
        return result
    }

    internal fun toBoundaryContent(v: BoundaryContent): Obj =
        fields(
            "firstOrigin" to Str(v.firstOrigin),
            "lastOrigin" to Str(v.lastOrigin),
            "startUs" to Str(v.startUs.toString()),
            "endUs" to Str(v.endUs.toString()),
        )

    internal fun fromBoundaryContent(json: Json): BoundaryContent {
        val o = json.obj()
        o.closed("firstOrigin lastOrigin startUs endUs", "")
        val result =
            BoundaryContent(
                o.required("firstOrigin").string(),
                o.required("lastOrigin").string(),
                decimal(o.required("startUs")),
                decimal(o.required("endUs")),
            )
        validate(result)
        return result
    }

    @Throws(ProtocolException::class)
    fun encodeRoutePoint(value: RoutePoint): String {
        val json = toRoutePoint(value)
        fromRoutePoint(json)
        return HealthDocuments.encode(json)
    }

    @Throws(ProtocolException::class) fun decodeRoutePoint(text: String): RoutePoint = fromRoutePoint(document(text))

    internal fun toRoutePoint(v: RoutePoint): Obj =
        fields(
            "originKey" to Str(v.originKey),
            "timeUs" to Str(v.timeUs.toString()),
            "latitude" to Num(v.latitude),
            "longitude" to Num(v.longitude),
            "horizontalAccuracyM" to v.horizontalAccuracyM?.let { Num(it) },
            "verticalAccuracyM" to v.verticalAccuracyM?.let { Num(it) },
            "altitudeMeters" to v.altitudeMeters?.let { Num(it) },
        )

    internal fun fromRoutePoint(json: Json): RoutePoint {
        val o = json.obj()
        o.closed("originKey timeUs latitude longitude", "horizontalAccuracyM verticalAccuracyM altitudeMeters")
        val result =
            RoutePoint(
                o.required("originKey").string(),
                decimal(o.required("timeUs")),
                o.required("latitude").number(),
                o.required("longitude").number(),
                o["horizontalAccuracyM"]?.let { it.number() },
                o["verticalAccuracyM"]?.let { it.number() },
                o["altitudeMeters"]?.let { it.number() },
            )
        validate(result)
        return result
    }

    internal fun toSessionContent(v: SessionContent): Obj =
        fields(
            "startUs" to Str(v.startUs.toString()),
            "endUs" to Str(v.endUs.toString()),
            "metadata" to toPublicationMetadata(v.metadata),
            "laps" to Arr(v.laps.map { toBoundaryContent(it) }),
            "pauses" to Arr(v.pauses.map { toBoundaryContent(it) }),
            "route" to Arr(v.route.map { toRoutePoint(it) }),
        )

    internal fun fromSessionContent(json: Json): SessionContent {
        val o = json.obj()
        o.closed("startUs endUs metadata laps pauses route", "")
        val result =
            SessionContent(
                decimal(o.required("startUs")),
                decimal(o.required("endUs")),
                fromPublicationMetadata(o.required("metadata")),
                o.required("laps").array().map { fromBoundaryContent(it) },
                o.required("pauses").array().map { fromBoundaryContent(it) },
                o.required("route").array().map { fromRoutePoint(it) },
            )
        validate(result)
        return result
    }

    @Throws(ProtocolException::class)
    fun encodeComponentResult(value: ComponentResult): String {
        val json = toComponentResult(value)
        fromComponentResult(json)
        return HealthDocuments.encode(json)
    }

    @Throws(ProtocolException::class)
    fun decodeComponentResult(text: String): ComponentResult = fromComponentResult(document(text))

    internal fun toComponentResult(v: ComponentResult): Obj =
        fields(
            "component" to Str(v.component.wire),
            "sourceRevision" to Str(v.sourceRevision.toString()),
            "required" to Bool(v.required),
            "state" to Str(v.state.name),
            "reason" to v.reason?.let { Str(it.name) },
            "providerIds" to Arr(v.providerIds.map { Str(it) }),
        )

    internal fun fromComponentResult(json: Json): ComponentResult {
        val o = json.obj()
        o.closed("component sourceRevision required state providerIds", "reason")
        val result =
            ComponentResult(
                fromComponent(o.required("component")),
                decimal(o.required("sourceRevision")),
                o.required("required").boolean(),
                fromComponentState(o.required("state")),
                o["reason"]?.let { fromHealthReason(it) },
                o.required("providerIds").array().map { it.string() },
            )
        validate(result)
        return result
    }

    @Throws(ProtocolException::class)
    fun encodeProviderParent(value: ProviderParent): String {
        val json = toProviderParent(value)
        fromProviderParent(json)
        return HealthDocuments.encode(json)
    }

    @Throws(ProtocolException::class)
    fun decodeProviderParent(text: String): ProviderParent = fromProviderParent(document(text))

    internal fun toProviderParent(v: ProviderParent): Obj =
        fields(
            "provider" to Str(v.provider.name),
            "dataOrigin" to Str(v.dataOrigin),
            "providerId" to Str(v.providerId),
            "metric" to Str(v.metric),
            "originalStart" to Str(v.originalStart),
            "originalEnd" to Str(v.originalEnd),
            "nativeRevision" to v.nativeRevision?.let { Str(it) },
            "snapshotDigest" to Str(v.snapshotDigest),
            "memberCount" to Str(v.memberCount.toString()),
        )

    internal fun fromProviderParent(json: Json): ProviderParent {
        val o = json.obj()
        o.closed(
            "provider dataOrigin providerId metric originalStart originalEnd snapshotDigest memberCount",
            "nativeRevision",
        )
        val result =
            ProviderParent(
                fromProvider(o.required("provider")),
                o.required("dataOrigin").string(),
                o.required("providerId").string(),
                o.required("metric").string(),
                o.required("originalStart").string(),
                o.required("originalEnd").string(),
                o["nativeRevision"]?.let { it.string() },
                o.required("snapshotDigest").string(),
                decimal(o.required("memberCount")),
            )
        validate(result)
        return result
    }

    @Throws(ProtocolException::class)
    fun encodeProviderMember(value: ProviderMember): String {
        val json = toProviderMember(value)
        fromProviderMember(json)
        return HealthDocuments.encode(json)
    }

    @Throws(ProtocolException::class)
    fun decodeProviderMember(text: String): ProviderMember = fromProviderMember(document(text))

    internal fun toProviderMember(v: ProviderMember): Obj =
        fields(
            "parent" to toProviderParent(v.parent),
            "canonicalIndex" to Str(v.canonicalIndex.toString()),
            "occurrenceIndex" to Str(v.occurrenceIndex.toString()),
            "originalStart" to Str(v.originalStart),
            "originalEnd" to Str(v.originalEnd),
            "valueBits" to Str(v.valueBits),
            "unit" to Str(v.unit),
            "logicalKey" to Str(v.logicalKey),
            "eventId" to Str(v.eventId),
        )

    internal fun fromProviderMember(json: Json): ProviderMember {
        val o = json.obj()
        o.closed(
            "parent canonicalIndex occurrenceIndex originalStart originalEnd valueBits unit logicalKey eventId",
            "",
        )
        val result =
            ProviderMember(
                fromProviderParent(o.required("parent")),
                decimal(o.required("canonicalIndex")),
                decimal(o.required("occurrenceIndex")),
                o.required("originalStart").string(),
                o.required("originalEnd").string(),
                o.required("valueBits").string(),
                o.required("unit").string(),
                o.required("logicalKey").string(),
                o.required("eventId").string(),
            )
        validate(result)
        return result
    }

    internal fun toControllerMetric(v: ControllerMetric): Obj =
        fields(
            "contentVersion" to Str(v.contentVersion.toString()),
            "sourceRevision" to Str(v.sourceRevision.toString()),
            "content" to toControllerContent(v.content),
        )

    internal fun fromControllerMetric(json: Json): ControllerMetric {
        val o = json.obj()
        o.closed("contentVersion sourceRevision content", "")
        val result =
            ControllerMetric(
                decimal(o.required("contentVersion")),
                decimal(o.required("sourceRevision")),
                fromControllerContent(o.required("content")),
            )
        validate(result)
        return result
    }

    @Throws(ProtocolException::class)
    fun encodeControllerSecond(value: ControllerSecond): String {
        val json = toControllerSecond(value)
        fromControllerSecond(json)
        return HealthDocuments.encode(json)
    }

    @Throws(ProtocolException::class)
    fun decodeControllerSecond(text: String): ControllerSecond = fromControllerSecond(document(text))

    internal fun toControllerSecond(v: ControllerSecond): Obj =
        fields(
            "n" to Str(v.n.toString()),
            "endOffsetUs" to Str(v.endOffsetUs.toString()),
            "power" to toControllerMetric(v.power),
            "cadence" to toControllerMetric(v.cadence),
        )

    internal fun fromControllerSecond(json: Json): ControllerSecond {
        val o = json.obj()
        o.closed("n endOffsetUs power cadence", "")
        val result =
            ControllerSecond(
                decimal(o.required("n")),
                decimal(o.required("endOffsetUs")),
                fromControllerMetric(o.required("power")),
                fromControllerMetric(o.required("cadence")),
            )
        validate(result)
        return result
    }

    internal fun toRouteEncoding(v: RouteEncoding): Obj =
        fields(
            "providerUUID" to v.providerUUID?.let { Str(it) },
            "packageName" to v.packageName?.let { Str(it) },
            "appName" to v.appName?.let { Str(it) },
            "clientRecordId" to v.clientRecordId?.let { Str(it) },
            "manufacturer" to v.manufacturer?.let { Str(it) },
            "model" to v.model?.let { Str(it) },
            "notes" to v.notes?.let { Str(it) },
            "title" to v.title?.let { Str(it) },
            "plannedExerciseSessionId" to v.plannedExerciseSessionId?.let { Str(it) },
            "laps" to Num(v.laps.toDouble()),
            "pauses" to Num(v.pauses.toDouble()),
        )

    internal fun fromRouteEncoding(json: Json): RouteEncoding {
        val o = json.obj()
        o.closed(
            "laps pauses",
            "providerUUID packageName appName clientRecordId manufacturer model notes title plannedExerciseSessionId",
        )
        val result =
            RouteEncoding(
                o["providerUUID"]?.let { it.string() },
                o["packageName"]?.let { it.string() },
                o["appName"]?.let { it.string() },
                o["clientRecordId"]?.let { it.string() },
                o["manufacturer"]?.let { it.string() },
                o["model"]?.let { it.string() },
                o["notes"]?.let { it.string() },
                o["title"]?.let { it.string() },
                o["plannedExerciseSessionId"]?.let { it.string() },
                int32(o.required("laps")),
                int32(o.required("pauses")),
            )
        validate(result)
        return result
    }

    @Throws(ProtocolException::class)
    fun encodeSecondOutcome(value: SecondOutcome): String {
        val json = toSecondOutcome(value)
        fromSecondOutcome(json)
        return HealthDocuments.encode(json)
    }

    @Throws(ProtocolException::class)
    fun decodeSecondOutcome(text: String): SecondOutcome = fromSecondOutcome(document(text))

    internal fun toSecondOutcome(v: SecondOutcome): Obj =
        when (v) {
            is SecondOutcome.Sample -> fields("state" to Str("sample"), "sample" to toSecondValue(v.sample))
            is SecondOutcome.Absent -> fields("state" to Str("absent"), "reason" to Str(v.reason.name))
            is SecondOutcome.Invalid -> fields("state" to Str("invalid"), "reason" to Str(v.reason.name))
            is SecondOutcome.NumericOverflow ->
                fields(
                    "state" to Str("numericOverflow"),
                    "metric" to Str(v.metric.wire),
                    "quantity" to Str(v.quantity.name),
                )
        }

    internal fun fromSecondOutcome(json: Json): SecondOutcome {
        val o = json.obj()
        return when (o.required("state").string()) {
            "sample" -> {
                o.closed("state sample", "")
                SecondOutcome.Sample(fromSecondValue(o.required("sample")))
            }
            "absent" -> {
                o.closed("state reason", "")
                SecondOutcome.Absent(fromAbsence(o.required("reason")))
            }
            "invalid" -> {
                o.closed("state reason", "")
                SecondOutcome.Invalid(fromHealthReason(o.required("reason")))
            }
            "numericOverflow" -> {
                o.closed("state metric quantity", "")
                SecondOutcome.NumericOverflow(
                    fromHealthKind(o.required("metric")),
                    fromQuantity(o.required("quantity")),
                )
            }
            else -> invalid()
        }.also { validate(it) }
    }

    internal fun toRouteIntent(v: RouteIntent): Obj =
        when (v) {
            RouteIntent.NotRequested -> fields("state" to Str("notRequested"))
            RouteIntent.Preserve -> fields("state" to Str("preserve"))
            is RouteIntent.Replace ->
                fields(
                    "state" to Str("replace"),
                    "projectedOriginKeys" to Arr(v.projectedOriginKeys.map { Str(it) }),
                )
            RouteIntent.Remove -> fields("state" to Str("remove"))
        }

    internal fun fromRouteIntent(json: Json): RouteIntent {
        val o = json.obj()
        return when (o.required("state").string()) {
            "notRequested" -> {
                o.closed("state ", "")
                RouteIntent.NotRequested
            }
            "preserve" -> {
                o.closed("state ", "")
                RouteIntent.Preserve
            }
            "replace" -> {
                o.closed("state projectedOriginKeys", "")
                RouteIntent.Replace(o.required("projectedOriginKeys").array().map { it.string() })
            }
            "remove" -> {
                o.closed("state ", "")
                RouteIntent.Remove
            }
            else -> invalid()
        }.also { validate(it) }
    }

    @Throws(ProtocolException::class)
    fun encodeProviderContent(value: ProviderContent): String {
        val json = toProviderContent(value)
        fromProviderContent(json)
        return HealthDocuments.encode(json)
    }

    internal fun toProviderContent(v: ProviderContent): Obj =
        when (v) {
            is ProviderContent.Quantity -> fields("state" to Str("quantity"), "fields" to toQuantityContent(v.fields))
            is ProviderContent.Session -> fields("state" to Str("session"), "fields" to toSessionContent(v.fields))
            is ProviderContent.Route ->
                fields(
                    "state" to Str("route"),
                    "points" to Arr(v.points.map { toRoutePoint(it) }),
                    "metadata" to toPublicationMetadata(v.metadata),
                )
            is ProviderContent.Lap ->
                fields(
                    "state" to Str("lap"),
                    "eventId" to Str(v.eventId),
                    "timestamp" to Str(v.timestamp),
                    "metadata" to toPublicationMetadata(v.metadata),
                )
        }

    internal fun fromProviderContent(json: Json): ProviderContent {
        val o = json.obj()
        return when (o.required("state").string()) {
            "quantity" -> {
                o.closed("state fields", "")
                ProviderContent.Quantity(fromQuantityContent(o.required("fields")))
            }
            "session" -> {
                o.closed("state fields", "")
                ProviderContent.Session(fromSessionContent(o.required("fields")))
            }
            "route" -> {
                o.closed("state points metadata", "")
                ProviderContent.Route(
                    o.required("points").array().map { fromRoutePoint(it) },
                    fromPublicationMetadata(o.required("metadata")),
                )
            }
            "lap" -> {
                o.closed("state eventId timestamp metadata", "")
                ProviderContent.Lap(
                    o.required("eventId").string(),
                    o.required("timestamp").string(),
                    fromPublicationMetadata(o.required("metadata")),
                )
            }
            else -> invalid()
        }.also { validate(it) }
    }

    internal fun toAdapterProjection(v: AdapterProjection): Obj =
        when (v) {
            is AdapterProjection.Apple -> fields("state" to Str("apple"), "content" to toProviderContent(v.content))
            is AdapterProjection.HealthConnect ->
                fields(
                    "state" to Str("healthConnect"),
                    "content" to toProviderContent(v.content),
                    "startZoneOffsetSeconds" to Num(v.startZoneOffsetSeconds.toDouble()),
                    "endZoneOffsetSeconds" to Num(v.endZoneOffsetSeconds.toDouble()),
                    "routeIntent" to toRouteIntent(v.routeIntent),
                    "encodedBytes" to Str(v.encodedBytes.toString()),
                    "encoding" to toRouteEncoding(v.encoding),
                )
        }

    internal fun fromAdapterProjection(json: Json): AdapterProjection {
        val o = json.obj()
        return when (o.required("state").string()) {
            "apple" -> {
                o.closed("state content", "")
                AdapterProjection.Apple(fromProviderContent(o.required("content")))
            }
            "healthConnect" -> {
                o.closed(
                    "state content startZoneOffsetSeconds endZoneOffsetSeconds routeIntent encodedBytes encoding",
                    "",
                )
                AdapterProjection.HealthConnect(
                    fromProviderContent(o.required("content")),
                    int32(o.required("startZoneOffsetSeconds")),
                    int32(o.required("endZoneOffsetSeconds")),
                    fromRouteIntent(o.required("routeIntent")),
                    decimal(o.required("encodedBytes")),
                    fromRouteEncoding(o.required("encoding")),
                )
            }
            else -> invalid()
        }.also { validate(it) }
    }

    @Throws(ProtocolException::class)
    fun encodeNormalizedContent(value: NormalizedContent): String {
        val json = toNormalizedContent(value)
        fromNormalizedContent(json)
        return HealthDocuments.encode(json)
    }

    @Throws(ProtocolException::class)
    fun decodeNormalizedContent(text: String): NormalizedContent = fromNormalizedContent(document(text))

    internal fun toNormalizedContent(v: NormalizedContent): Obj =
        when (v) {
            is NormalizedContent.Present ->
                fields(
                    "state" to Str("present"),
                    "fields" to toProviderContent(v.fields),
                    "projection" to toAdapterProjection(v.projection),
                )
            is NormalizedContent.Absent -> fields("state" to Str("absent"), "reason" to Str(v.reason.name))
        }

    internal fun fromNormalizedContent(json: Json, trusted: Boolean = false): NormalizedContent {
        val o = json.obj()
        return when (o.required("state").string()) {
            "present" -> {
                o.closed("state fields projection", "")
                NormalizedContent.Present(
                    fromProviderContent(o.required("fields")),
                    fromAdapterProjection(o.required("projection")),
                )
            }
            "absent" -> {
                o.closed("state reason", "")
                NormalizedContent.Absent(fromAbsence(o.required("reason")))
            }
            else -> invalid()
        }.also { HealthValidation.validate(it, !trusted) }
    }

    @Throws(ProtocolException::class)
    fun encodeControllerContent(value: ControllerContent): String {
        val json = toControllerContent(value)
        fromControllerContent(json)
        return HealthDocuments.encode(json)
    }

    internal fun toControllerContent(v: ControllerContent): Obj =
        when (v) {
            is ControllerPresent -> fields("state" to Str("present"), "value" to Num(v.value))
            is ControllerAbsent -> fields("state" to Str("absent"), "reason" to Str(v.reason.name))
        }

    internal fun fromControllerContent(json: Json): ControllerContent {
        val o = json.obj()
        return when (o.required("state").string()) {
            "present" -> {
                o.closed("state value", "")
                ControllerPresent(o.required("value").number())
            }
            "absent" -> {
                o.closed("state reason", "")
                ControllerAbsent(fromControllerAbsence(o.required("reason")))
            }
            else -> invalid()
        }.also { validate(it) }
    }

    internal fun toScalar(v: Scalar): Obj =
        when (v) {
            is Scalar.Integer64 -> fields("state" to Str("integer64"), "value" to Str(v.value.toString()))
            is Scalar.Real64 ->
                fields(
                    "state" to Str("real64"),
                    "valueBits" to Str(v.value.toBits().toULong().toString(16).padStart(16, '0')),
                )
            is Scalar.Rational ->
                fields(
                    "state" to Str("rational"),
                    "numerator" to Str(v.numerator.toString()),
                    "denominator" to Str(v.denominator.toString()),
                )
        }

    internal fun fromScalar(json: Json): Scalar {
        val o = json.obj()
        return when (o.required("state").string()) {
            "integer64" -> {
                o.closed("state value", "")
                Scalar.Integer64(decimal(o.required("value")))
            }
            "real64" -> {
                o.closed("state valueBits", "")
                val bits = o.required("valueBits").string()
                checkValue(bits.length == 16 && bits.all { it in '0'..'9' || it in 'a'..'f' })
                Scalar.Real64(Double.fromBits(bits.toULong(16).toLong()))
            }
            "rational" -> {
                o.closed("state numerator denominator", "")
                Scalar.Rational(decimal(o.required("numerator")), decimal(o.required("denominator")))
            }
            else -> invalid()
        }.also { validate(it) }
    }

    @Throws(ProtocolException::class)
    fun encodePublicationBudget(value: PublicationBudget): String = HealthDocuments.encode(toPublicationBudget(value))

    @Throws(ProtocolException::class)
    fun decodePublicationBudget(text: String): PublicationBudget = fromPublicationBudget(document(text))

    private fun toPublicationBudget(v: PublicationBudget): Obj {
        validate(v)
        return fields(
            "provider" to Str(v.provider.name),
            "calls" to Arr(v.calls.map(::toBudgetCall)),
            "nextEligibleUs" to v.nextEligibleUs?.let { Str(it.toString()) },
        )
    }

    private fun fromPublicationBudget(json: Json): PublicationBudget {
        val o = json.obj()
        o.closed("provider calls", "nextEligibleUs")
        return PublicationBudget(
                fromProvider(o.required("provider")),
                o.required("calls").array().map(::fromBudgetCall),
                o["nextEligibleUs"]?.let(::decimal),
            )
            .also { validate(it) }
    }

    private fun toBudgetCall(v: BudgetCall): Obj {
        validate(v)
        return fields(
            "atUs" to Str(v.atUs.toString()),
            "bucket" to Str(v.bucket.name),
            "mode" to Str(v.mode.name),
            "bytes" to Str(v.bytes.toString()),
        )
    }

    private fun fromBudgetCall(json: Json): BudgetCall {
        val o = json.obj()
        o.closed("atUs bucket mode bytes", "")
        return BudgetCall(
                decimal(o.required("atUs")),
                CallBucket.entries.firstOrNull { it.name == o.required("bucket").string() } ?: invalid(),
                ExecutionMode.entries.firstOrNull { it.name == o.required("mode").string() } ?: invalid(),
                decimal(o.required("bytes")),
            )
            .also { validate(it) }
    }

    @Throws(ProtocolException::class)
    fun encodeMemberTuple(value: MemberTuple): String = HealthDocuments.encode(toMemberTuple(value))

    @Throws(ProtocolException::class) fun decodeMemberTuple(text: String): MemberTuple = fromMemberTuple(document(text))

    private fun toMemberTuple(v: MemberTuple): Obj {
        validate(v)
        return fields(
            "startUs" to Str(v.startUs.toString()),
            "startSubUsNs" to Num(v.startSubUsNs.toDouble()),
            "endUs" to Str(v.endUs.toString()),
            "endSubUsNs" to Num(v.endSubUsNs.toDouble()),
            "metric" to Str(v.metric),
            "unit" to Str(v.unit),
            "valueBits" to Str(v.valueBits),
        )
    }

    private fun fromMemberTuple(json: Json): MemberTuple {
        val o = json.obj()
        o.closed("startUs startSubUsNs endUs endSubUsNs metric unit valueBits", "")
        return MemberTuple(
                decimal(o.required("startUs")),
                int32(o.required("startSubUsNs")),
                decimal(o.required("endUs")),
                int32(o.required("endSubUsNs")),
                o.required("metric").string(),
                o.required("unit").string(),
                o.required("valueBits").string(),
            )
            .also { validate(it) }
    }

    @Throws(ProtocolException::class)
    fun encodeMemberCursor(value: MemberCursor): String = HealthDocuments.encode(toMemberCursor(value))

    @Throws(ProtocolException::class)
    fun decodeMemberCursor(text: String): MemberCursor = fromMemberCursor(document(text))

    private fun toMemberCursor(v: MemberCursor): Obj {
        validate(v)
        return fields(
            "snapshotDigest" to Str(v.snapshotDigest),
            "nextIndex" to Str(v.nextIndex.toString()),
            "previous" to v.previous?.let(::toMemberTuple),
            "nextOccurrence" to Str(v.nextOccurrence.toString()),
        )
    }

    private fun fromMemberCursor(json: Json): MemberCursor {
        val o = json.obj()
        o.closed("snapshotDigest nextIndex nextOccurrence", "previous")
        return MemberCursor(
                o.required("snapshotDigest").string(),
                decimal(o.required("nextIndex")),
                o["previous"]?.let(::fromMemberTuple),
                decimal(o.required("nextOccurrence")),
            )
            .also { validate(it) }
    }

    private fun validate(value: Any) = HealthValidation.validate(value)
}

internal object HealthDocuments {
    const val byteLimit = 64 * 1024 * 1024
    const val arrayLimit = 30000

    fun parse(text: String): Json =
        JsonCodec.parse(text, arrayLimit = arrayLimit, byteLimit = byteLimit, wireNumbers = false)

    fun encode(value: Json): String {
        val encoded = JsonCodec.encode(value)
        checkValue(parse(encoded) == value)
        return encoded
    }
}
