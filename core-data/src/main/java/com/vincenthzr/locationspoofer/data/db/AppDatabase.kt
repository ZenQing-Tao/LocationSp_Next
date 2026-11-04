package com.vincenthzr.locationspoofer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocationRecord::class,
        LocationConnectedWifi::class,
        WifiDevice::class, LocationWifi::class,
        BluetoothDevice::class, LocationBluetooth::class,
        CellDevice::class, LocationCell::class,
        SavedRouteEntity::class, CollectionRouteRecord::class,
        TrajectoryEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun environmentDao(): EnvironmentDao
    abstract fun savedRouteDao(): SavedRouteDao
    abstract fun trajectoryDao(): TrajectoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_records ADD COLUMN placeName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE location_records ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_records ADD COLUMN selectedWifiBssid TEXT")
                db.execSQL("ALTER TABLE location_records ADD COLUMN selectedBluetoothAddress TEXT")
                db.execSQL("ALTER TABLE location_records ADD COLUMN selectedCellKey TEXT")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_records_lat_lng ON location_records(lat, lng)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_records_timestamp ON location_records(timestamp)")
            }
        }

        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 增加来源标记列：空串 = 普通路线，trajectory = GPS 轨迹录制
                db.execSQL("ALTER TABLE saved_routes ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 新增轨迹表：独立于普通路线，不影响 saved_routes 的结构与数据
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trajectories (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "pointsJson TEXT NOT NULL, " +
                        "durationSec REAL NOT NULL, " +
                        "distanceM REAL NOT NULL, " +
                        "pointCount INTEGER NOT NULL, " +
                        "timestamp INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS collection_routes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, remark TEXT NOT NULL, pointsJson TEXT NOT NULL, halfWidthM REAL NOT NULL, timestamp INTEGER NOT NULL)")
                db.execSQL("ALTER TABLE location_records ADD COLUMN collectionRouteId INTEGER REFERENCES collection_routes(id) ON DELETE CASCADE")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_records_collectionRouteId ON location_records(collectionRouteId)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "environment_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
