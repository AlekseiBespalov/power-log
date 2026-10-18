package app.powerlog.core.health

import kotlin.test.Test
import kotlin.test.assertTrue

class ProviderNativeContractTest {
    @Test
    fun parentAdmissionAndComparisonDeclareCatchableFailures() {
        for (name in listOf("sameSnapshot", "admitRevision")) {
            val method = ProviderMembers::class.java.declaredMethods.single { it.name == name }
            assertTrue(IllegalArgumentException::class.java in method.exceptionTypes, name)
            assertTrue(ArithmeticException::class.java in method.exceptionTypes, name)
            assertTrue(app.powerlog.core.protocol.ProtocolException::class.java in method.exceptionTypes, name)
        }
    }
}
