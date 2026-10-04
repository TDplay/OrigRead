package me.ash.reader.infrastructure.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 为归档保留文章稳定身份，旧记录保留原链接且新增字段为 null，不删除任何历史数据。 */
@Suppress("ClassName")
object MIGRATION_12_13 : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `archived_article` ADD COLUMN `articleId` TEXT")
    }
}
