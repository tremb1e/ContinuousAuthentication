package com.continuousauth.sensor

import com.continuousauth.model.SensorSample
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** The processor owns the queue; optional UI observers receive independent copies. */
internal class SensorSampleStreams {
    private var processingQueue = Channel<SensorSample>(Channel.UNLIMITED)
    private var processingFinished = false
    private val preview = MutableSharedFlow<SensorSample>(
        replay = 3,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val processingFlow: Flow<SensorSample>
        @Synchronized get() {
            if (processingFinished) {
                processingQueue = Channel(Channel.UNLIMITED)
                processingFinished = false
            }
            return processingQueue.receiveAsFlow()
        }
    val previewFlow: Flow<SensorSample> = preview.asSharedFlow()

    @Synchronized
    fun publish(sample: SensorSample) {
        check(processingQueue.trySend(sample).isSuccess)
        preview.tryEmit(sample)
    }

    @Synchronized
    fun finishProcessing() {
        processingFinished = true
        processingQueue.close()
    }
}
