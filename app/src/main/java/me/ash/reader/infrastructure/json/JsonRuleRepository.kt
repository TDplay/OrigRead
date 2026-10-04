package me.ash.reader.infrastructure.json

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.time.ZoneId
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 保存并读取用户导入的 JSON/API 来源规则。 */
@Singleton
class JsonRuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** 旧订阅只允许唯一的精确 endpoint 匹配，不以域名第一条规则猜测来源身份。 */
    fun findRuleForEndpoint(endpointUrl: String): JsonRule? {
        val matches = findRules(endpointUrl).filter { resolveEndpoint(endpointUrl, it.endpoint) == endpointUrl }
        require(matches.size <= 1) { "JSON 来源匹配多条规则，请重新探测并选择：$endpointUrl" }
        return matches.singleOrNull()
    }

    /** 在来源入库前持久化成功规则，文件写入失败必须阻止订阅被报告成功。 */
    @Synchronized
    fun bindSubscription(feedId: String, sourcePageUrl: String, result: JsonSourceProbeResult) {
        require(feedId.isNotBlank()) { "JSON 来源 ID 不能为空" }
        validateRule(result.rule)
        val bundle = loadBundle()
        val binding = JsonSourceBinding(
            sourcePageUrl, result.endpointUrl, result.rule,
            importedRule = result.importedRule,
        )
        writeBundle(bundle.copy(subscriptions = bundle.subscriptions + (feedId to binding)))
    }

    /** 固定规则删除或停用时显式失败，不能静默换成其他规则。 */
    @Synchronized
    fun subscriptionRule(feedId: String, endpointUrl: String): JsonRule? {
        val bundle = loadBundle()
        val binding = bundle.subscriptions[feedId] ?: return null
        require(binding.endpointUrl == endpointUrl) { "JSON 请求地址已改变，请重新探测来源" }
        val rule = if (binding.importedRule) {
            bundle.rules.firstOrNull { it.id == binding.rule.id }
                ?: error("JSON 来源规则已删除：${binding.rule.name}")
        } else binding.rule
        require(rule.enabled) { "JSON 来源规则已停用：${rule.name}" }
        val resolvedEndpoint = if (rule.sourceKind == JsonSourceKind.API) {
            resolveEndpoint(binding.sourcePageUrl, rule.endpoint)
        } else binding.sourcePageUrl
        require(rule.sourceKind == binding.rule.sourceKind && resolvedEndpoint == endpointUrl) {
            "JSON 规则的请求方式或地址已改变，请重新探测来源：${rule.name}"
        }
        return rule
    }

    fun resolveEndpoint(inputUrl: String, endpoint: String): String =
        URI(inputUrl).resolve(endpoint).toString()

    private val ruleFile
        get() = context.filesDir.resolve("json-source-rules.json")

    fun listRules(): List<JsonRule> = loadRules().sortedBy(JsonRule::name)

    fun findRules(url: String): List<JsonRule> {
        return findConfiguredRules(url).filter(JsonRule::enabled)
    }

    /** 返回与地址匹配的全部 JSON 规则，供来源设置展示启用/停用状态。 */
    fun findConfiguredRules(url: String): List<JsonRule> {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return loadRules().filter { rule ->
            rule.hosts.any { expected ->
                host == expected.lowercase() || host.endsWith(".${expected.lowercase()}")
            }
        }
    }

    @Synchronized
    fun importRules(content: String): Int {
        val incoming = json.decodeFromString<JsonRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的 JSON 规则版本：${incoming.schemaVersion}" }
        incoming.rules.forEach(::validateRule)
        val incomingAiHosts = incoming.rules
            .asSequence()
            .filter { it.id.startsWith(AI_RULE_ID_PREFIX) }
            .flatMap { it.hosts.asSequence() }
            .toList()
        val existing = loadRules().filterNot { existingRule ->
            existingRule.id.startsWith(AI_RULE_ID_PREFIX) &&
                incomingAiHosts.any { incomingHost -> hostsOverlap(existingRule.hosts, listOf(incomingHost)) }
        }
        val merged = (existing + incoming.rules).associateBy(JsonRule::id).values.toList()
        writeRules(merged)
        return incoming.rules.size
    }

    /** 仅校验完整备份中的 JSON/API 规则，不写入本地。 */
    fun validateBackup(content: String) {
        val incoming = json.decodeFromString<JsonRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的 JSON 规则版本：${incoming.schemaVersion}" }
        incoming.rules.forEach(::validateRule)
        incoming.subscriptions.values.forEach { validateRule(it.rule) }
    }

    /** 完整配置恢复时使用备份规则集替换当前用户规则。 */
    @Synchronized
    fun restoreBackup(content: String, feedIdMap: Map<String, String>? = null): Int {
        val incoming = json.decodeFromString<JsonRuleBundle>(content)
        require(incoming.schemaVersion == 1) { "不支持的 JSON 规则版本：${incoming.schemaVersion}" }
        incoming.rules.forEach(::validateRule)
        // 未提供映射表示精确恢复规则文件；配置合并恢复则保留未参与本次迁移的现有来源绑定。
        val subscriptions = if (feedIdMap == null) incoming.subscriptions else {
            loadBundle().subscriptions +
            incoming.subscriptions.mapNotNull { (oldId, binding) ->
                feedIdMap[oldId]?.let { it to binding }
            }.toMap()
        }
        writeBundle(incoming.copy(subscriptions = subscriptions))
        return incoming.rules.size
    }

    /** 供 AI 候选生成等入口复用与手工导入完全相同的规则校验，不执行持久化。 */
    fun validateCandidate(rule: JsonRule) = validateRule(rule)

    /** 用户确认 AI 候选后保存单条 JSON 规则；保存前再次校验 schema 与受限 JSONPath。 */
    @Synchronized
    fun saveRule(rule: JsonRule) {
        validateRule(rule)
        val existing = loadRules().filterNot { existingRule ->
            existingRule.id.startsWith(AI_RULE_ID_PREFIX) &&
                rule.id.startsWith(AI_RULE_ID_PREFIX) &&
                existingRule.id != rule.id &&
                hostsOverlap(existingRule.hosts, rule.hosts)
        }
        val merged = (existing + rule).associateBy(JsonRule::id).values.toList()
        writeRules(merged)
    }

    fun exportRules(): String = json.encodeToString(JsonRuleBundle(rules = listRules()))

    /** 完整配置备份额外携带来源绑定；手动规则导出仍只包含规则。 */
    fun exportBackup(feedIds: Set<String>? = null): String {
        val bundle = loadBundle()
        return json.encodeToString(if (feedIds == null) bundle else {
            bundle.copy(subscriptions = bundle.subscriptions.filterKeys { it in feedIds })
        })
    }

    @Synchronized
    fun setEnabled(ruleId: String, enabled: Boolean) {
        writeRules(loadRules().map { rule ->
            if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
        })
    }

    @Synchronized
    fun deleteRule(ruleId: String) {
        writeRules(loadRules().filterNot { it.id == ruleId })
    }

    fun exportTemplate(): String =
        json.encodeToString(
            JsonRuleBundle(
                rules = listOf(
                    JsonRule(
                        id = "example-json-api",
                        name = "Example JSON API",
                        hosts = listOf("example.com"),
                        endpoint = "/api/posts",
                        itemsPath = "$.data.items[*]",
                        titlePath = "$.title",
                        linkPath = "$.url",
                        datePath = "$.publishedAt",
                        authorPath = "$.author.name",
                        descriptionPath = "$.summary",
                        contentPath = "$.content",
                        imagePath = "$.cover",
                        idPath = "$.id",
                    )
                )
            )
        )

    private fun validateRule(rule: JsonRule) {
        require(rule.id.isNotBlank()) { "规则 id 不能为空" }
        require(rule.name.isNotBlank()) { "规则名称不能为空" }
        require(rule.hosts.isNotEmpty()) { "规则至少需要一个 hosts" }
        rule.hosts.forEach { host ->
            require(HOST_REGEX.matches(host)) { "hosts 只能填写纯域名：$host" }
        }
        require(rule.endpoint.isNotBlank()) { "endpoint 不能为空" }
        rule.dateTimeZone?.let { ZoneId.of(it) }
        require(rule.maxItems in 1..200) { "maxItems 必须在 1 到 200 之间" }
        listOf(rule.itemsPath, rule.titlePath, rule.linkPath)
            .forEach { path -> SimpleJsonPath.query(kotlinx.serialization.json.JsonNull, path) }
        listOfNotNull(
            rule.datePath,
            rule.authorPath,
            rule.descriptionPath,
            rule.contentPath,
            rule.imagePath,
            rule.idPath,
        ).forEach { path -> SimpleJsonPath.query(kotlinx.serialization.json.JsonNull, path) }
    }

    private fun loadRules(): List<JsonRule> = loadBundle().rules

    /** 空文件夹代表尚未配置；已有文件损坏直接报错，不清空规则及来源绑定。 */
    private fun loadBundle(): JsonRuleBundle = if (!ruleFile.exists()) JsonRuleBundle(rules = emptyList())
        else json.decodeFromString(ruleFile.readText())

    private fun writeRules(rules: List<JsonRule>) {
        writeBundle(loadBundle().copy(rules = rules))
    }

    /** 规则和绑定写入同一个原子文件，避免中断留下截断 JSON。 */
    private fun writeBundle(bundle: JsonRuleBundle) {
        val staging = ruleFile.resolveSibling("${ruleFile.name}.new")
        staging.writeText(json.encodeToString(bundle))
        Files.move(staging.toPath(), ruleFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun hostsOverlap(left: List<String>, right: List<String>): Boolean =
        left.any { leftHost ->
            right.any { rightHost ->
                val a = leftHost.lowercase()
                val b = rightHost.lowercase()
                a == b || a.endsWith(".$b") || b.endsWith(".$a")
            }
        }

    private companion object {
        const val AI_RULE_ID_PREFIX = "ai-json-"
        val HOST_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")
    }
}

