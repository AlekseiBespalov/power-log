package app.powerlog.runtime.web.files

import app.powerlog.application.port.PickedFile
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebFilePickerTest {
    private class Page {
        val input: dynamic = js("({})")
        val inputListeners = mutableMapOf<String, (dynamic) -> Unit>()
        val windowListeners = mutableMapOf<String, (dynamic) -> Unit>()
        val timers = mutableListOf<() -> Unit>()
        var clicks = 0
        var created: String? = null

        val document: PickerDocument
        val window: PickerWindow

        init {
            input.addEventListener = { type: String, listener: (dynamic) -> Unit -> inputListeners[type] = listener }
            input.click = { clicks++ }
            input.files = null
            val document: dynamic = js("({})")
            document.createElement = { name: String ->
                created = name
                input
            }
            this.document = document.unsafeCast<PickerDocument>()
            val window: dynamic = js("({})")
            window.addEventListener = { type: String, listener: (dynamic) -> Unit -> windowListeners[type] = listener }
            window.removeEventListener = { type: String, listener: (dynamic) -> Unit ->
                if (windowListeners[type] === listener) windowListeners.remove(type)
            }
            window.setTimeout = { handler: () -> Unit, _: Int ->
                timers += handler
                timers.size
            }
            this.window = window.unsafeCast<PickerWindow>()
        }

        fun choose(name: String, bytes: Int) {
            val file: dynamic = js("({})")
            file.name = name
            file.size = bytes
            file.stream = { js("({ getReader: function () { return {}; } })") }
            val files: dynamic = js("({})")
            files.item = { _: Int -> file }
            input.files = files
        }

        fun fire(type: String) = inputListeners.getValue(type)(null)

        fun focus() = windowListeners["focus"]?.invoke(null)

        fun runTimers() {
            val due = timers.toList()
            timers.clear()
            due.forEach { it() }
        }
    }

    private class Answers {
        val answers = mutableListOf<Result<PickedFile>>()
        val reply =
            Reply<PickedFile>(
                onOk = { answers += Result.success(it) },
                onFailed = { error: PortError -> answers += Result.failure(Refused(error.kind)) },
            )

        fun kind(): PortErrorKind? = (answers.single().exceptionOrNull() as? Refused)?.kind
    }

    private class Refused(val kind: PortErrorKind) : Exception()

    private val now: (() -> Unit) -> Unit = { it() }

    @Test
    fun aChosenFileAnswersItsNameAndSize() {
        val page = Page()
        val answers = Answers()
        WebFilePicker(page.document, page.window, { true }, now).pickCsv(answers.reply)
        assertEquals("input", page.created)
        assertEquals("file", page.input.type)
        assertEquals(".csv,text/csv", page.input.accept)
        assertEquals(1, page.clicks)
        page.choose("ride.csv", 42)
        page.focus()
        page.fire("change")
        page.runTimers()
        val file = answers.answers.single().getOrThrow()
        assertEquals("ride.csv", file.name)
        assertEquals(42L, file.sizeBytes)
        assertNull(page.windowListeners["focus"])
    }

    @Test
    fun theCancelEventAnswersCancelled() {
        val page = Page()
        val answers = Answers()
        WebFilePicker(page.document, page.window, { true }, now).pickCsv(answers.reply)
        page.fire("cancel")
        assertEquals(PortErrorKind.cancelled, answers.kind())
        assertNull(page.windowListeners["focus"])
    }

    @Test
    fun aDismissedPickerWithoutTheCancelEventAnswersCancelledOnceFocusReturns() {
        val page = Page()
        val answers = Answers()
        WebFilePicker(page.document, page.window, { true }, now).pickCsv(answers.reply)
        page.focus()
        assertTrue(answers.answers.isEmpty())
        page.runTimers()
        assertEquals(PortErrorKind.cancelled, answers.kind())
        page.choose("late.csv", 1)
        page.fire("change")
        assertEquals(1, answers.answers.size)
    }

    @Test
    fun aFileChosenWithinTheGraceAfterFocusIsTheAnswer() {
        val page = Page()
        val answers = Answers()
        WebFilePicker(page.document, page.window, { true }, now).pickCsv(answers.reply)
        page.focus()
        page.choose("ride.csv", 7)
        page.runTimers()
        page.fire("change")
        assertEquals("ride.csv", answers.answers.single().getOrThrow().name)
    }

    @Test
    fun withoutUserActivationOrADocumentThePickerDoesNotOpen() {
        val page = Page()
        val inactive = Answers()
        WebFilePicker(page.document, page.window, { false }, now).pickCsv(inactive.reply)
        assertEquals(PortErrorKind.noUserActivation, inactive.kind())
        val headless = Answers()
        WebFilePicker(null, null, { true }, now).pickCsv(headless.reply)
        assertEquals(PortErrorKind.uiUnavailable, headless.kind())
        assertEquals(0, page.clicks)
    }
}
