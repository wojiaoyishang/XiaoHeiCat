package top.lovepikachu.XiaoHeiHook.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.lovepikachu.XiaoHeiHook.composables.ActivationStatusCard
import top.lovepikachu.XiaoHeiHook.composables.XposedSettingItem

@Composable
fun AppsScreen() {

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        XposedSettingItem(
            title = "七点工具箱 VIP",
            subtitle = "开启后为目标应用解锁高级功能",
            statusKey = "七点工具箱 VIP",
            packageName = "cn.am7code.tools"
        )

    }
}