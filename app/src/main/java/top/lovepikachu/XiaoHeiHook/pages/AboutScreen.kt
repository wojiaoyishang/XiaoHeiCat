package top.lovepikachu.XiaoHeiHook.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.TextUnit.Companion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.lovepikachu.XiaoHeiHook.R
import top.lovepikachu.XiaoHeiHook.composables.AnimatedPressIcon

@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "关于本模块",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "皮卡丘 ~ 罗小黑 ~ Niko ~ \n\n" +
                            "在梦中，一切事都散漫着，\n都压着我，但这不过是一个梦呀。\n" +
                            "当我醒来时，\n我便将觉得这些事都已聚集在你那里，\n我也便将自由了。\n\n" +
                            "Pika Pika Meow Meow Meow ~",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        uriHandler.openUri("https://lovepikachu.top")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("My Website", fontSize = 15.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(200.dp))

        AnimatedPressIcon(
            painter = painterResource(id = R.drawable.nikocat),
            onClick = {},
            modifier = Modifier
                .align(Alignment.CenterHorizontally),
            size = 200.dp
        )

        Text(
            text = "Niko 在干什么呢 ~",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 250.dp),
            fontSize=12.sp
        )
    }
}