package me.ash.reader.ui.page.home.feeds.drawer.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonSourceProbeResult

/** 已有来源设置复用真实 JSON 探测，用户确认前只展示候选。 */
@Composable
internal fun JsonParserPanel(state: FeedOptionUiState, viewModel: FeedOptionViewModel) {
    val repair = state.jsonRepair
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.json_parser_title), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                IconButton(enabled = !repair.isSaving, onClick = viewModel::hideJsonParserDialog) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
            Text(stringResource(R.string.json_repair_description), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            JsonRepairInput(repair, viewModel)
            repair.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(repair.candidates, key = { it.rule.id }) { candidate ->
            JsonRepairCandidate(candidate, repair, viewModel)
        }
        item {
            TextButton(enabled = !repair.isLoading && repair.selectedRuleId != null,
                onClick = viewModel::confirmJsonRepair) { Text(stringResource(R.string.json_repair_confirm)) }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.json_parser_desc), color = MaterialTheme.colorScheme.outline)
        }
        items(state.jsonConfiguredRules, key = { "configured-${it.id}" }) { rule ->
            JsonConfiguredRule(rule, repair.isLoading, viewModel)
        }
    }
}

/** 修复页面地址可以修改，输入变化会取消旧探测并清除旧候选。 */
@Composable
private fun JsonRepairInput(state: JsonSourceRepairState, viewModel: FeedOptionViewModel) {
    OutlinedTextField(value = state.sourcePageUrl, onValueChange = viewModel::inputJsonSourceUrl,
        enabled = !state.isSaving, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.json_repair_page_url)) })
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = !state.isLoading && state.sourcePageUrl.isNotBlank(), onClick = viewModel::probeJsonSource) {
            Text(stringResource(R.string.json_repair_probe))
        }
        if (state.isLoading) CircularProgressIndicator()
    }
}

/** 候选显式展示规则和实际请求地址，选择不立即保存。 */
@Composable
private fun JsonRepairCandidate(candidate: JsonSourceProbeResult, state: JsonSourceRepairState, viewModel: FeedOptionViewModel) {
    Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isLoading) {
        viewModel.selectJsonProbe(candidate.rule.id)
    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = candidate.rule.id == state.selectedRuleId, enabled = !state.isLoading,
            onClick = { viewModel.selectJsonProbe(candidate.rule.id) })
        Column {
            Text(candidate.rule.name)
            Text(candidate.endpointUrl, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 规则启停沿用既有管理能力，与当前来源的绑定选择分开呈现。 */
@Composable
private fun JsonConfiguredRule(rule: JsonRule, loading: Boolean, viewModel: FeedOptionViewModel) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = rule.enabled, enabled = !loading, onCheckedChange = { viewModel.setJsonRuleEnabled(rule, it) })
        Column {
            Text(rule.name)
            Text(rule.sourceKind.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
