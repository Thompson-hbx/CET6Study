package com.example.cet6.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.example.cet6.domain.ServiceState

/** A Compose-observable copy of a service's state, kept in step synchronously, for screens that read ViewModel properties. */
internal fun <T> ServiceState<T>.composeState(): State<T> = mutableStateOf(value).also { mirror -> observe { mirror.value = it } }
