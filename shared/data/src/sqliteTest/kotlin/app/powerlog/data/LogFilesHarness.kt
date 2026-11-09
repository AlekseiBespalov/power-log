package app.powerlog.data

import app.powerlog.application.store.DiagnosticLogHarness
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogUnderTest
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope

internal class LogFilesHarness(
    private val platform: SqlTestPlatform,
    private val logFiles: (directory: String) -> LogFiles,
) : DiagnosticLogHarness {
    override val logRotateBytes: Int = StorePolicy.logRotateBytes

    override suspend fun create(scope: TestScope): LogUnderTest {
        val directory = platform.newDirectory()
        val executors = platform.executors()
        scope.backgroundScope.coroutineContext.job.invokeOnCompletion {
            executors.close()
            platform.delete(directory)
        }
        return Log(directory, executors.store)
    }

    private inner class Log(private val directory: String, private val executors: StoreExecutors) : LogUnderTest {
        override val log: DiagnosticLogRepository = DiagnosticLogFiles(logFiles(directory), executors.log)

        override suspend fun files(): Map<String, ByteArray> = platform.files(directory)

        override suspend fun writeFile(name: String, bytes: ByteArray) {
            platform.write("$directory/$name", bytes)
        }

        override suspend fun reopen(): LogUnderTest = Log(directory, executors)
    }
}
