package com.codeextractor.app.di

import com.codeextractor.app.domain.avatar.AvatarAnimator
import com.codeextractor.app.domain.avatar.AvatarAnimatorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AvatarModule {
    @Binds
    @Singleton
    abstract fun bindAvatarAnimator(impl: AvatarAnimatorImpl): AvatarAnimator
}
