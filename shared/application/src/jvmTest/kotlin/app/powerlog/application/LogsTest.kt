package app.powerlog.application

import app.powerlog.application.store.FilePragmas
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StoreFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogsTest {
    @Test
    fun eachFilesPragmasCarryWalAutocheckpoint() {
        val pragmas = Logs.pragmas(listOf(FilePragmas(StoreFile.archive, 2, "wal", 2, 100)))
        assertEquals(
            LogValue.Fields(
                listOf(
                    LogField(
                        "archive",
                        LogValue.Fields(
                            listOf(
                                LogField("synchronous", LogValue.Integer(2)),
                                LogField("journalMode", LogValue.Text("wal")),
                                LogField("autoVacuum", LogValue.Integer(2)),
                                LogField("walAutocheckpoint", LogValue.Integer(100)),
                            )
                        ),
                    )
                )
            ),
            pragmas,
        )
    }

    @Test
    fun aFileIsHealthyOnlyWithWalAutocheckpointAtOneThousand() {
        assertTrue(Logs.healthy(listOf(FilePragmas(StoreFile.archive, 2, "wal", 2, 1_000))))
        assertFalse(Logs.healthy(listOf(FilePragmas(StoreFile.archive, 2, "wal", 2, 100))))
    }
}
