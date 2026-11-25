package com.kestrel.vpn.di
import com.kestrel.vpn.domain.core.ProxyCore
import com.kestrel.vpn.engine.core.SingBoxProxyCore
import com.kestrel.vpn.engine.probe.ConnectivityChecker
import com.kestrel.vpn.engine.probe.OfflineDetector
import com.kestrel.vpn.engine.probe.ProxyProbePipeline
import com.kestrel.vpn.engine.probe.ProxyProber
import com.kestrel.vpn.engine.scheduler.DeviceResourceMonitor
import com.kestrel.vpn.engine.scheduler.ResourceConstraintProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
@Module
@InstallIn(SingletonComponent::class)
object EngineProvideModule {
    @Provides
    @Singleton
    @Named("probe")
    fun provideProbeOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
}
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineBindModule {
    @Binds
    @Singleton
    abstract fun bindProxyCore(impl: SingBoxProxyCore): ProxyCore
    @Binds
    @Singleton
    abstract fun bindProxyProber(impl: ProxyProbePipeline): ProxyProber
    @Binds
    @Singleton
    abstract fun bindConnectivityChecker(impl: OfflineDetector): ConnectivityChecker
    @Binds
    @Singleton
    abstract fun bindResourceConstraintProvider(impl: DeviceResourceMonitor): ResourceConstraintProvider
}
