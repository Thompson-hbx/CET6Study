package com.example.cet6.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state of one service. New screens collect [flow]; screens that still read ViewModel
 * properties register an [observe] listener, which sees every update synchronously so those reads
 * never lag behind the service. Transforms must be pure: they run under the lock.
 */
class ServiceState<T>(initial: T) {
    private val mutable = MutableStateFlow(initial)
    private var listener: ((T) -> Unit)? = null
    val flow: StateFlow<T> = mutable.asStateFlow()
    val value: T get() = mutable.value

    fun update(transform: (T) -> T): T = synchronized(this) {
        transform(mutable.value).also { mutable.value = it; listener?.invoke(it) }
    }

    fun observe(listener: (T) -> Unit) = synchronized(this) {
        this.listener = listener
        listener(mutable.value)
    }
}
