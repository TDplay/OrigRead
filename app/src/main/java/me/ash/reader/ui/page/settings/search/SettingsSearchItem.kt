package me.ash.reader.ui.page.settings.search

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import me.ash.reader.ui.page.nav3.key.Route

/**
 * 设置项搜索元数据模型。
 *
 * @property id 唯一标识符，用于去重和稳定性标识
 * @property titleRes 设置项标题字符串资源 ID
 * @property descRes 设置项描述或辅助说明字符串资源 ID（可选）
 * @property breadcrumbsRes 面包屑分类层级字符串资源 ID 列表，例如：[R.string.settings, R.string.interaction, R.string.article_list]
 * @property route 点击后导航的目标路由
 * @property icon 设置所属分类的大图标
 * @property keywords 自定义近义词关键词列表（便于扩展搜索）
 * @property isAvailable 运行时条件过滤（例如部分功能仅在 GitHub 渠道有效）
 */
data class SettingsSearchItem(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int? = null,
    val breadcrumbsRes: List<Int>,
    val route: Route,
    val icon: ImageVector,
    val keywords: List<String> = emptyList(),
    val isAvailable: () -> Boolean = { true },
)

/**
 * 设置项搜索结果包装模型，包含解析后的本地化文本与匹配度得分。
 *
 * @property item 原始搜索元数据
 * @property title 本地化标题
 * @property desc 本地化描述（若有）
 * @property breadcrumbText 格式化后的面包屑路径文本（如 "设置 > 交互 > 文章列表"）
 * @property score 检索匹配度评分，数值越高越靠前
 */
data class SettingsSearchResult(
    val item: SettingsSearchItem,
    val title: String,
    val desc: String?,
    val breadcrumbText: String,
    val score: Int,
)
