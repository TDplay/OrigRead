package me.ash.reader.infrastructure.json

/** API 使用相对地址解析；内嵌 JSON 始终读取原页面，忽略规则的占位 endpoint。 */
internal fun JsonRule.requestEndpoint(
    inputUrl: String,
    resolve: (String, String) -> String,
): String = if (sourceKind == JsonSourceKind.API) resolve(inputUrl, endpoint) else inputUrl
