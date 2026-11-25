package com.kestrel.vpn.di

import android.content.Context
import androidx.room.Room
import com.kestrel.vpn.data.local.KestrelDatabase
import com.kestrel.vpn.data.local.dao.HuntStateDao
import com.kestrel.vpn.data.local.dao.ProxyConfigDao
import com.kestrel.vpn.data.local.dao.ScanProgressDao
import com.kestrel.vpn.data.local.dao.TestResultDao
import com.kestrel.vpn.data.remote.SubscriptionConfig
import com.kestrel.vpn.data.repository.ConfigRepositoryImpl
import com.kestrel.vpn.domain.repository.ConfigRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataProvideModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KestrelDatabase =
    Room.databaseBuilder(context, KestrelDatabase::class.java, "kestrel.db")
        .fallbackToDestructiveMigration()
        .build()

    @Provides fun provideProxyConfigDao(db: KestrelDatabase): ProxyConfigDao = db.proxyConfigDao()
    @Provides fun provideTestResultDao(db: KestrelDatabase): TestResultDao = db.testResultDao()
    @Provides fun provideScanProgressDao(db: KestrelDatabase): ScanProgressDao = db.scanProgressDao()
    @Provides fun provideHuntStateDao(db: KestrelDatabase): HuntStateDao = db.huntStateDao()

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    @Named("subscription")
    fun provideSubscriptionClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(SubscriptionConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SubscriptionConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(SubscriptionConfig.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindModule {
    @Binds
    @Singleton
    abstract fun bindConfigRepository(impl: ConfigRepositoryImpl): ConfigRepository
}
