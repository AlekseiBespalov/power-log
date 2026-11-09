package app.powerlog.runtime.compose

import kotlinx.coroutines.CoroutineDispatcher

internal interface RideBound {
    fun bind(ride: CoroutineDispatcher)
}
