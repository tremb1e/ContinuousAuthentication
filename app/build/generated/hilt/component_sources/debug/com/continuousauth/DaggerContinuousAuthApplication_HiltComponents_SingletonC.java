package com.continuousauth;

import android.app.Activity;
import android.app.Service;
import android.view.View;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.continuousauth.buffer.InMemoryBufferImpl;
import com.continuousauth.buffer.RingBuffer;
import com.continuousauth.chunking.ChunkingManager;
import com.continuousauth.compression.CompressionManager;
import com.continuousauth.core.SmartTransmissionManager;
import com.continuousauth.crypto.AADBuilder;
import com.continuousauth.crypto.EnvelopeCryptoBox;
import com.continuousauth.data.DataPacketBuilder;
import com.continuousauth.database.BatchMetadataDao;
import com.continuousauth.database.ContinuousAuthDatabase;
import com.continuousauth.di.AppModule_ProvideApplicationCoroutineScopeFactory;
import com.continuousauth.di.DatabaseModule_ProvideBatchMetadataDaoFactory;
import com.continuousauth.di.DatabaseModule_ProvideContinuousAuthDatabaseFactory;
import com.continuousauth.monitor.MemoryMonitor;
import com.continuousauth.monitor.SystemMonitor;
import com.continuousauth.network.GrpcManager;
import com.continuousauth.network.NetworkEnvironmentDetector;
import com.continuousauth.network.ServerConnectionTester;
import com.continuousauth.network.TlsSecurityManager;
import com.continuousauth.network.UploadManager;
import com.continuousauth.network.UploaderImpl;
import com.continuousauth.observability.MetricsCollectorImpl;
import com.continuousauth.observability.MetricsUploader;
import com.continuousauth.observability.PerformanceMonitorImpl;
import com.continuousauth.policy.PolicyManager;
import com.continuousauth.pool.SensorEventPool;
import com.continuousauth.privacy.PrivacyManager;
import com.continuousauth.processing.SensorDataProcessor;
import com.continuousauth.sensor.SensorCollectorImpl;
import com.continuousauth.service.DataCollectionService;
import com.continuousauth.service.DataCollectionService_MembersInjector;
import com.continuousauth.storage.FileQueueManager;
import com.continuousauth.time.EnhancedTimeSync;
import com.continuousauth.ui.MainActivity;
import com.continuousauth.ui.MainActivityNav;
import com.continuousauth.ui.MainViewModel;
import com.continuousauth.ui.MainViewModel_HiltModules;
import com.continuousauth.ui.chart.ChartManager;
import com.continuousauth.ui.compose.MainComposeActivity;
import com.continuousauth.ui.fragments.SensorDataFragment;
import com.continuousauth.ui.fragments.SensorDataFragment_MembersInjector;
import com.continuousauth.ui.fragments.ServerConfigFragment;
import com.continuousauth.ui.viewmodels.DetailedInfoViewModel;
import com.continuousauth.ui.viewmodels.DetailedInfoViewModel_HiltModules;
import com.continuousauth.ui.viewmodels.SensorDataViewModel;
import com.continuousauth.ui.viewmodels.SensorDataViewModel_HiltModules;
import com.continuousauth.ui.viewmodels.ServerConfigViewModel;
import com.continuousauth.ui.viewmodels.ServerConfigViewModel_HiltModules;
import com.continuousauth.utils.ForegroundAppDetector;
import com.continuousauth.utils.UserIdManager;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dagger.hilt.android.ActivityRetainedLifecycle;
import dagger.hilt.android.ViewModelLifecycle;
import dagger.hilt.android.internal.builders.ActivityComponentBuilder;
import dagger.hilt.android.internal.builders.ActivityRetainedComponentBuilder;
import dagger.hilt.android.internal.builders.FragmentComponentBuilder;
import dagger.hilt.android.internal.builders.ServiceComponentBuilder;
import dagger.hilt.android.internal.builders.ViewComponentBuilder;
import dagger.hilt.android.internal.builders.ViewModelComponentBuilder;
import dagger.hilt.android.internal.builders.ViewWithFragmentComponentBuilder;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories_InternalFactoryFactory_Factory;
import dagger.hilt.android.internal.managers.ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory;
import dagger.hilt.android.internal.managers.SavedStateHandleHolder;
import dagger.hilt.android.internal.modules.ApplicationContextModule;
import dagger.hilt.android.internal.modules.ApplicationContextModule_ProvideContextFactory;
import dagger.internal.DaggerGenerated;
import dagger.internal.DoubleCheck;
import dagger.internal.IdentifierNameString;
import dagger.internal.KeepFieldType;
import dagger.internal.LazyClassKeyMap;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;
import kotlinx.coroutines.CoroutineScope;

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
public final class DaggerContinuousAuthApplication_HiltComponents_SingletonC {
  private DaggerContinuousAuthApplication_HiltComponents_SingletonC() {
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ApplicationContextModule applicationContextModule;

    private Builder() {
    }

    public Builder applicationContextModule(ApplicationContextModule applicationContextModule) {
      this.applicationContextModule = Preconditions.checkNotNull(applicationContextModule);
      return this;
    }

    public ContinuousAuthApplication_HiltComponents.SingletonC build() {
      Preconditions.checkBuilderRequirement(applicationContextModule, ApplicationContextModule.class);
      return new SingletonCImpl(applicationContextModule);
    }
  }

