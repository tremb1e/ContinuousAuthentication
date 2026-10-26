package com.continuousauth.testing

import com.continuousauth.auth.AuthenticationManager
import com.continuousauth.auth.AuthHistoryRepository
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.settings.AppSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access for resetting the disposable UI-test device between real Activity tests. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UiRegressionEntryPoint {
    fun privacyManager(): PrivacyManager
    fun settingsRepository(): AppSettingsRepository
    fun authenticationManager(): AuthenticationManager
    fun historyRepository(): AuthHistoryRepository
}
