package com.bicilona.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY createdAt DESC")
    fun getAll(): LiveData<List<FavoritePlace>>

    @Insert
    suspend fun insert(favorite: FavoritePlace)

    @Delete
    suspend fun delete(favorite: FavoritePlace)
}
