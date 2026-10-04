package me.ash.reader.infrastructure.json

import kotlinx.serialization.Serializable

/** 可导入的 JSON/API 来源规则集合。 */
@Serializable
data class JsonRuleBundle(
    val schemaVersion: Int = 1,
    val rules: List<JsonRule>,
    /** 每个来源保留成功探测的规则身份、原页面与实际请求地址。 */
    val subscriptions: Map<String, JsonSourceBinding> = emptyMap(),
)

/** 导入规则按 ID 跟踪停用/删除；内置规则保存定义以恢复子目录 endpoint。 */
@Serializable
data class JsonSourceBinding(
    val sourcePageUrl: String,
    val endpointUrl: String,
    val rule: JsonRule,
    val importedRule: Boolean,
)

/** JSON 数据实际所在位置。旧规则未声明时默认继续按独立 API 处理。 */
@Serializable
enum class JsonSourceKind {
    API,
    NEXT_DATA,
    NUXT_DATA,
}

/**
 * JSON/API 文章列表规则。路径使用受限 JSONPath：支持 $.a.b、数组下标和 [*]。
 */
@Serializable
data class JsonRule(
    val id: String,
    val name: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    val hosts: List<String>,
    val sourceKind: JsonSourceKind = JsonSourceKind.API,
    val endpoint: String,
    val itemsPath: String,
    val titlePath: String,
    val linkPath: String,
    val datePath: String? = null,
    val authorPath: String? = null,
    val descriptionPath: String? = null,
    /** 可选正文路径；未声明时兼容旧规则，继续使用 descriptionPath。 */
    val contentPath: String? = null,
    val imagePath: String? = null,
    val idPath: String? = null,
    val dateFormat: String? = null,
    /** 无偏移日期所属时区；未声明时保留用户规则原有的设备本地时区语义。 */
    val dateTimeZone: String? = null,
    val maxItems: Int = 50,
)

