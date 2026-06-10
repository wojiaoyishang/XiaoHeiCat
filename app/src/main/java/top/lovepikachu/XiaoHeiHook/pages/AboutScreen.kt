package top.lovepikachu.XiaoHeiHook.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.composables.AccessibilityKeepAliveCard
import top.lovepikachu.XiaoHeiHook.composables.AnimatedPressIcon
import top.lovepikachu.XiaoHeiHook.composables.FullWidthTextButton
import top.lovepikachu.XiaoHeiHook.composables.McpSettingsCard
import top.lovepikachu.XiaoHeiHook.composables.ScriptRootSettingsCard
import top.lovepikachu.XiaoHeiHook.composables.WebIdeSettingsCard
import top.lovepikachu.XiaoHeiHook.composables.WebIdeLogMaintenanceCard
import top.lovepikachu.XiaoHeiHook.ui.material.AppCard
import top.lovepikachu.XiaoHeiHook.ui.material.AppPageTitle

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        item { AppPageTitle(title = stringResource(R.string.settings_title)) }

        item { WebIdeSettingsCard() }

        item { McpSettingsCard() }

        item { AccessibilityKeepAliveCard() }

        item { ScriptRootSettingsCard() }

        item { WebIdeLogMaintenanceCard() }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.about_module_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_module_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    FullWidthTextButton(
                        text = stringResource(R.string.about_website),
                        onClick = { uriHandler.openUri("https://lovepikachu.top") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FullWidthTextButton(
                        text = stringResource(R.string.about_open_source_repository),
                        onClick = { uriHandler.openUri("https://github.com/wojiaoyishang/XiaoHeiCat") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FullWidthTextButton(
                        text = stringResource(R.string.about_open_documentation),
                        onClick = { uriHandler.openUri("https://lab.lovepikachu.top/document/xiaoheihook") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(72.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedPressIcon(
                    painter = painterResource(id = R.drawable.nikocat),
                    onClick = {},
                    size = 156.dp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.about_niko_caption),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(120.dp))
        }
    }
}
