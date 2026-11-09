package app.powerlog.application.slice

import app.powerlog.application.fakes.FakeStoragePersistence
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.StoragePersistence
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.settings.PersistenceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoragePersistenceScenarioTest {
    @Test
    fun aWebLaunchShowsWhetherTheBrowserKeepsTheStoreAndEachForegroundReadsItAgain() {
        val scenario = Scenario(Platform.web)
        val storage = FakeStoragePersistence(scenario.post)
        scenario.persistence = storage
        scenario.test {
            scenario.launch()
            runCurrent()
            val info = scenario.api.device.info
            assertEquals(PersistenceState.notPersisted, info.until("device") { it.storage != null }.storage)
            storage.state = PersistenceState.persisted
            scenario.application.host.foregroundChanged(true)
            runCurrent()
            assertEquals(
                PersistenceState.persisted,
                info.until("device") { it.storage == PersistenceState.persisted }.storage,
            )
        }
    }

    @Test
    fun aFailedReadShowsUnsupported() {
        val scenario = Scenario(Platform.web)
        scenario.persistence =
            object : StoragePersistence {
                override fun state(reply: Reply<PersistenceState>) {
                    scenario.post { reply.failed(PortError(PortErrorKind.failed)) }
                }

                override fun request(reply: Reply<PersistenceState>) = state(reply)
            }
        scenario.test {
            scenario.launch()
            runCurrent()
            assertEquals(
                PersistenceState.unsupported,
                scenario.api.device.info.until("device") { it.storage != null }.storage,
            )
        }
    }

    @Test
    fun withoutThePortDeviceInfoHasNoStorageState() {
        val scenario = Scenario()
        scenario.test {
            scenario.launch()
            runCurrent()
            assertNull(scenario.api.device.info.until("device") { it.capabilities.recording }.storage)
        }
    }
}
