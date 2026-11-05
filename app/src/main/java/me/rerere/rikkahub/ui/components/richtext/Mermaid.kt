package me.rerere.rikkahub.ui.components.richtext

import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ui.toast.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Maximize01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.escapeHtml
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.toCssHex
import me.rerere.ui.webview.WEB_VIEW_ASSET_URL
import me.rerere.ui.webview.WEB_VIEW_BASE_URL
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.WebViewContentCache
import me.rerere.ui.webview.rememberWebViewState

@Composable
fun Mermaid(
    code: String,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkMode = LocalDarkMode.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val toaster = LocalToaster.current
    val navController = LocalNavController.current

    // 同一段代码 + 同一套配色渲染结果固定，直接复用上次的 SVG 与高度，滚动来回不用重跑 mermaid
    val themeSignature = remember(colorScheme, darkMode) {
        listOf(
            colorScheme.primaryContainer.value, colorScheme.secondaryContainer.value,
            colorScheme.tertiaryContainer.value, colorScheme.background.value,
            colorScheme.surface.value, colorScheme.onPrimaryContainer.value,
            colorScheme.onSecondaryContainer.value, colorScheme.onTertiaryContainer.value,
            colorScheme.onBackground.value, colorScheme.error.value, colorScheme.onError.value,
            darkMode,
        ).joinToString("|")
    }
    val cacheKey = remember(code, themeSignature) { MermaidRenderCache.key(code, themeSignature) }
    val cached = remember(cacheKey) { MermaidRenderCache.get(context, cacheKey) }
    val cachedSvg = cached?.svg
    // 命中缓存时首帧就用已知高度，避免「先 200dp 再长高」把列表顶一下
    var contentHeightDp by remember(code) { mutableIntStateOf(cached?.heightDp ?: 0) }
    val currentCacheKey = rememberUpdatedState(cacheKey)
    val maxThumbnailHeightDp = (LocalConfiguration.current.screenHeightDp * 0.7f).toInt().coerceAtLeast(160)

    val jsInterface = remember {
        MermaidInterface(
            onContentHeight = { heightPx ->
                if (heightPx > 0) {
                    contentHeightDp = heightPx
                    MermaidRenderCache.putHeight(context, currentCacheKey.value, heightPx)
                }
            },
            onSvgReady = { svg -> MermaidRenderCache.putSvg(context, currentCacheKey.value, svg) },
            onExportImage = { base64Image ->
                runCatching {
                    activity?.let {
                        try {
                            val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                            val bitmap =
                                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            context.exportImage(
                                it,
                                bitmap,
                                "mermaid_${System.currentTimeMillis()}.png"
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    toaster.show(
                        context.getString(R.string.mermaid_export_success),
                        type = ToastType.Success
                    )
                }.onFailure {
                    it.printStackTrace()
                    toaster.show(
                        context.getString(R.string.mermaid_export_failed),
                        type = ToastType.Error
                    )
                }
            }
        )
    }

    val html = remember(code, colorScheme, darkMode, maxThumbnailHeightDp, cachedSvg) {
        buildMermaidHtml(
            code = code,
            colorScheme = colorScheme,
            maxHeightDp = maxThumbnailHeightDp,
            cachedSvg = cachedSvg,
        )
    }

    val webViewState = rememberWebViewState(
        data = html,
        baseUrl = WEB_VIEW_BASE_URL,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf(
            "AndroidInterface" to jsInterface
        ),
        settings = {
            builtInZoomControls = false
            displayZoomControls = false
            // 按设备宽度排版，不要用 980px 宽视口再整体缩小（字会变得很小）
            useWideViewPort = false
            loadWithOverviewMode = false
        }
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 标题在左上，右上只留「点进去看大图」，缩放/拖动在全屏预览里做
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (activity != null) {
                    MermaidToolButton(
                        icon = HugeIcons.Maximize01,
                        description = "Preview",
                    ) {
                        val contentId = WebViewContentCache.store(context.cacheDir, html)
                        navController.navigate(Screen.WebView(contentId = contentId))
                    }
                }
            }

            WebView(
                state = webViewState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(
                        if (contentHeightDp > 0) {
                            contentHeightDp.coerceIn(80, maxThumbnailHeightDp)
                        } else {
                            200
                        }.dp
                    ),
            )
        }
    }
}

@Composable
private fun MermaidToolButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
    }
}



