package com.continuousauth.registration;

import com.continuousauth.crypto.KeyManagementService;
import com.continuousauth.network.ApiService;
import com.continuousauth.utils.HmacKeyManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class DeviceRegistration_Factory implements Factory<DeviceRegistration> {
  private final Provider<ApiService> apiServiceProvider;

  private final Provider<KeyManagementService> keyManagementServiceProvider;

  private final Provider<HmacKeyManager> hmacKeyManagerProvider;

  public DeviceRegistration_Factory(Provider<ApiService> apiServiceProvider,
      Provider<KeyManagementService> keyManagementServiceProvider,
      Provider<HmacKeyManager> hmacKeyManagerProvider) {
    this.apiServiceProvider = apiServiceProvider;
    this.keyManagementServiceProvider = keyManagementServiceProvider;
    this.hmacKeyManagerProvider = hmacKeyManagerProvider;
  }

  @Override
  public DeviceRegistration get() {
    return newInstance(apiServiceProvider.get(), keyManagementServiceProvider.get(), hmacKeyManagerProvider.get());
  }

  public static DeviceRegistration_Factory create(Provider<ApiService> apiServiceProvider,
      Provider<KeyManagementService> keyManagementServiceProvider,
      Provider<HmacKeyManager> hmacKeyManagerProvider) {
    return new DeviceRegistration_Factory(apiServiceProvider, keyManagementServiceProvider, hmacKeyManagerProvider);
  }

  public static DeviceRegistration newInstance(ApiService apiService,
      KeyManagementService keyManagementService, HmacKeyManager hmacKeyManager) {
    return new DeviceRegistration(apiService, keyManagementService, hmacKeyManager);
  }
}
