package com.continuousauth.monitor;

import android.content.Context;
import com.continuousauth.crypto.EnvelopeCryptoBox;
import com.continuousauth.network.Uploader;
import com.continuousauth.storage.FileQueueManager;
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
public final class SystemMonitor_Factory implements Factory<SystemMonitor> {
  private final Provider<Context> contextProvider;

  private final Provider<Uploader> uploaderProvider;

  private final Provider<FileQueueManager> fileQueueManagerProvider;

  private final Provider<EnvelopeCryptoBox> cryptoBoxProvider;

  private final Provider<EnhancedTimeSync> enhancedTimeSyncProvider;

  public SystemMonitor_Factory(Provider<Context> contextProvider,
      Provider<Uploader> uploaderProvider, Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<EnvelopeCryptoBox> cryptoBoxProvider,
      Provider<EnhancedTimeSync> enhancedTimeSyncProvider) {
    this.contextProvider = contextProvider;
    this.uploaderProvider = uploaderProvider;
    this.fileQueueManagerProvider = fileQueueManagerProvider;
    this.cryptoBoxProvider = cryptoBoxProvider;
    this.enhancedTimeSyncProvider = enhancedTimeSyncProvider;
  }

  @Override
  public SystemMonitor get() {
    return newInstance(contextProvider.get(), uploaderProvider.get(), fileQueueManagerProvider.get(), cryptoBoxProvider.get(), enhancedTimeSyncProvider.get());
  }

  public static SystemMonitor_Factory create(Provider<Context> contextProvider,
      Provider<Uploader> uploaderProvider, Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<EnvelopeCryptoBox> cryptoBoxProvider,
      Provider<EnhancedTimeSync> enhancedTimeSyncProvider) {
    return new SystemMonitor_Factory(contextProvider, uploaderProvider, fileQueueManagerProvider, cryptoBoxProvider, enhancedTimeSyncProvider);
  }

  public static SystemMonitor newInstance(Context context, Uploader uploader,
      FileQueueManager fileQueueManager, EnvelopeCryptoBox cryptoBox,
      EnhancedTimeSync enhancedTimeSync) {
    return new SystemMonitor(context, uploader, fileQueueManager, cryptoBox, enhancedTimeSync);
  }
}
