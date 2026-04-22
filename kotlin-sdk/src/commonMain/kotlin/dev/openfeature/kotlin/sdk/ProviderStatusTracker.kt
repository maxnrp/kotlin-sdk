package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single place for [OpenFeatureStatus] and the provider event stream: call [send] for each lifecycle
 * step; [status] is derived with the same [toOpenFeatureStatus] rules as the rest of the SDK. Do not
 * set readiness from another [StateFlow] outside [send]. For [StateManagingProvider], expose
 * [status] and [observe] from the tracker, or use [OpenFeatureAPI.statusFlow] for readiness if registered.
 *
 * [observe] is a [MutableSharedFlow] with `replay = 1`. New subscribers get the most recent [send] (if
 * any), then all later [send] calls. The event flow does not conflate by [equals] (unlike [status]);
 * for readiness, prefer [status] or `OpenFeatureAPI.statusFlow` when the replay is not a lifecycle
 * event (e.g. `ProviderConfigurationChanged` was last on the bus).
 *
 * [send] uses [MutableSharedFlow.tryEmit], which is non-suspending and can return `false` if the
 * internal buffer (including `extraBufferCapacity`) is full and collectors are not keeping up—rare
 * for typical provider lifecycle rates. Rely on [status] for authoritative readiness if you are
 * unsure. [send] and [synchronized] (atomicfu) keep [OpenFeatureStatus] and emissions ordered; do not
 * call [send] re-entrantly from [observe] collection.
 */
class ProviderStatusTracker {
    private val providerMutex = SynchronizedObject()
    private val _status = MutableStateFlow<OpenFeatureStatus>(OpenFeatureStatus.NotReady)
    private val _events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 256)

    val status: StateFlow<OpenFeatureStatus> = _status.asStateFlow()

    fun send(event: OpenFeatureProviderEvents) {
        synchronized(providerMutex) {
            event.toOpenFeatureStatus()?.let { _status.value = it }
            _events.tryEmit(event)
        }
    }

    fun observe(): Flow<OpenFeatureProviderEvents> = _events.asSharedFlow()
}