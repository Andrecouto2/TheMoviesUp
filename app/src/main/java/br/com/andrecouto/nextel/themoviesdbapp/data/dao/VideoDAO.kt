package br.com.andrecouto.nextel.themoviesdbapp.data.dao

import android.arch.persistence.room.Dao
import android.arch.persistence.room.Insert
import android.arch.persistence.room.OnConflictStrategy
import br.com.andrecouto.nextel.themoviesdbapp.data.model.Video

@Dao
interface VideoDAO {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(video: Video)

}