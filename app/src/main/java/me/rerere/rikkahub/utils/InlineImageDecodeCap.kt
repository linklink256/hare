package me.rerere.rikkahub.utils

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Size
import coil3.size.pxOrElse

/** 内联图片的解码上限（像素），由 ZoomableAsyncImage 按屏幕尺寸写入请求 extras。 */
val InlineDecodeCapWidth = Extras.Key(default = 0)
val InlineDecodeCapHeight = Extras.Key(default = 0)

/**
 * 只把带解码上限的请求裁小：布局约束已经给出尺寸的（气泡内图片、缩略图）只做 min，不会放大；
 * 真正回落到原始尺寸的无界请求才被限制到上限，避免整张原图当成 GL 纹理驻留。
 */
object InlineDecodeCapInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val capWidth = chain.request.extras[InlineDecodeCapWidth] ?: 0
        val capHeight = chain.request.extras[InlineDecodeCapHeight] ?: 0
        if (capWidth <= 0 || capHeight <= 0) return chain.proceed()

        val size = chain.size
        val width = size.width.pxOrElse { capWidth }.coerceAtMost(capWidth)
        val height = size.height.pxOrElse { capHeight }.coerceAtMost(capHeight)
        return chain
            .withSize(Size(Dimension.Pixels(width), Dimension.Pixels(height)))
            .proceed()
    }
}
