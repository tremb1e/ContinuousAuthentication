package com.continuousauth.chunking;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
public final class ChunkingManager_Factory implements Factory<ChunkingManager> {
  @Override
  public ChunkingManager get() {
    return newInstance();
  }

  public static ChunkingManager_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static ChunkingManager newInstance() {
    return new ChunkingManager();
  }

  private static final class InstanceHolder {
    private static final ChunkingManager_Factory INSTANCE = new ChunkingManager_Factory();
  }
}
