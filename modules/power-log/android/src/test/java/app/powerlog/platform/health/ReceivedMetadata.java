package app.powerlog.platform.health;

import androidx.health.connect.client.records.metadata.DataOrigin;
import androidx.health.connect.client.records.metadata.Device;
import androidx.health.connect.client.records.metadata.Metadata;
import java.time.Instant;

final class ReceivedMetadata {
    private ReceivedMetadata() {}

    static Metadata of(String id, String origin, String clientId) {
        return new Metadata(
                Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                id,
                new DataOrigin(origin),
                Instant.EPOCH,
                clientId,
                0,
                new Device(Device.TYPE_WATCH, null, null));
    }
}
