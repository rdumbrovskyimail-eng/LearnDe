package com.learnde.app.di

import com.learnde.app.learn.data.db.A1Database
import com.learnde.app.learn.data.db.v2.A1AssociationDao
import com.learnde.app.learn.data.db.v2.A1LessonPlanDao
import com.learnde.app.learn.data.db.v2.LearnerProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LearnV2Module {

    @Provides
    @Singleton
    fun provideAssociationDao(db: A1Database): A1AssociationDao = db.associationDao()

    @Provides
    @Singleton
    fun provideLessonPlanDao(db: A1Database): A1LessonPlanDao = db.lessonPlanDao()

    @Provides
    @Singleton
    fun provideLearnerProfileDao(db: A1Database): LearnerProfileDao = db.learnerProfileDao()
}