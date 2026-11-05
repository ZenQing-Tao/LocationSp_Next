package com.vincenthzr.locationspoofer.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrajectoryDao {
    @Query("SELECT * FROM trajectories ORDER BY timestamp DESC")
    fun getAll(): Flow<List<TrajectoryEntity>>

    @Query("SELECT * FROM trajectories ORDER BY timestamp DESC")
    suspend fun getAllList(): List<TrajectoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trajectory: TrajectoryEntity): Long

    @Delete
    suspend fun delete(trajectory: TrajectoryEntity)

    @Query("DELETE FROM trajectories WHERE id = :id")
    suspend fun deleteById(id: Long)
}