private class MermaidInterface(
    private val onExportImage: (String) -> Unit,
    private val onContentHeight: (Int) -> Unit,
    private val onSvgReady: (String) -> Unit,
) {
    @JavascriptInterface
    fun exportImage(base64Image: String) {
        onExportImage(base64Image)
    }

    @JavascriptInterface
    fun setContentHeight(heightPx: Int) {
        onContentHeight(heightPx)
    }

    @JavascriptInterface
    fun svgReady(svg: String) {
        onSvgReady(svg)
    }
}

private fun buildMermaidHtml(
    code: String,
    colorScheme: ColorScheme,
    maxHeightDp: Int,
    cachedSvg: String?,
): String {
    val primaryColor = colorScheme.primaryContainer.toCssHex()
    val secondaryColor = colorScheme.secondaryContainer.toCssHex()
    val tertiaryColor = colorScheme.tertiaryContainer.toCssHex()
    val background = colorScheme.background.toCssHex()
    val surface = colorScheme.surface.toCssHex()
    val onPrimary = colorScheme.onPrimaryContainer.toCssHex()
    val onSecondary = colorScheme.onSecondaryContainer.toCssHex()
    val onTertiary = colorScheme.onTertiaryContainer.toCssHex()
    val onBackground = colorScheme.onBackground.toCssHex()
    val errorColor = colorScheme.error.toCssHex()
    val onErrorColor = colorScheme.onError.toCssHex()

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            ${if (cachedSvg == null) "<script src=\"${WEB_VIEW_ASSET_URL}/html/mermaid.min.js\"></script>" else ""}
            <style>
                html, body {
                    margin: 0;
                    padding: 0;
                    width: 100%;
                    background-color: ${background};
                    overflow: auto;
                }
                .mermaid {
                    padding: 8px;
                    box-sizing: border-box;
                    text-align: center;
                }
                .mermaid svg {
                    display: inline-block;
                    vertical-align: top;
                    background: transparent !important;
                }
            </style>
        </head>
        <body>
            <div class="mermaid">
                ${cachedSvg ?: code.escapeHtml()}
            </div>
            <script>
              var CACHED_SVG = ${if (cachedSvg != null) "true" else "false"};
              if (!CACHED_SVG) {
              mermaid.initialize({
                    startOnLoad: false,
                    theme: 'base',
                    flowchart: { useMaxWidth: true },
                    sequence: { useMaxWidth: true },
                    gantt: { useMaxWidth: true },
                    themeVariables: {
                        primaryColor: '${primaryColor}',
                        primaryTextColor: '${onPrimary}',
                        primaryBorderColor: '${primaryColor}',

                        secondaryColor: '${secondaryColor}',
                        secondaryTextColor: '${onSecondary}',
                        secondaryBorderColor: '${secondaryColor}',

                        tertiaryColor: '${tertiaryColor}',
                        tertiaryTextColor: '${onTertiary}',
                        tertiaryBorderColor: '${tertiaryColor}',

                        background: '${background}',
                        mainBkg: '${primaryColor}',
                        secondBkg: '${secondaryColor}',

                        lineColor: '${onBackground}',
                        textColor: '${onBackground}',

                        nodeBkg: '${surface}',
                        nodeBorder: '${primaryColor}',
                        clusterBkg: '${surface}',
                        clusterBorder: '${primaryColor}',

                        actorBorder: '${primaryColor}',
                        actorBkg: '${surface}',
                        actorTextColor: '${onBackground}',
                        actorLineColor: '${primaryColor}',

                        taskBorderColor: '${primaryColor}',
                        taskBkgColor: '${primaryColor}',
                        taskTextLightColor: '${onPrimary}',
                        taskTextDarkColor: '${onBackground}',

                        labelColor: '${onBackground}',
                        errorBkgColor: '${errorColor}',
                        errorTextColor: '${onErrorColor}'
                    }
              });
              }

              // 把整张图按宽高都塞进卡片，保证一眼看完；要看细节点右上角进全屏
              var MAX_HEIGHT = ${maxHeightDp};
              var naturalWidth = 0;
              var naturalHeight = 0;
              var reportedSvg = false;

              function mermaidSvg() {
                return document.querySelector('.mermaid svg');
              }

              function postHeight(height) {
                try {
                  if (window.AndroidInterface && AndroidInterface.setContentHeight) {
                    AndroidInterface.setContentHeight(Math.ceil(height));
                  }
                } catch (e) {}
              }

              function fit() {
                var svg = mermaidSvg();
                if (!svg) { return false; }
                var vb = svg.viewBox && svg.viewBox.baseVal;
                if (vb && vb.width > 0 && vb.height > 0) {
                  naturalWidth = vb.width;
                  naturalHeight = vb.height;
                } else {
                  var rect = svg.getBoundingClientRect();
                  naturalWidth = rect.width || 1;
                  naturalHeight = rect.height || 1;
                }
                var availableWidth = Math.max(1, document.body.clientWidth - 16);
                var scale = Math.min(1, availableWidth / naturalWidth, MAX_HEIGHT / naturalHeight);
                var width = Math.max(1, Math.round(naturalWidth * scale));
                var height = Math.max(1, Math.round(naturalHeight * scale));
                svg.style.width = width + 'px';
                svg.style.height = height + 'px';
                svg.style.maxWidth = 'none';
                svg.style.maxHeight = 'none';
                postHeight(height + 16);
                if (!CACHED_SVG && !reportedSvg) {
                  reportedSvg = true;
                  try {
                    if (window.AndroidInterface && AndroidInterface.svgReady) {
                      AndroidInterface.svgReady(svg.outerHTML);
                    }
                  } catch (e) {}
                }
                return true;
              }

              if (!CACHED_SVG) {
                try {
                  mermaid.run({ querySelector: '.mermaid' }).then(fit).catch(fit);
                } catch (e) {
                  fit();
                }
              }
              // 渲染完成时机不确定，多试几次；拿到尺寸后停止
              var bootTries = 0;
              var bootTimer = setInterval(function() {
                bootTries += 1;
                if (fit() || bootTries > 20) { clearInterval(bootTimer); }
              }, 150);
              window.addEventListener('resize', function() { setTimeout(fit, 50); });

              window.exportSvgToPng = function() {
                try {
                    const svgElement = document.querySelector('.mermaid svg');
                    if (!svgElement) {
                        AndroidInterface.exportImage('');
                        return;
                    }

                    const canvas = document.createElement('canvas');
                    const ctx = canvas.getContext('2d');

                    const svgRect = svgElement.getBoundingClientRect();
                    const width = svgRect.width;
                    const height = svgRect.height;

                    const scaleFactor = window.devicePixelRatio * 2;
                    canvas.width = width * scaleFactor;
                    canvas.height = height * scaleFactor;

                    const svgXml = new XMLSerializer().serializeToString(svgElement);
                    const svgBase64 = btoa(unescape(encodeURIComponent(svgXml)));

                    const img = new Image();
                    img.onload = function() {
                        ctx.fillStyle = '${background}';
                        ctx.fillRect(0, 0, canvas.width, canvas.height);
                        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

                        ctx.font = '14px Arial';
                        ctx.fillStyle = '${onBackground}';
                        ctx.fillText('rikka-ai.com', 20, canvas.height - 10);

                        const pngBase64 = canvas.toDataURL('image/png').split(',')[1];
                        AndroidInterface.exportImage(pngBase64);
                    };
                    img.onerror = function(e) {
                        AndroidInterface.exportImage('');
                    }
                    img.src = 'data:image/svg+xml;base64,' + svgBase64;
                } catch (e) {
                    AndroidInterface.exportImage('');
                }
              };
            </script>
        </body>
        </html>
    """.trimIndent()
}
