package com.qwen.tts.android.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QwenDao {
    @Query("SELECT * FROM voice_profiles ORDER BY createdAt DESC")
    fun observeVoices(): Flow<List<VoiceProfileEntity>>

    @Insert
    suspend fun insertVoice(voice: VoiceProfileEntity)

    @Delete
    suspend fun deleteVoice(voice: VoiceProfileEntity)

    @Query("SELECT * FROM generations ORDER BY createdAt DESC")
    fun observeGenerations(): Flow<List<GenerationEntity>>

    @Query("SELECT * FROM generations WHERE generationId = :generationId")
    suspend fun getGeneration(generationId: Long): GenerationEntity?

    @Delete
    suspend fun deleteGeneration(generation: GenerationEntity)
}
