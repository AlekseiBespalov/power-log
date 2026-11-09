package app.powerlog.application.api

import app.powerlog.application.store.StorageError
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal fun failure(code: FailureCode, detail: RefusalDetail? = null): ApiFailure =
    ApiFailure(code, code.retryable, null, detail)

@OptIn(ExperimentalUuidApi::class) internal fun newId(): String = Uuid.random().toString()

internal fun occurrence(code: FailureCode, previous: ApiFailure?, detail: RefusalDetail? = null): ApiFailure =
    if (previous != null && previous.code == code && previous.detail == detail) previous
    else ApiFailure(code, code.retryable, newId(), detail)

internal fun commitFailure(error: StorageError): ApiFailure =
    when (error.kind) {
        StorageError.Kind.busy -> failure(FailureCode.storageBusy)
        StorageError.Kind.full -> failure(FailureCode.storageFull)
        StorageError.Kind.corrupt -> failure(FailureCode.storageRefused, RefusalDetail.unreadable)
        StorageError.Kind.io,
        StorageError.Kind.constraint,
        StorageError.Kind.closed -> failure(FailureCode.storageFailed)
    }

internal fun readFailure(error: StorageError): ApiFailure =
    when (error.kind) {
        StorageError.Kind.busy -> failure(FailureCode.storageBusy)
        StorageError.Kind.corrupt -> failure(FailureCode.storageRefused, RefusalDetail.unreadable)
        else -> failure(FailureCode.readFailed)
    }

internal class ApiFailureException(val failure: ApiFailure) : Exception(failure.code.name)
