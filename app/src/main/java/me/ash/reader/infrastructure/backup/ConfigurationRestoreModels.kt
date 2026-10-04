package me.ash.reader.infrastructure.backup

import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.ai.AiSettings
import me.ash.reader.infrastructure.preference.KeepArchivedPreference
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import me.ash.reader.infrastructure.preference.SyncOnStartPreference
import me.ash.reader.infrastructure.preference.SyncOnlyOnWiFiPreference
import me.ash.reader.infrastructure.preference.SyncOnlyWhenChargingPreference
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.translation.TranslationSettings

internal data class ValidatedAccountSettings(
    val syncInterval: SyncIntervalPreference,
    val syncOnStart: SyncOnStartPreference,
    val syncOnlyOnWiFi: SyncOnlyOnWiFiPreference,
    val syncOnlyWhenCharging: SyncOnlyWhenChargingPreference,
    val keepArchived: KeepArchivedPreference,
    val syncBlockList: List<String>,
)

/** 完成所有无副作用校验后的恢复计划，写入阶段不得再重新解释外部 JSON。 */
internal data class PreparedConfigurationRestore(
    val backup: ConfigurationBackup,
    val secrets: ConfigurationBackupSecrets?,
    val preferencesJson: String,
    val websiteRulesJson: String,
    val jsonRulesJson: String,
    val filterRulesJson: String,
    val websitePreferencesJson: String,
    val rssHubSettings: RssHubSettings,
    val translationSettings: TranslationSettings,
    val aiSettings: AiSettings,
    val accountSettings: ValidatedAccountSettings,
)

/** 失败补偿需要同时覆盖配置仓库与 Room 实体，避免 merge 过程中新增的 Group/Feed 残留。 */
internal data class ConfigurationRestoreRollbackSnapshot(
    val accountId: Int,
    val groups: List<Group>,
    val feeds: List<Feed>,
    val configurationBackupJson: String,
    /** 补偿需精确恢复全规则文件，包含其他账户的来源绑定。 */
    val jsonRulesSnapshot: String,
    val password: String,
)
