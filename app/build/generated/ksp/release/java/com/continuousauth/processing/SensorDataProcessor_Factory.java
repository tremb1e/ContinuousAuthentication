package com.continuousauth.processing;

import com.continuousauth.chunking.ChunkingManager;
import com.continuousauth.compression.CompressionManager;
import com.continuousauth.crypto.AADBuilder;
import com.continuousauth.crypto.CryptoBox;
import com.continuousauth.crypto.EnvelopeCryptoBox;
import com.continuousauth.data.DataPacketBuilder;
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
public final class SensorDataProcessor_Factory implements Factory<SensorDataProcessor> {
  private final Provider<CryptoBox> cryptoBoxProvider;

  private final Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider;

  private final Provider<AADBuilder> aadBuilderProvider;

  private final Provider<DataPacketBuilder> dataPacketBuilderProvider;

  private final Provider<CompressionManager> compressionManagerProvider;

  private final Provider<ChunkingManager> chunkingManagerProvider;

  public SensorDataProcessor_Factory(Provider<CryptoBox> cryptoBoxProvider,
      Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider,
      Provider<AADBuilder> aadBuilderProvider,
      Provider<DataPacketBuilder> dataPacketBuilderProvider,
      Provider<CompressionManager> compressionManagerProvider,
      Provider<ChunkingManager> chunkingManagerProvider) {
    this.cryptoBoxProvider = cryptoBoxProvider;
    this.envelopeCryptoBoxProvider = envelopeCryptoBoxProvider;
    this.aadBuilderProvider = aadBuilderProvider;
    this.dataPacketBuilderProvider = dataPacketBuilderProvider;
    this.compressionManagerProvider = compressionManagerProvider;
    this.chunkingManagerProvider = chunkingManagerProvider;
  }

  @Override
  public SensorDataProcessor get() {
    return newInstance(cryptoBoxProvider.get(), envelopeCryptoBoxProvider.get(), aadBuilderProvider.get(), dataPacketBuilderProvider.get(), compressionManagerProvider.get(), chunkingManagerProvider.get());
  }

  public static SensorDataProcessor_Factory create(Provider<CryptoBox> cryptoBoxProvider,
      Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider,
      Provider<AADBuilder> aadBuilderProvider,
      Provider<DataPacketBuilder> dataPacketBuilderProvider,
      Provider<CompressionManager> compressionManagerProvider,
      Provider<ChunkingManager> chunkingManagerProvider) {
    return new SensorDataProcessor_Factory(cryptoBoxProvider, envelopeCryptoBoxProvider, aadBuilderProvider, dataPacketBuilderProvider, compressionManagerProvider, chunkingManagerProvider);
  }

  public static SensorDataProcessor newInstance(CryptoBox cryptoBox,
      EnvelopeCryptoBox envelopeCryptoBox, AADBuilder aadBuilder,
      DataPacketBuilder dataPacketBuilder, CompressionManager compressionManager,
      ChunkingManager chunkingManager) {
    return new SensorDataProcessor(cryptoBox, envelopeCryptoBox, aadBuilder, dataPacketBuilder, compressionManager, chunkingManager);
  }
}
