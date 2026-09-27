package app.powerlog.bridge

import expo.modules.kotlin.Promise
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WorkoutPermissionsTest {
    @Test
    fun invalidOptionsRejectBeforeAccessingThePermissionContextOrStarting() {
        val module = CycBridgeModule()
        val request =
            CycBridgeModule::class
                .java
                .getDeclaredMethod(
                    "ridePermissions",
                    Map::class.java,
                    Promise::class.java,
                    Function1::class.java,
                )
                .apply { isAccessible = true }
        val invalid =
            rejectedRideOptions() +
                listOf("useWatch", "saveToHealth", "recordGPS").flatMap { field ->
                    listOf(0, "false", emptyList<Any>(), emptyMap<String, Any>()).map {
                        field to mapOf("indoor" to false, field to it)
                    }
                }
        for ((field, input) in invalid) {
            var rejected = false
            val promise =
                object : Promise {
                    override fun resolve(value: Any?) {
                        fail("Invalid options were accepted")
                    }

                    override fun reject(code: String?, message: String?, cause: Throwable?) {
                        rejected = true
                        assertEquals("E_PERMISSIONS", code)
                        assertTrue(message.orEmpty().contains(field))
                        assertTrue(cause is IllegalArgumentException)
                    }
                }
            request.invoke(module, input, promise, { _: RideOptions -> fail("Permission continuation ran") })
            assertTrue(rejected)
        }
    }

    @Test
    fun malformedConnectRejectsBeforeAccessingTheEngineOrBluetoothContext() {
        val module = CycBridgeModule()
        val connect =
            CycBridgeModule::class.java.getDeclaredMethod("connect", Map::class.java, Promise::class.java).apply {
                isAccessible = true
            }
        val invalid =
            rejectedRideOptions()
                .filter { it.first == "sampleHz" }
                .map {
                    "hz" to mapOf("deviceId" to "bike", "hz" to it.second["sampleHz"])
                } +
                listOf(null, "", " ", true, 42).map { "deviceId" to mapOf("deviceId" to it, "hz" to 4) } +
                listOf("deviceId" to emptyMap())
        for ((field, input) in invalid) {
            var rejected = false
            val promise =
                object : Promise {
                    override fun resolve(value: Any?) {
                        fail("Invalid connect was accepted")
                    }

                    override fun reject(code: String?, message: String?, cause: Throwable?) {
                        rejected = true
                        assertEquals("E_BLUETOOTH", code)
                        assertTrue(message.orEmpty().contains(field))
                        assertTrue(cause is IllegalArgumentException)
                    }
                }
            connect.invoke(module, input, promise)
            assertTrue(rejected)
        }
    }

    @Test
    fun malformedCatalogAndMonitorRequestsRejectBeforeOpeningTheStore() {
        val module = CycBridgeModule()
        val read =
            CycBridgeModule::class
                .java
                .getDeclaredMethod(
                    "read",
                    Map::class.java,
                    Promise::class.java,
                    Function1::class.java,
                    Function1::class.java,
                )
                .apply { isAccessible = true }
        val cases =
            listOf<Pair<Payload, (Payload) -> Any>>(
                mapOf("limit" to 1.5) to { BridgeInputs.catalog(it) },
                mapOf("beforeID" to "ride") to { BridgeInputs.catalog(it) },
                mapOf("source" to "unknown", "generation" to 0) to
                    {
                        BridgeInputs.monitor(MonitorOperation.Describe, it)
                    },
                mapOf(
                    "source" to "workout",
                    "id" to "ride",
                    "generation" to 0,
                    "metrics" to listOf("humanPowerW", 1),
                ) to { BridgeInputs.monitor(MonitorOperation.Latest, it) },
            )
        for ((input, parse) in cases) {
            var rejected = false
            val promise =
                object : Promise {
                    override fun resolve(value: Any?) {
                        fail("Invalid read was accepted")
                    }

                    override fun reject(code: String?, message: String?, cause: Throwable?) {
                        rejected = true
                        assertEquals("E_POWER_LOG", code)
                        assertTrue(cause is IllegalArgumentException)
                    }
                }
            read.invoke(module, input, promise, parse, { _: Any -> fail("Read continuation ran") })
            assertTrue(rejected)
        }
    }
}
