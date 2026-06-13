package com.learnde.app.learn.data.db.v2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface A1AssociationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(association: A1AssociationEntity)

    @Query("SELECT * FROM a1_associations WHERE lemma IN (:lemmas)")
    suspend fun getForLemmas(lemmas: List<String>): List<A1AssociationEntity>
}