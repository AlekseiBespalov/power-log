package app.powerlog.application.connection

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.ApiStream
import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.domain.connection.ConnectionDisplay
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.failure.Availability
import app.powerlog.domain.core.ids.BikeId
import kotlinx.serialization.Serializable

public interface ConnectionApi {
    public val state: ApiStream<Empty, ConnectionView>

    public suspend fun startSearch(input: Empty): Outcome<Unit>

    public suspend fun stopSearch(input: Empty): Outcome<Unit>

    public suspend fun connect(input: ConnectInput): Outcome<Unit>

    public suspend fun disconnect(input: Empty): Outcome<Unit>
}

@Serializable
public data class ConnectionView(
    public val state: ConnectionState,
    public val display: ConnectionDisplay,
    public val heldUntilMonoUs: Long?,
    public val bike: BikeView?,
    public val found: List<FoundBikeView>,
    public val faultCode: Int?,
    public val failure: ApiFailure?,
    public val allowed: ConnectionAllowed,
)

@Serializable
public data class BikeView(
    public val bikeId: BikeId,
    public val advertisedName: String?,
    public val identity: ControllerIdentity?,
    public val controllerSpeed: Boolean?,
)

@Serializable
public data class FoundBikeView(
    public val bikeId: BikeId,
    public val advertisedName: String?,
    public val known: ControllerIdentity?,
)

@Serializable
public data class ConnectionAllowed(
    public val startSearch: Availability,
    public val stopSearch: Availability,
    public val connect: Availability,
    public val disconnect: Availability,
)

@Serializable public data class ConnectInput(public val bikeId: BikeId)
