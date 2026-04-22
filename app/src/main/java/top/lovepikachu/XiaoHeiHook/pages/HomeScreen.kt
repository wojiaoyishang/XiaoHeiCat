package top.lovepikachu.XiaoHeiHook.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.composables.ActivationStatusCard
import top.lovepikachu.XiaoHeiHook.composables.AnimatedPressIcon
import top.lovepikachu.XiaoHeiHook.composables.FullWidthTextButton

@Composable
fun HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ActivationStatusCard()

        Text(
            text = stringResource(R.string.fast_operation),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp)
        )

        FullWidthTextButton(
            text = "打开 Lsposed 管理器",
            onClick = {
                Thread {
                    try {
                        val command = "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android"
                        Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            }
        )

        Spacer(modifier = Modifier.height(28.dp))

        AnimatedPressIcon(
            painter = painterResource(id = R.drawable.xiaoheiatstone),
            onClick = {},
            modifier = Modifier
                .align(Alignment.CenterHorizontally),
            size = 120.dp
        )
    }
}