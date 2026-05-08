package com.pickcode.app.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.model.CodeType

@Database(
    entities = [CodeRecord::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PickCodeDatabase : RoomDatabase() {

    abstract fun codeRecordDao(): CodeRecordDao

    companion object {
        @Volatile private var INSTANCE: PickCodeDatabase? = null

        /** v1→v2：新增 address 字段 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE code_records ADD COLUMN address TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2→v3：新增 isPickedUp 字段 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE code_records ADD COLUMN isPickedUp INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): PickCodeDatabase =
            INSTANCE ?: synchronized(this) {
                val builder: RoomDatabase.Builder<PickCodeDatabase> = Room.databaseBuilder(
                    context.applicationContext,
                    PickCodeDatabase::class.java,
                    "pickcode_history.db"
                )
                builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                val db = builder.build()
                INSTANCE = db
                db
            }
    }
}

class Converters {
    @TypeConverter
    fun fromCodeType(value: CodeType): String = value.name

    @TypeConverter
    fun toCodeType(value: String): CodeType = CodeType.valueOf(value)
}
