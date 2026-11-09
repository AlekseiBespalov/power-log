package app.powerlog.domain.core.ids

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable @JvmInline public value class RideId(public val value: String)

@Serializable @JvmInline public value class EventId(public val value: String)

@Serializable @JvmInline public value class BikeId(public val value: String)
