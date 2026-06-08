package top.lovepikachu.XiaoHeiHook.pages

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.lovepikachu.XiaoHeiHook.BuildConfig
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.composables.ActivationStatusCard
import top.lovepikachu.XiaoHeiHook.composables.AnimatedPressIcon
import top.lovepikachu.XiaoHeiHook.composables.FullWidthTextButton
import top.lovepikachu.XiaoHeiHook.data.ScriptRepository
import top.lovepikachu.XiaoHeiHook.ui.material.AppCard
import top.lovepikachu.XiaoHeiHook.ui.material.AppPageTitle

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val unknownText = stringResource(R.string.unknown)

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        item {
            AppPageTitle(title = stringResource(R.string.app_name))
        }

        item { ActivationStatusCard() }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HomeInfoItem(
                        icon = Icons.Filled.Schedule,
                        title = stringResource(R.string.home_version),
                        value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    )
                    HomeInfoItem(
                        icon = Icons.Filled.PhoneAndroid,
                        title = stringResource(R.string.home_android_version),
                        value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                    )
                    HomeInfoItem(
                        icon = Icons.Filled.Code,
                        title = stringResource(R.string.home_system_arch),
                        value = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { unknownText }
                    )
                    HomeInfoItem(
                        icon = Icons.Filled.Extension,
                        title = stringResource(R.string.home_script_directory),
                        value = ScriptRepository.publicScriptsDir.absolutePath
                    )
                }
            }
        }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.fast_operation),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.home_lsposed_action_description),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    Box(
                        modifier = Modifier.weight(0.34f),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        FullWidthTextButton(
                            text = stringResource(R.string.common_open),
                            onClick = {
                                Thread {
                                    try {
                                        val command =
                                            "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android"
                                        Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
                                        Handler(Looper.getMainLooper()).post {
                                            Toast.makeText(context, context.getString(R.string.home_lsposed_open_success), Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Handler(Looper.getMainLooper()).post {
                                            Toast.makeText(context, context.getString(R.string.home_lsposed_open_failed), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }.start()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedPressIcon(
                    painter = painterResource(id = R.drawable.xiaoheiatstone),
                    onClick = {},
                    size = 104.dp
                )
            }
        }
    }
}

@Composable
private fun HomeInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
