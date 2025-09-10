package com.continuousauth.ui.viewmodels;

import android.content.Context;
import com.continuousauth.network.Uploader;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class ServerConfigViewModel_Factory implements Factory<ServerConfigViewModel> {
  private final Provider<Context> contextProvider;

  private final Provider<Uploader> uploaderProvider;

  public ServerConfigViewModel_Factory(Provider<Context> contextProvider,
      Provider<Uploader> uploaderProvider) {
    this.contextProvider = contextProvider;
    this.uploaderProvider = uploaderProvider;
  }

  @Override
  public ServerConfigViewModel get() {
    return newInstance(contextProvider.get(), uploaderProvider.get());
  }

  public static ServerConfigViewModel_Factory create(Provider<Context> contextProvider,
      Provider<Uploader> uploaderProvider) {
    return new ServerConfigViewModel_Factory(contextProvider, uploaderProvider);
  }

  public static ServerConfigViewModel newInstance(Context context, Uploader uploader) {
    return new ServerConfigViewModel(context, uploader);
  }
}
