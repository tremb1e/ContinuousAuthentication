package com.continuousauth.network;

import com.continuousauth.policy.PolicyManager;
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
public final class UploaderImpl_Factory implements Factory<UploaderImpl> {
  private final Provider<TlsSecurityManager> tlsSecurityManagerProvider;

  private final Provider<PolicyManager> policyManagerProvider;

  public UploaderImpl_Factory(Provider<TlsSecurityManager> tlsSecurityManagerProvider,
      Provider<PolicyManager> policyManagerProvider) {
    this.tlsSecurityManagerProvider = tlsSecurityManagerProvider;
    this.policyManagerProvider = policyManagerProvider;
  }

  @Override
  public UploaderImpl get() {
    return newInstance(tlsSecurityManagerProvider.get(), policyManagerProvider.get());
  }

  public static UploaderImpl_Factory create(Provider<TlsSecurityManager> tlsSecurityManagerProvider,
      Provider<PolicyManager> policyManagerProvider) {
    return new UploaderImpl_Factory(tlsSecurityManagerProvider, policyManagerProvider);
  }

  public static UploaderImpl newInstance(TlsSecurityManager tlsSecurityManager,
      PolicyManager policyManager) {
    return new UploaderImpl(tlsSecurityManager, policyManager);
  }
}
