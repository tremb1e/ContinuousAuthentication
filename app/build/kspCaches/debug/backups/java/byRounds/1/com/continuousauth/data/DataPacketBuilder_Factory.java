package com.continuousauth.data;

import android.content.Context;
import com.continuousauth.crypto.EnvelopeCryptoBox;
import com.continuousauth.time.EnhancedTimeSync;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class DataPacketBuilder_Factory implements Factory<DataPacketBuilder> {
  private final Provider<Context> contextProvider;

  private final Provider<EnhancedTimeSync> enhancedTimeSyncProvider;

  private final Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider;

  public DataPacketBuilder_Factory(Provider<Context> contextProvider,
      Provider<EnhancedTimeSync> enhancedTimeSyncProvider,
      Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider) {
    this.contextProvider = contextProvider;
    this.enhancedTimeSyncProvider = enhancedTimeSyncProvider;
    this.envelopeCryptoBoxProvider = envelopeCryptoBoxProvider;
  }

  @Override
  public DataPacketBuilder get() {
    return newInstance(contextProvider.get(), enhancedTimeSyncProvider.get(), envelopeCryptoBoxProvider.get());
  }

  public static DataPacketBuilder_Factory create(Provider<Context> contextProvider,
      Provider<EnhancedTimeSync> enhancedTimeSyncProvider,
      Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider) {
    return new DataPacketBuilder_Factory(contextProvider, enhancedTimeSyncProvider, envelopeCryptoBoxProvider);
  }

  public static DataPacketBuilder newInstance(Context context, EnhancedTimeSync enhancedTimeSync,
      EnvelopeCryptoBox envelopeCryptoBox) {
    return new DataPacketBuilder(context, enhancedTimeSync, envelopeCryptoBox);
  }
}
