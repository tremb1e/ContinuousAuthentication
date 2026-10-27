package com.continuousauth.testing

import com.continuousauth.network.Uploader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Only the debug target exposes its uploader to the opt-in, read-only device probe. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HeartbeatEntryPoint { fun uploader(): Uploader }
