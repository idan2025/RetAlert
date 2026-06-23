package network.retalert.updater

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.retalert.domain.AppVersion
import java.io.File
import javax.inject.Singleton

/** Hilt wiring for the updater module: HTTP fetch bindings + coordinator deps. */
@Module
@InstallIn(SingletonComponent::class)
object UpdaterModule {

    @Provides @Singleton
    fun provideUpdateChecker(): UpdateChecker =
        UpdateChecker(AppVersion.REPO) { url -> UpdateChecker.httpFetch(url) }

    @Provides @Singleton
    fun provideApkDownloader(@ApplicationContext context: Context): ApkDownloader =
        ApkDownloader(context.cacheDir) { url -> ApkDownloader.httpFetchBytes(url) }

    @Provides @Singleton
    fun provideApkInstaller(@ApplicationContext context: Context): ApkInstaller =
        InstallLauncher(context, context.cacheDir)

    @Provides @Singleton
    fun provideCacheDir(@ApplicationContext context: Context): File = context.cacheDir
}