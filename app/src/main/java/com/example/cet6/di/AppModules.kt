package com.example.cet6.di

import android.app.Application
import com.example.cet6.data.StudyDatabase
import com.example.cet6.domain.StudyServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Qualifier
import javax.inject.Singleton

/** The coroutine scope the services run in; it lives exactly as long as the ViewModel that owns them. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ServicesScope

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    /** The same instance [StudyDatabase.get] returns, so code and checks that still call it share one connection. */
    @Provides
    @Singleton
    fun database(app: Application): StudyDatabase = StudyDatabase.get(app)
}

@Module
@InstallIn(ViewModelComponent::class)
object ServicesModule {
    /** Matches viewModelScope: main-thread, supervised, cancelled when the ViewModel is cleared. */
    @Provides
    @ViewModelScoped
    @ServicesScope
    fun scope(lifecycle: ViewModelLifecycle): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        .also { scope -> lifecycle.addOnClearedListener { scope.cancel() } }

    @Provides
    @ViewModelScoped
    fun services(app: Application, db: StudyDatabase, @ServicesScope scope: CoroutineScope): StudyServices = StudyServices(app, db, scope)
}
