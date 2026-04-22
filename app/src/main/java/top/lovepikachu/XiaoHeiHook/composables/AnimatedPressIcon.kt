package top.lovepikachu.XiaoHeiHook.composables

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AnimatedPressIcon(
    painter: Painter? = null,
    imageVector: ImageVector? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    contentDescription: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }

    // 按压时轻微上浮（手指按住时的反馈）
    val pressOffset by animateDpAsState(
        targetValue = if (isPressed) (-8).dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press_offset"
    )

    Surface(
        modifier = modifier
            .offset(y = (offsetY.value + pressOffset.value).dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    // 点击时触发「跳跃」动画
                    scope.launch {
                        offsetY.snapTo(0f)
                        offsetY.animateTo(
                            targetValue = -25f,
                            animationSpec = tween(
                                durationMillis = 80,
                                easing = FastOutSlowInEasing
                            )
                        )
                        offsetY.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    }
                    onClick()
                }
            )
            .padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        when {
            painter != null -> {
                androidx.compose.foundation.Image(
                    painter = painter,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(size)
                )
            }
            imageVector != null -> {
                androidx.compose.material3.Icon(
                    imageVector = imageVector,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(size),
                    tint = Color.Unspecified
                )
            }
        }
    }
}