package me.ash.reader.infrastructure.backup

import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiCapabilityOverrideMode
import me.ash.reader.infrastructure.ai.AiOutputTokenLimitStyle
import me.ash.reader.infrastructure.ai.AiSettings
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.infrastructure.rsshub.RssHubInstance
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionDescriptor
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import me.ash.reader.infrastructure.translation.TranslationProviderSettings
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationSettings
import me.ash.reader.infrastructure.translation.TranslationTarget

internal fun Feed.toBackup() =
    BackupFeed(
        id = id,
        name = name,
        icon = icon,
        url = url,
        groupId = groupId,
        isNotification = isNotification,
        isFullContent = isFullContent,
        isBrowser = isBrowser,
        sourceType = sourceType.name,
    )

internal fun RssHubSettings.toBackup() =
    RssHubBackup(
        enabled = enabled,
        instances =
            instances.map { instance ->
                RssHubInstanceBackup(
                    id = instance.id,
                    url = instance.url,
                    location = instance.location,
                    maintainer = instance.maintainer,
                    enabled = instance.enabled,
                    builtIn = instance.builtIn,
                )
            },
    )

internal fun RssHubBackup.toSettings() =
    RssHubSettings(
        enabled = enabled,
        instances =
            instances.map { instance ->
                require(instance.url.isNotBlank()) { "RSSHub 实例地址不能为空" }
                RssHubInstance(
                    id = instance.id,
                    url = RssHubSettingsRepository.normalizeInstanceUrl(instance.url),
                    location = instance.location,
                    maintainer = instance.maintainer,
                    enabled = instance.enabled,
                    builtIn = instance.builtIn,
                )
            },
    )

internal fun RssHubSubscriptionDescriptor.toBackup() =
    RssHubSubscriptionBackup(
        originalInput = originalInput,
        routePath = routePath,
        preferredInstance = preferredInstance,
        lastResolvedInstance = lastResolvedInstance,
        lastResolvedUrl = lastResolvedUrl,
    )

internal fun RssHubSubscriptionBackup.toDescriptor() =
    RssHubSubscriptionDescriptor(
        originalInput = originalInput,
        routePath = routePath,
        preferredInstance = preferredInstance,
        lastResolvedInstance = lastResolvedInstance,
        lastResolvedUrl = lastResolvedUrl,
    )

internal fun TranslationSettings.toBackup() =
    TranslationBackup(
        defaultProvider = defaultProvider.name,
        defaultTarget =
            when (val target = defaultTarget) {
                is TranslationTarget.Traditional ->
                    TranslationTargetBackup(type = "traditional", provider = target.provider.name)
                is TranslationTarget.Ai ->
                    TranslationTargetBackup(
                        type = "ai",
                        providerId = target.providerId,
                        providerName = target.providerName,
                        model = target.model,
                    )
            },
        targetLanguage = targetLanguage,
        displayMode = displayMode.name,
        providers =
            TranslationProviderType.entries.map { type ->
                val provider = provider(type)
                TranslationProviderBackup(
                    type = type.name,
                    enabled = provider.enabled,
                    endpoint = provider.endpoint,
                    region = provider.region,
                )
            },
    )

internal fun TranslationBackup.toSettings(): TranslationSettings {
    val providers =
        providers.associate { item ->
            val type = TranslationProviderType.valueOf(item.type)
            type to
                TranslationProviderSettings(
                    enabled = item.enabled,
                    endpoint = item.endpoint,
                    region = item.region,
                )
        }
    val defaultProvider = TranslationProviderType.valueOf(defaultProvider)
    val target =
        when (defaultTarget.type) {
            "traditional" ->
                TranslationTarget.Traditional(
                    TranslationProviderType.valueOf(requireNotNull(defaultTarget.provider))
                )
            "ai" ->
                TranslationTarget.Ai(
                    providerId = requireNotNull(defaultTarget.providerId),
                    providerName = defaultTarget.providerName.orEmpty().ifBlank { "AI" },
                    model = requireNotNull(defaultTarget.model),
                )
            else -> error("未知翻译目标类型：${defaultTarget.type}")
        }
    return TranslationSettings(
        defaultProvider = defaultProvider,
        defaultTarget = target,
        targetLanguage = targetLanguage,
        displayMode = TranslationDisplayMode.valueOf(displayMode),
        providers = TranslationSettings.defaultProviderSettings() + providers,
    )
}

internal fun AiSettings.toBackup() =
    AiBackup(
        enabled = enabled,
        defaultProviderId = defaultProviderId,
        outputLanguage = outputLanguage,
        summaryLength = summaryLength.name,
        providers =
            providers.map { provider ->
                AiProviderBackup(
                    id = provider.id,
                    name = provider.name,
                    enabled = provider.enabled,
                    endpoint = provider.endpoint,
                    defaultModel = provider.defaultModel,
                    models = provider.models,
                    streamingCapabilityOverride = provider.streamingCapabilityOverride.name,
                    toolCallingCapabilityOverride = provider.toolCallingCapabilityOverride.name,
                    reasoningCapabilityOverride = provider.reasoningCapabilityOverride.name,
                    outputTokenLimitStyle = provider.outputTokenLimitStyle.name,
                    contextWindowTokens = provider.contextWindowTokens,
                    strictStreamTermination = provider.strictStreamTermination,
                )
            },
    )

internal fun AiBackup.toSettings(): AiSettings {
    require(providers.isNotEmpty()) { "AI 配置至少需要一个 Provider" }
    require(providers.map(AiProviderBackup::id).distinct().size == providers.size) {
        "AI 配置包含重复 Provider ID"
    }
    val restoredProviders =
        providers.map { provider ->
            require(provider.id.isNotBlank() && provider.endpoint.isNotBlank()) { "备份包含无效 AI Provider" }
            AiProviderProfile(
                id = provider.id,
                name = provider.name,
                enabled = provider.enabled,
                endpoint = provider.endpoint,
                defaultModel = provider.defaultModel,
                models = provider.models,
                streamingCapabilityOverride =
                    AiCapabilityOverrideMode.valueOf(provider.streamingCapabilityOverride),
                toolCallingCapabilityOverride =
                    AiCapabilityOverrideMode.valueOf(provider.toolCallingCapabilityOverride),
                reasoningCapabilityOverride =
                    AiCapabilityOverrideMode.valueOf(provider.reasoningCapabilityOverride),
                outputTokenLimitStyle =
                    AiOutputTokenLimitStyle.valueOf(provider.outputTokenLimitStyle),
                contextWindowTokens = provider.contextWindowTokens,
                strictStreamTermination = provider.strictStreamTermination,
            )
        }
    return AiSettings(
        enabled = enabled,
        providers = restoredProviders,
        defaultProviderId = defaultProviderId,
        outputLanguage = outputLanguage,
        summaryLength = AiSummaryLength.valueOf(summaryLength),
    )
}
