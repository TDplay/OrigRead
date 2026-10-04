package me.ash.reader.infrastructure.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 历史订阅索引与默认分组关系修复，升级顺序及 SQL 与原实现相同。 */
@Suppress("ClassName")
object MIGRATION_9_10 : Migration(9, 10) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_archived_article_feedId` ON `archived_article` (`feedId`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_article_feedId_link` ON `article` (`feedId`, `link`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_article_accountId_isUnread_date` ON `article` (`accountId`, `isUnread`, `date`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_article_accountId_isStarred_date` ON `article` (`accountId`, `isStarred`, `date`)"
        )
    }
}

/**
 * 将早期 Read You 派生的默认分组稳定 ID 正式迁移为 OrigRead ID。
 *
 * 注意：不能依赖 `feed.groupId -> group.id` 的 ON UPDATE CASCADE。
 * Room 当前是在 migration 完成后的 onOpen 才执行 `PRAGMA foreign_keys = ON`，因此正式版 v10
 * 升级时 migration 连接上的外键可能尚未启用。这里必须显式同时迁移 group.id 与 feed.groupId，
 * 并且 SQL 顺序要同时兼容“外键已启用”和“外键未启用”两种运行环境。
 *
 * 该 Migration 属于历史升级链。后续可以删除运行时品牌迁移，但不要删除本迁移，
 * 否则仍停留在 DB v10 的用户将无法直接跨版本升级。
 */
@Suppress("ClassName")
object MIGRATION_10_11 : Migration(10, 11) {

    override fun migrate(database: SupportSQLiteDatabase) {
        // Room 内的数据品牌迁移放在数据库升级链中，保证用户即使跳过中间版本，
        // 未来从 DB v10 直接升级到更高版本时仍会完成迁移。
        database.execSQL(
            """
            UPDATE `account`
            SET `name` = 'OrigRead'
            WHERE `type` = 1 AND `name` = 'Read You'
            """.trimIndent()
        )
        database.execSQL(
            """
            UPDATE `feed`
            SET `name` = 'OrigRead Releases',
                `icon` = 'https://github.com/ZGMFX01A.png',
                `url` = 'https://github.com/ZGMFX01A/OrigRead/releases.atom'
            WHERE `url` = 'https://github.com/ReadYouApp/ReadYou/releases.atom'
            """.trimIndent()
        )

        migrateDefaultGroupRelations(database)

    }
}

/**
 * 修复曾安装过缺陷版 DB v11 测试包的数据库。
 *
 * 缺陷版 MIGRATION_10_11 在 migration 阶段外键未开启时只修改了 group.id，可能遗留：
 * - group.id = `<account>$origread_app_default_group`
 * - feed.groupId 仍为 `<account>$read_you_app_default_group`
 *
 * 这种 Feed 行本身仍存在，所以旧文章仍能通过 feedId 显示来源；但来源列表按 Group -> Feed Relation
 * 组装，因此这些孤儿 Feed 会完全消失。v12 只修复这一已知默认分组引用，不碰用户自建分组。
 */
@Suppress("ClassName")
object MIGRATION_11_12 : Migration(11, 12) {

    override fun migrate(database: SupportSQLiteDatabase) {
        // 同样兼容少量异常数据：若旧默认分组仍存在且新 ID 尚不存在，先安全改名。
        database.execSQL(
            """
            UPDATE `group`
            SET `id` = CAST(`accountId` AS TEXT) || '$' || 'origread_app_default_group'
            WHERE `id` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
              AND NOT EXISTS (
                  SELECT 1 FROM `group` AS `new_group`
                  WHERE `new_group`.`id` = CAST(`group`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
              )
            """.trimIndent()
        )

        // 核心修复：把缺陷版 v11 遗留的孤儿 Feed 重新挂回当前默认分组。
        database.execSQL(
            """
            UPDATE `feed`
            SET `groupId` = CAST(`accountId` AS TEXT) || '$' || 'origread_app_default_group'
            WHERE `groupId` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
              AND EXISTS (
                  SELECT 1 FROM `group`
                  WHERE `group`.`id` = CAST(`feed`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
              )
            """.trimIndent()
        )

        database.execSQL(
            """
            DELETE FROM `group`
            WHERE `id` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
              AND EXISTS (
                  SELECT 1 FROM `group` AS `new_group`
                  WHERE `new_group`.`id` = CAST(`group`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
              )
            """.trimIndent()
        )
    }
}

/** 保持旧默认组升级的原始 SQL 及顺序，拆分仅用于限制方法长度。 */
private fun migrateDefaultGroupRelations(database: SupportSQLiteDatabase) {
    renameUnconflictedDefaultGroups(database)
    relinkDefaultGroupFeeds(database)
    removeMergedDefaultGroups(database)
}

/** 先迁移不存在新 ID 冲突的旧默认组，外键是否开启均可进入后续步骤。 */
private fun renameUnconflictedDefaultGroups(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        UPDATE `group`
        SET `id` = CAST(`accountId` AS TEXT) || '$' || 'origread_app_default_group'
        WHERE `id` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
          AND NOT EXISTS (
              SELECT 1 FROM `group`
              AS `new_group`
              WHERE `new_group`.`id` = CAST(`group`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
          )
        """.trimIndent()
    )
}

/** 显式修正来源分组引用，覆盖迁移连接尚未启用外键的场景。 */
private fun relinkDefaultGroupFeeds(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        UPDATE `feed`
        SET `groupId` = CAST(`accountId` AS TEXT) || '$' || 'origread_app_default_group'
        WHERE `groupId` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
          AND EXISTS (
              SELECT 1 FROM `group`
              WHERE `group`.`id` = CAST(`feed`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
          )
        """.trimIndent()
    )
}

/** 来源完成迁出后再删除旧默认组，防止外键级联删除订阅。 */
private fun removeMergedDefaultGroups(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        DELETE FROM `group`
        WHERE `id` = CAST(`accountId` AS TEXT) || '$' || 'read_you_app_default_group'
          AND EXISTS (
              SELECT 1 FROM `group` AS `new_group`
              WHERE `new_group`.`id` = CAST(`group`.`accountId` AS TEXT) || '$' || 'origread_app_default_group'
          )
        """.trimIndent()
    )
}
