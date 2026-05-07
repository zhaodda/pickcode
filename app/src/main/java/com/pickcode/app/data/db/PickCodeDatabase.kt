package com.pickcode.app.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.model.CodeType

@Database(
    entities = [CodeRecord::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PickCodeDatabase : RoomDatabase() {

    abstract fun codeRecordDao(): CodeRecordDao

    companion object {
        @Volatile private var INSTANCE: PickCodeDatabase? = null

        fun getInstance(context: Context): PickCodeDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PickCodeDatabase::class.java,
                    "pickcode_history.db"
                ).build().also { INSTANCE = it }
            }
    }
}

class Converters {
    @TypeConverter
    fun fromCodeType(value: CodeType): String = value.name

    @TypeConverter
    fun toCodeType(value: String): CodeType = CodeType.valueOf(value)
}
