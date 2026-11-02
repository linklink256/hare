package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import androidx.compose.ui.graphics.Color
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/**
 * 取背景图底部区域的平均色，并按背景不透明度与全屏遮罩叠一遍，
 * 作为底部渐变遮罩的终点色——遮住消息但不露出白色。
 */
internal suspend fun sampleChatScrimColor(
    context: Context,
    model: String,
    backgroundOpacity: Float,
    base: Color,
): Color {
    val bitmap = runCatching {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(64)
            .allowHardware(false)
            .build()
        (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }.getOrNull() ?: return base
    if (bitmap.width <= 0 || bitmap.height <= 0) return base

    val fromRow = (bitmap.height * 0.75f).toInt().coerceIn(0, bitmap.height - 1)
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    for (y in fromRow until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
            count++
        }
    }
    if (count == 0L) return base

    val average = Color(
        red = (r / count).toInt(),
        green = (g / count).toInt(),
        blue = (b / count).toInt(),
        alpha = 255,
    )
    // 与 AssistantBackground 的叠法一致：图片按背景不透明度叠在基色上，再叠 0.5 基色遮罩
    val image = blend(base, average, backgroundOpacity.coerceIn(0f, 1f))
    return blend(image, base, 0.5f)
}

private fun blend(dst: Color, src: Color, alpha: Float): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(
        red = dst.red * (1 - a) + src.red * a,
        green = dst.green * (1 - a) + src.green * a,
        blue = dst.blue * (1 - a) + src.blue * a,
        alpha = 1f,
    )
}
