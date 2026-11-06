package com.elialombardi.piccistodo.di

import android.content.Context
import com.elialombardi.piccistodo.data.local.SettingsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsManager(@ApplicationContext context: Context): SettingsManager {
        return SettingsManager(context)
    }

    // That's it!
    // Hilt will automatically construct RetrofitProvider and TodoRepository
    // because they have @Inject constructors.
}