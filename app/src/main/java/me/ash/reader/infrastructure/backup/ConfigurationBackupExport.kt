package me.ash.reader.infrastructure.backup

import kotlinx.serialization.encodeToString
import me.ash.reader.BuildConfig
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.ui.ext.exportConfigurationPreferencesJSONString
import me.ash.reader.ui.ext.getDefaultGroupId

/** 导出时捕获的账户和订阅，后续字段转换不再重新读取当前账户。 */
internal data class ConfigurationExportSnapshot(
    val account: Account, val groups: List<Group>, val feeds: List<Feed>,
)

/** 来源配置使用真实 feedId 限定范围，JSON 成功规则绑定与其它来源偏好一起进入完整备份。 */
internal suspend fun ConfigurationBackupService.createBackupEnvelope(
    snapshot: ConfigurationExportSnapshot, secrets: EncryptedBackupSecrets?,
): ConfigurationBackup {
    val account = snapshot.account
    val accountId = requireNotNull(account.id)
    val feedIds = snapshot.feeds.mapTo(hashSetOf(), Feed::id)
    return ConfigurationBackup(
        sourceVersion = BuildConfig.VERSION_NAME, createdAtEpochMillis = System.currentTimeMillis(),
        preferences = json.parseToJsonElement(context.exportConfigurationPreferencesJSONString()),
        accountSettings = AccountSettingsBackup(
            syncIntervalMinutes = account.syncInterval.value, syncOnStart = account.syncOnStart.value,
            syncOnlyOnWiFi = account.syncOnlyOnWiFi.value, syncOnlyWhenCharging = account.syncOnlyWhenCharging.value,
            keepArchivedMillis = account.keepArchived.value, syncBlockList = account.syncBlockList,
        ),
        subscriptions = SubscriptionBackup(
            sourceAccountId = accountId,
            groups = snapshot.groups.map { BackupGroup(it.id, it.name, it.id == accountId.getDefaultGroupId()) },
            feeds = snapshot.feeds.map { it.toBackup() },
        ),
        websiteRules = json.parseToJsonElement(websiteRuleRepository.exportRules()),
        jsonRules = json.parseToJsonElement(jsonRuleRepository.exportBackup(feedIds)),
        articleFilters = json.parseToJsonElement(articleFilterRepository.exportRules()),
        websiteParsePreferences = json.parseToJsonElement(websiteParsePreferenceRepository.exportBackup(feedIds)),
        rssHub = rssHubSettingsRepository.current().toBackup(),
        rssHubSourceUrls = rssHubSubscriptionRepository.exportMappings(feedIds),
        rssHubSubscriptions = rssHubSubscriptionRepository.exportDescriptors(feedIds)
            .mapValues { (_, descriptor) -> descriptor.toBackup() },
        translation = translationSettingsRepository.current().toBackup(), ai = aiSettingsRepository.current().toBackup(),
        editionConfiguration = editionBackupExtension.exportConfiguration(), encryptedSecrets = secrets,
    )
}

/** 仅在用户选择包含凭据时采集并加密，密码和明文不写入日志。 */
internal fun ConfigurationBackupService.exportEncryptedSecrets(password: String): EncryptedBackupSecrets {
    val payload = ConfigurationBackupSecrets(
        translationApiKeys = TranslationProviderType.entries.mapNotNull { type ->
            translationSettingsRepository.getApiKey(type).takeIf(String::isNotBlank)?.let { type.name to it }
        }.toMap(),
        aiApiKeys = aiSettingsRepository.current().providers.mapNotNull { provider ->
            aiSettingsRepository.getApiKey(provider.id).takeIf(String::isNotBlank)?.let { provider.id to it }
        }.toMap(),
        editionSecrets = editionBackupExtension.exportSecrets(),
    )
    return ConfigurationBackupCrypto.encrypt(json.encodeToString(payload), password)
}