  private static final class ActivityRetainedCBuilder implements ContinuousAuthApplication_HiltComponents.ActivityRetainedC.Builder {
    private final SingletonCImpl singletonCImpl;

    private SavedStateHandleHolder savedStateHandleHolder;

    private ActivityRetainedCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ActivityRetainedCBuilder savedStateHandleHolder(
        SavedStateHandleHolder savedStateHandleHolder) {
      this.savedStateHandleHolder = Preconditions.checkNotNull(savedStateHandleHolder);
      return this;
    }

    @Override
    public ContinuousAuthApplication_HiltComponents.ActivityRetainedC build() {
      Preconditions.checkBuilderRequirement(savedStateHandleHolder, SavedStateHandleHolder.class);
      return new ActivityRetainedCImpl(singletonCImpl, savedStateHandleHolder);
    }
  }

  private static final class ActivityCBuilder implements ContinuousAuthApplication_HiltComponents.ActivityC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private Activity activity;

    private ActivityCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ActivityCBuilder activity(Activity activity) {
      this.activity = Preconditions.checkNotNull(activity);
      return this;
    }

    @Override
    public ContinuousAuthApplication_HiltComponents.ActivityC build() {
      Preconditions.checkBuilderRequirement(activity, Activity.class);
      return new ActivityCImpl(singletonCImpl, activityRetainedCImpl, activity);
    }
  }

  private static final class FragmentCBuilder implements ContinuousAuthApplication_HiltComponents.FragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private Fragment fragment;

    private FragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public FragmentCBuilder fragment(Fragment fragment) {
      this.fragment = Preconditions.checkNotNull(fragment);
      return this;
    }

    @Override
    public ContinuousAuthApplication_HiltComponents.FragmentC build() {
      Preconditions.checkBuilderRequirement(fragment, Fragment.class);
      return new FragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragment);
    }
  }

  private static final class ViewWithFragmentCBuilder implements ContinuousAuthApplication_HiltComponents.ViewWithFragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private View view;

    private ViewWithFragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;
    }

    @Override
    public ViewWithFragmentCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public ContinuousAuthApplication_HiltComponents.ViewWithFragmentC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewWithFragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl, view);
    }
  }

  private static final class ViewCBuilder implements ContinuousAuthApplication_HiltComponents.ViewC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private View view;

    private ViewCBuilder(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public ViewCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public ContinuousAuthApplication_HiltComponents.ViewC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, view);
    }
  }

  private static final class ViewModelCBuilder implements ContinuousAuthApplication_HiltComponents.ViewModelC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private SavedStateHandle savedStateHandle;

    private ViewModelLifecycle viewModelLifecycle;

    private ViewModelCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ViewModelCBuilder savedStateHandle(SavedStateHandle handle) {
      this.savedStateHandle = Preconditions.checkNotNull(handle);
      return this;
    }

    @Override
    public ViewModelCBuilder viewModelLifecycle(ViewModelLifecycle viewModelLifecycle) {
      this.viewModelLifecycle = Preconditions.checkNotNull(viewModelLifecycle);
      return this;
    }

    @Override
    public ContinuousAuthApplication_HiltComponents.ViewModelC build() {
      Preconditions.checkBuilderRequirement(savedStateHandle, SavedStateHandle.class);
      Preconditions.checkBuilderRequirement(viewModelLifecycle, ViewModelLifecycle.class);
      return new ViewModelCImpl(singletonCImpl, activityRetainedCImpl, savedStateHandle, viewModelLifecycle);
    }
  }

  private static final class ServiceCBuilder implements ContinuousAuthApplication_HiltComponents.ServiceC.Builder {
    private final SingletonCImpl singletonCImpl;

    private Service service;

    private ServiceCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ServiceCBuilder service(Service service) {
      this.service = Preconditions.checkNotNull(service);
      return this;
    }

    @Override
    public ContinuousAuthApplication_HiltComponents.ServiceC build() {
      Preconditions.checkBuilderRequirement(service, Service.class);
      return new ServiceCImpl(singletonCImpl, service);
    }
  }

  private static final class ViewWithFragmentCImpl extends ContinuousAuthApplication_HiltComponents.ViewWithFragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private final ViewWithFragmentCImpl viewWithFragmentCImpl = this;

    private ViewWithFragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;


    }
  }

  private static final class FragmentCImpl extends ContinuousAuthApplication_HiltComponents.FragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl = this;

    private FragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        Fragment fragmentParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }

    @Override
    public void injectSensorDataFragment(SensorDataFragment sensorDataFragment) {
      injectSensorDataFragment2(sensorDataFragment);
    }

    @Override
    public void injectServerConfigFragment(ServerConfigFragment serverConfigFragment) {
    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return activityCImpl.getHiltInternalFactoryFactory();
    }

    @Override
    public ViewWithFragmentComponentBuilder viewWithFragmentComponentBuilder() {
      return new ViewWithFragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl);
    }

    @CanIgnoreReturnValue
    private SensorDataFragment injectSensorDataFragment2(SensorDataFragment instance) {
      SensorDataFragment_MembersInjector.injectChartManager(instance, singletonCImpl.chartManagerProvider.get());
      return instance;
    }
  }

  private static final class ViewCImpl extends ContinuousAuthApplication_HiltComponents.ViewC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final ViewCImpl viewCImpl = this;

    private ViewCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }
  }

  private static final class ActivityCImpl extends ContinuousAuthApplication_HiltComponents.ActivityC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl = this;

    private ActivityCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, Activity activityParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;


    }

    @Override
    public void injectMainActivityNav(MainActivityNav mainActivityNav) {
    }

    @Override
    public void injectMainActivity(MainActivity mainActivity) {
    }

    @Override
    public void injectMainComposeActivity(MainComposeActivity mainComposeActivity) {
    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return DefaultViewModelFactories_InternalFactoryFactory_Factory.newInstance(getViewModelKeys(), new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl));
    }

    @Override
    public Map<Class<?>, Boolean> getViewModelKeys() {
      return LazyClassKeyMap.<Boolean>of(ImmutableMap.<String, Boolean>of(LazyClassKeyProvider.com_continuousauth_ui_viewmodels_DetailedInfoViewModel, DetailedInfoViewModel_HiltModules.KeyModule.provide(), LazyClassKeyProvider.com_continuousauth_ui_MainViewModel, MainViewModel_HiltModules.KeyModule.provide(), LazyClassKeyProvider.com_continuousauth_ui_viewmodels_SensorDataViewModel, SensorDataViewModel_HiltModules.KeyModule.provide(), LazyClassKeyProvider.com_continuousauth_ui_viewmodels_ServerConfigViewModel, ServerConfigViewModel_HiltModules.KeyModule.provide()));
    }

    @Override
    public ViewModelComponentBuilder getViewModelComponentBuilder() {
      return new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public FragmentComponentBuilder fragmentComponentBuilder() {
      return new FragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @Override
    public ViewComponentBuilder viewComponentBuilder() {
      return new ViewCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @IdentifierNameString
    private static final class LazyClassKeyProvider {
      static String com_continuousauth_ui_MainViewModel = "com.continuousauth.ui.MainViewModel";

      static String com_continuousauth_ui_viewmodels_DetailedInfoViewModel = "com.continuousauth.ui.viewmodels.DetailedInfoViewModel";

      static String com_continuousauth_ui_viewmodels_SensorDataViewModel = "com.continuousauth.ui.viewmodels.SensorDataViewModel";

      static String com_continuousauth_ui_viewmodels_ServerConfigViewModel = "com.continuousauth.ui.viewmodels.ServerConfigViewModel";

      @KeepFieldType
      MainViewModel com_continuousauth_ui_MainViewModel2;

      @KeepFieldType
      DetailedInfoViewModel com_continuousauth_ui_viewmodels_DetailedInfoViewModel2;

      @KeepFieldType
      SensorDataViewModel com_continuousauth_ui_viewmodels_SensorDataViewModel2;

      @KeepFieldType
      ServerConfigViewModel com_continuousauth_ui_viewmodels_ServerConfigViewModel2;
    }
  }

  private static final class ViewModelCImpl extends ContinuousAuthApplication_HiltComponents.ViewModelC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ViewModelCImpl viewModelCImpl = this;

    private Provider<DetailedInfoViewModel> detailedInfoViewModelProvider;

    private Provider<MainViewModel> mainViewModelProvider;

    private Provider<SensorDataViewModel> sensorDataViewModelProvider;

    private Provider<ServerConfigViewModel> serverConfigViewModelProvider;

    private ViewModelCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, SavedStateHandle savedStateHandleParam,
        ViewModelLifecycle viewModelLifecycleParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;

      initialize(savedStateHandleParam, viewModelLifecycleParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandle savedStateHandleParam,
        final ViewModelLifecycle viewModelLifecycleParam) {
      this.detailedInfoViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 0);
      this.mainViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 1);
      this.sensorDataViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 2);
      this.serverConfigViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 3);
    }

    @Override
    public Map<Class<?>, javax.inject.Provider<ViewModel>> getHiltViewModelMap() {
      return LazyClassKeyMap.<javax.inject.Provider<ViewModel>>of(ImmutableMap.<String, javax.inject.Provider<ViewModel>>of(LazyClassKeyProvider.com_continuousauth_ui_viewmodels_DetailedInfoViewModel, ((Provider) detailedInfoViewModelProvider), LazyClassKeyProvider.com_continuousauth_ui_MainViewModel, ((Provider) mainViewModelProvider), LazyClassKeyProvider.com_continuousauth_ui_viewmodels_SensorDataViewModel, ((Provider) sensorDataViewModelProvider), LazyClassKeyProvider.com_continuousauth_ui_viewmodels_ServerConfigViewModel, ((Provider) serverConfigViewModelProvider)));
    }

    @Override
    public Map<Class<?>, Object> getHiltViewModelAssistedMap() {
      return ImmutableMap.<Class<?>, Object>of();
    }

    @IdentifierNameString
    private static final class LazyClassKeyProvider {
      static String com_continuousauth_ui_viewmodels_DetailedInfoViewModel = "com.continuousauth.ui.viewmodels.DetailedInfoViewModel";

      static String com_continuousauth_ui_viewmodels_ServerConfigViewModel = "com.continuousauth.ui.viewmodels.ServerConfigViewModel";

      static String com_continuousauth_ui_MainViewModel = "com.continuousauth.ui.MainViewModel";

      static String com_continuousauth_ui_viewmodels_SensorDataViewModel = "com.continuousauth.ui.viewmodels.SensorDataViewModel";

      @KeepFieldType
      DetailedInfoViewModel com_continuousauth_ui_viewmodels_DetailedInfoViewModel2;

      @KeepFieldType
      ServerConfigViewModel com_continuousauth_ui_viewmodels_ServerConfigViewModel2;

      @KeepFieldType
      MainViewModel com_continuousauth_ui_MainViewModel2;

      @KeepFieldType
      SensorDataViewModel com_continuousauth_ui_viewmodels_SensorDataViewModel2;
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final ViewModelCImpl viewModelCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          ViewModelCImpl viewModelCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.viewModelCImpl = viewModelCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // com.continuousauth.ui.viewmodels.DetailedInfoViewModel 
          return (T) new DetailedInfoViewModel(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.systemMonitorProvider.get());

          case 1: // com.continuousauth.ui.MainViewModel 
          return (T) new MainViewModel(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.uploadManagerProvider.get(), singletonCImpl.networkEnvironmentDetectorProvider.get(), singletonCImpl.metricsCollectorImplProvider.get(), singletonCImpl.performanceMonitorImplProvider.get(), singletonCImpl.memoryMonitorProvider.get(), singletonCImpl.sensorEventPoolProvider.get(), singletonCImpl.userIdManagerProvider.get(), singletonCImpl.serverConnectionTesterProvider.get(), singletonCImpl.fileQueueManagerProvider.get(), singletonCImpl.tlsSecurityManagerProvider.get(), singletonCImpl.privacyManagerProvider.get());

          case 2: // com.continuousauth.ui.viewmodels.SensorDataViewModel 
          return (T) new SensorDataViewModel(singletonCImpl.sensorCollectorImplProvider.get(), singletonCImpl.foregroundAppDetectorProvider.get());

          case 3: // com.continuousauth.ui.viewmodels.ServerConfigViewModel 
          return (T) new ServerConfigViewModel(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.uploaderImplProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ActivityRetainedCImpl extends ContinuousAuthApplication_HiltComponents.ActivityRetainedC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl = this;

    private Provider<ActivityRetainedLifecycle> provideActivityRetainedLifecycleProvider;

    private ActivityRetainedCImpl(SingletonCImpl singletonCImpl,
        SavedStateHandleHolder savedStateHandleHolderParam) {
      this.singletonCImpl = singletonCImpl;

      initialize(savedStateHandleHolderParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandleHolder savedStateHandleHolderParam) {
      this.provideActivityRetainedLifecycleProvider = DoubleCheck.provider(new SwitchingProvider<ActivityRetainedLifecycle>(singletonCImpl, activityRetainedCImpl, 0));
    }

    @Override
    public ActivityComponentBuilder activityComponentBuilder() {
      return new ActivityCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public ActivityRetainedLifecycle getActivityRetainedLifecycle() {
      return provideActivityRetainedLifecycleProvider.get();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // dagger.hilt.android.ActivityRetainedLifecycle 
          return (T) ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory.provideActivityRetainedLifecycle();

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ServiceCImpl extends ContinuousAuthApplication_HiltComponents.ServiceC {
    private final SingletonCImpl singletonCImpl;

    private final ServiceCImpl serviceCImpl = this;

    private ServiceCImpl(SingletonCImpl singletonCImpl, Service serviceParam) {
      this.singletonCImpl = singletonCImpl;


    }

    @Override
    public void injectDataCollectionService(DataCollectionService dataCollectionService) {
      injectDataCollectionService2(dataCollectionService);
    }

    @CanIgnoreReturnValue
    private DataCollectionService injectDataCollectionService2(DataCollectionService instance) {
      DataCollectionService_MembersInjector.injectPrivacyManager(instance, singletonCImpl.privacyManagerProvider.get());
      DataCollectionService_MembersInjector.injectSmartTransmissionManager(instance, singletonCImpl.smartTransmissionManagerProvider.get());
      return instance;
    }
  }

  private static final class SingletonCImpl extends ContinuousAuthApplication_HiltComponents.SingletonC {
    private final ApplicationContextModule applicationContextModule;

    private final SingletonCImpl singletonCImpl = this;

    private Provider<CoroutineScope> provideApplicationCoroutineScopeProvider;

    private Provider<EnhancedTimeSync> enhancedTimeSyncProvider;

    private Provider<ForegroundAppDetector> foregroundAppDetectorProvider;

    private Provider<RingBuffer> ringBufferProvider;

    private Provider<SensorEventPool> sensorEventPoolProvider;

    private Provider<SensorCollectorImpl> sensorCollectorImplProvider;

    private Provider<ChartManager> chartManagerProvider;

    private Provider<TlsSecurityManager> tlsSecurityManagerProvider;

    private Provider<PolicyManager> policyManagerProvider;

    private Provider<UploaderImpl> uploaderImplProvider;

    private Provider<ContinuousAuthDatabase> provideContinuousAuthDatabaseProvider;

    private Provider<BatchMetadataDao> provideBatchMetadataDaoProvider;

    private Provider<FileQueueManager> fileQueueManagerProvider;

    private Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider;

    private Provider<SystemMonitor> systemMonitorProvider;

    private Provider<InMemoryBufferImpl> inMemoryBufferImplProvider;

    private Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider;

    private Provider<UploadManager> uploadManagerProvider;

    private Provider<MetricsCollectorImpl> metricsCollectorImplProvider;

    private Provider<PerformanceMonitorImpl> performanceMonitorImplProvider;

    private Provider<MemoryMonitor> memoryMonitorProvider;

    private Provider<UserIdManager> userIdManagerProvider;

    private Provider<GrpcManager> grpcManagerProvider;

    private Provider<ServerConnectionTester> serverConnectionTesterProvider;

    private Provider<PrivacyManager> privacyManagerProvider;

    private Provider<AADBuilder> aADBuilderProvider;

    private Provider<DataPacketBuilder> dataPacketBuilderProvider;

    private Provider<CompressionManager> compressionManagerProvider;

    private Provider<ChunkingManager> chunkingManagerProvider;

    private Provider<SensorDataProcessor> sensorDataProcessorProvider;

    private Provider<MetricsUploader> metricsUploaderProvider;

    private Provider<SmartTransmissionManager> smartTransmissionManagerProvider;

    private SingletonCImpl(ApplicationContextModule applicationContextModuleParam) {
      this.applicationContextModule = applicationContextModuleParam;
      initialize(applicationContextModuleParam);
      initialize2(applicationContextModuleParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final ApplicationContextModule applicationContextModuleParam) {
      this.provideApplicationCoroutineScopeProvider = DoubleCheck.provider(new SwitchingProvider<CoroutineScope>(singletonCImpl, 1));
      this.enhancedTimeSyncProvider = DoubleCheck.provider(new SwitchingProvider<EnhancedTimeSync>(singletonCImpl, 0));
      this.foregroundAppDetectorProvider = DoubleCheck.provider(new SwitchingProvider<ForegroundAppDetector>(singletonCImpl, 4));
      this.ringBufferProvider = DoubleCheck.provider(new SwitchingProvider<RingBuffer>(singletonCImpl, 5));
      this.sensorEventPoolProvider = DoubleCheck.provider(new SwitchingProvider<SensorEventPool>(singletonCImpl, 6));
      this.sensorCollectorImplProvider = DoubleCheck.provider(new SwitchingProvider<SensorCollectorImpl>(singletonCImpl, 3));
      this.chartManagerProvider = DoubleCheck.provider(new SwitchingProvider<ChartManager>(singletonCImpl, 2));
      this.tlsSecurityManagerProvider = DoubleCheck.provider(new SwitchingProvider<TlsSecurityManager>(singletonCImpl, 9));
      this.policyManagerProvider = DoubleCheck.provider(new SwitchingProvider<PolicyManager>(singletonCImpl, 10));
      this.uploaderImplProvider = DoubleCheck.provider(new SwitchingProvider<UploaderImpl>(singletonCImpl, 8));
      this.provideContinuousAuthDatabaseProvider = DoubleCheck.provider(new SwitchingProvider<ContinuousAuthDatabase>(singletonCImpl, 13));
      this.provideBatchMetadataDaoProvider = DoubleCheck.provider(new SwitchingProvider<BatchMetadataDao>(singletonCImpl, 12));
      this.fileQueueManagerProvider = DoubleCheck.provider(new SwitchingProvider<FileQueueManager>(singletonCImpl, 11));
      this.envelopeCryptoBoxProvider = DoubleCheck.provider(new SwitchingProvider<EnvelopeCryptoBox>(singletonCImpl, 14));
      this.systemMonitorProvider = DoubleCheck.provider(new SwitchingProvider<SystemMonitor>(singletonCImpl, 7));
      this.inMemoryBufferImplProvider = DoubleCheck.provider(new SwitchingProvider<InMemoryBufferImpl>(singletonCImpl, 16));
      this.networkEnvironmentDetectorProvider = DoubleCheck.provider(new SwitchingProvider<NetworkEnvironmentDetector>(singletonCImpl, 17));
      this.uploadManagerProvider = DoubleCheck.provider(new SwitchingProvider<UploadManager>(singletonCImpl, 15));
      this.metricsCollectorImplProvider = DoubleCheck.provider(new SwitchingProvider<MetricsCollectorImpl>(singletonCImpl, 18));
      this.performanceMonitorImplProvider = DoubleCheck.provider(new SwitchingProvider<PerformanceMonitorImpl>(singletonCImpl, 19));
      this.memoryMonitorProvider = DoubleCheck.provider(new SwitchingProvider<MemoryMonitor>(singletonCImpl, 20));
      this.userIdManagerProvider = DoubleCheck.provider(new SwitchingProvider<UserIdManager>(singletonCImpl, 21));
      this.grpcManagerProvider = DoubleCheck.provider(new SwitchingProvider<GrpcManager>(singletonCImpl, 23));
      this.serverConnectionTesterProvider = DoubleCheck.provider(new SwitchingProvider<ServerConnectionTester>(singletonCImpl, 22));
      this.privacyManagerProvider = DoubleCheck.provider(new SwitchingProvider<PrivacyManager>(singletonCImpl, 24));
    }

    @SuppressWarnings("unchecked")
    private void initialize2(final ApplicationContextModule applicationContextModuleParam) {
      this.aADBuilderProvider = DoubleCheck.provider(new SwitchingProvider<AADBuilder>(singletonCImpl, 27));
      this.dataPacketBuilderProvider = DoubleCheck.provider(new SwitchingProvider<DataPacketBuilder>(singletonCImpl, 28));
      this.compressionManagerProvider = DoubleCheck.provider(new SwitchingProvider<CompressionManager>(singletonCImpl, 29));
      this.chunkingManagerProvider = DoubleCheck.provider(new SwitchingProvider<ChunkingManager>(singletonCImpl, 30));
      this.sensorDataProcessorProvider = DoubleCheck.provider(new SwitchingProvider<SensorDataProcessor>(singletonCImpl, 26));
      this.metricsUploaderProvider = DoubleCheck.provider(new SwitchingProvider<MetricsUploader>(singletonCImpl, 31));
      this.smartTransmissionManagerProvider = DoubleCheck.provider(new SwitchingProvider<SmartTransmissionManager>(singletonCImpl, 25));
    }

    @Override
    public void injectContinuousAuthApplication(
        ContinuousAuthApplication continuousAuthApplication) {
      injectContinuousAuthApplication2(continuousAuthApplication);
    }

    @Override
    public Set<Boolean> getDisableFragmentGetContextFix() {
      return ImmutableSet.<Boolean>of();
    }

    @Override
    public ActivityRetainedComponentBuilder retainedComponentBuilder() {
      return new ActivityRetainedCBuilder(singletonCImpl);
    }

    @Override
    public ServiceComponentBuilder serviceComponentBuilder() {
      return new ServiceCBuilder(singletonCImpl);
    }

    @CanIgnoreReturnValue
    private ContinuousAuthApplication injectContinuousAuthApplication2(
        ContinuousAuthApplication instance) {
      ContinuousAuthApplication_MembersInjector.injectTimeSync(instance, enhancedTimeSyncProvider.get());
      return instance;
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // com.continuousauth.time.EnhancedTimeSync 
          return (T) new EnhancedTimeSync(singletonCImpl.provideApplicationCoroutineScopeProvider.get());

          case 1: // kotlinx.coroutines.CoroutineScope 
          return (T) AppModule_ProvideApplicationCoroutineScopeFactory.provideApplicationCoroutineScope();

          case 2: // com.continuousauth.ui.chart.ChartManager 
          return (T) new ChartManager(singletonCImpl.sensorCollectorImplProvider.get());

          case 3: // com.continuousauth.sensor.SensorCollectorImpl 
          return (T) new SensorCollectorImpl(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.foregroundAppDetectorProvider.get(), singletonCImpl.ringBufferProvider.get(), singletonCImpl.sensorEventPoolProvider.get());

          case 4: // com.continuousauth.utils.ForegroundAppDetector 
          return (T) new ForegroundAppDetector(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 5: // com.continuousauth.buffer.RingBuffer 
          return (T) new RingBuffer();

          case 6: // com.continuousauth.pool.SensorEventPool 
          return (T) new SensorEventPool();

          case 7: // com.continuousauth.monitor.SystemMonitor 
          return (T) new SystemMonitor(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.uploaderImplProvider.get(), singletonCImpl.fileQueueManagerProvider.get(), singletonCImpl.envelopeCryptoBoxProvider.get(), singletonCImpl.enhancedTimeSyncProvider.get());

          case 8: // com.continuousauth.network.UploaderImpl 
          return (T) new UploaderImpl(singletonCImpl.tlsSecurityManagerProvider.get(), singletonCImpl.policyManagerProvider.get());

          case 9: // com.continuousauth.network.TlsSecurityManager 
          return (T) new TlsSecurityManager();

          case 10: // com.continuousauth.policy.PolicyManager 
          return (T) new PolicyManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 11: // com.continuousauth.storage.FileQueueManager 
          return (T) new FileQueueManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideBatchMetadataDaoProvider.get());

          case 12: // com.continuousauth.database.BatchMetadataDao 
          return (T) DatabaseModule_ProvideBatchMetadataDaoFactory.provideBatchMetadataDao(singletonCImpl.provideContinuousAuthDatabaseProvider.get());

          case 13: // com.continuousauth.database.ContinuousAuthDatabase 
          return (T) DatabaseModule_ProvideContinuousAuthDatabaseFactory.provideContinuousAuthDatabase(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 14: // com.continuousauth.crypto.EnvelopeCryptoBox 
          return (T) new EnvelopeCryptoBox(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 15: // com.continuousauth.network.UploadManager 
          return (T) new UploadManager(singletonCImpl.uploaderImplProvider.get(), singletonCImpl.inMemoryBufferImplProvider.get(), singletonCImpl.policyManagerProvider.get(), singletonCImpl.fileQueueManagerProvider.get(), singletonCImpl.networkEnvironmentDetectorProvider.get());

          case 16: // com.continuousauth.buffer.InMemoryBufferImpl 
          return (T) new InMemoryBufferImpl();

          case 17: // com.continuousauth.network.NetworkEnvironmentDetector 
          return (T) new NetworkEnvironmentDetector(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 18: // com.continuousauth.observability.MetricsCollectorImpl 
          return (T) new MetricsCollectorImpl();

          case 19: // com.continuousauth.observability.PerformanceMonitorImpl 
          return (T) new PerformanceMonitorImpl(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 20: // com.continuousauth.monitor.MemoryMonitor 
          return (T) new MemoryMonitor();

          case 21: // com.continuousauth.utils.UserIdManager 
          return (T) new UserIdManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 22: // com.continuousauth.network.ServerConnectionTester 
          return (T) new ServerConnectionTester(singletonCImpl.grpcManagerProvider.get());

          case 23: // com.continuousauth.network.GrpcManager 
          return (T) new GrpcManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 24: // com.continuousauth.privacy.PrivacyManager 
          return (T) new PrivacyManager(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideContinuousAuthDatabaseProvider.get(), singletonCImpl.fileQueueManagerProvider.get(), singletonCImpl.userIdManagerProvider.get(), singletonCImpl.grpcManagerProvider.get());

          case 25: // com.continuousauth.core.SmartTransmissionManager 
          return (T) new SmartTransmissionManager(singletonCImpl.sensorCollectorImplProvider.get(), singletonCImpl.sensorDataProcessorProvider.get(), singletonCImpl.dataPacketBuilderProvider.get(), singletonCImpl.compressionManagerProvider.get(), singletonCImpl.envelopeCryptoBoxProvider.get(), singletonCImpl.inMemoryBufferImplProvider.get(), singletonCImpl.fileQueueManagerProvider.get(), singletonCImpl.uploadManagerProvider.get(), singletonCImpl.chunkingManagerProvider.get(), singletonCImpl.enhancedTimeSyncProvider.get(), singletonCImpl.systemMonitorProvider.get(), singletonCImpl.metricsUploaderProvider.get(), singletonCImpl.privacyManagerProvider.get(), singletonCImpl.userIdManagerProvider.get());

          case 26: // com.continuousauth.processing.SensorDataProcessor 
          return (T) new SensorDataProcessor(singletonCImpl.envelopeCryptoBoxProvider.get(), singletonCImpl.envelopeCryptoBoxProvider.get(), singletonCImpl.aADBuilderProvider.get(), singletonCImpl.dataPacketBuilderProvider.get(), singletonCImpl.compressionManagerProvider.get(), singletonCImpl.chunkingManagerProvider.get());

          case 27: // com.continuousauth.crypto.AADBuilder 
          return (T) new AADBuilder(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.envelopeCryptoBoxProvider.get());

          case 28: // com.continuousauth.data.DataPacketBuilder 
          return (T) new DataPacketBuilder(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.enhancedTimeSyncProvider.get(), singletonCImpl.envelopeCryptoBoxProvider.get());

          case 29: // com.continuousauth.compression.CompressionManager 
          return (T) new CompressionManager();

          case 30: // com.continuousauth.chunking.ChunkingManager 
          return (T) new ChunkingManager();

          case 31: // com.continuousauth.observability.MetricsUploader 
          return (T) new MetricsUploader(singletonCImpl.metricsCollectorImplProvider.get(), singletonCImpl.performanceMonitorImplProvider.get(), singletonCImpl.userIdManagerProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }
}
