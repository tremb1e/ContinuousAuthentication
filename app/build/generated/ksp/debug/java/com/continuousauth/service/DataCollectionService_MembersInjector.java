package com.continuousauth.service;

import com.continuousauth.core.SmartTransmissionManager;
import com.continuousauth.privacy.PrivacyManager;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class DataCollectionService_MembersInjector implements MembersInjector<DataCollectionService> {
  private final Provider<PrivacyManager> privacyManagerProvider;

  private final Provider<SmartTransmissionManager> smartTransmissionManagerProvider;

  public DataCollectionService_MembersInjector(Provider<PrivacyManager> privacyManagerProvider,
      Provider<SmartTransmissionManager> smartTransmissionManagerProvider) {
    this.privacyManagerProvider = privacyManagerProvider;
    this.smartTransmissionManagerProvider = smartTransmissionManagerProvider;
  }

  public static MembersInjector<DataCollectionService> create(
      Provider<PrivacyManager> privacyManagerProvider,
      Provider<SmartTransmissionManager> smartTransmissionManagerProvider) {
    return new DataCollectionService_MembersInjector(privacyManagerProvider, smartTransmissionManagerProvider);
  }

  @Override
  public void injectMembers(DataCollectionService instance) {
    injectPrivacyManager(instance, privacyManagerProvider.get());
    injectSmartTransmissionManager(instance, smartTransmissionManagerProvider.get());
  }

  @InjectedFieldSignature("com.continuousauth.service.DataCollectionService.privacyManager")
  public static void injectPrivacyManager(DataCollectionService instance,
      PrivacyManager privacyManager) {
    instance.privacyManager = privacyManager;
  }

  @InjectedFieldSignature("com.continuousauth.service.DataCollectionService.smartTransmissionManager")
  public static void injectSmartTransmissionManager(DataCollectionService instance,
      SmartTransmissionManager smartTransmissionManager) {
    instance.smartTransmissionManager = smartTransmissionManager;
  }
}
