package com.alijahroun.screenrecorder

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide state shared between [RecorderService] and the UI. */
object RecorderStatus {
    enum class Phase { IDLE, RECORDING, PAUSED, SAVING }

    val phase = MutableStateFlow(Phase.IDLE)

    /** One-off user-visible messages (errors, warnings, "saved"). */
    val events = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}
