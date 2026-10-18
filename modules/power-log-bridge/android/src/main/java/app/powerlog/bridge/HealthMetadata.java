package app.powerlog.bridge;

import androidx.health.connect.client.records.metadata.DataOrigin;
import androidx.health.connect.client.records.metadata.Device;
import androidx.health.connect.client.records.metadata.Metadata;
import java.time.Instant;

// Metadata's Kotlin factories cannot combine a provider ID with a client ID and version; its constructor can.
final class HealthMetadata {
    private HealthMetadata() {}

    static Metadata received(
            String id, String clientId, long version, Device device, String origin, Instant modified, int method) {
        return new Metadata(method, id, new DataOrigin(origin), modified, clientId, version, device);
    }

    static Metadata create(String id, String clientId, long version, Device device) {
        return new Metadata(
                Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                id,
                new DataOrigin(""),
                Instant.EPOCH,
                clientId,
                version,
                device);
    }
}
