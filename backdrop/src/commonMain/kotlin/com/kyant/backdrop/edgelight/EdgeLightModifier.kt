package com.kyant.backdrop.edgelight

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.internal.blur
import com.kyant.backdrop.internal.clipOutline
import com.kyant.backdrop.internal.setRuntimeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.math.ceil

/**
 * 默认的「不淡出」系数。
 *
 * 必须是**同一个实例**：`fadeAlpha` 参与 [EdgeLightElement.equals]，若默认值写成
 * `{ 1f }` 字面量，每次重组都会新建 lambda，equals 恒不等 → update()/invalidateOutlineCache()
 * 白白每帧跑一遍。不传 fadeAlpha 的调用点（导航栏按钮 / 下拉菜单等）共用这一个。
 */
internal val FullFadeAlpha: () -> Float = { 1f }

internal class EdgeLightElement(
    val shape: androidx.compose.ui.graphics.Shape,
    val edgeLight: () -> EdgeLight?,
    /**
     * 绘制期读取的淡出系数（1f = 全量，0f = 完全隐藏）。
     *
     * 由上层（:ui-shared 的 GlassPerf）在调用点以参数注入 —— :ui-shared → :backdrop 是
     * 单向依赖，backdrop 不能反向认识 GlassPerf。**只能在 draw 里调用**：读进组合就是
     * 每帧重组，读进 remember 的键会让 element 身份每次都变。
     */
    val fadeAlpha: () -> Float
) : ModifierNodeElement<EdgeLightNode>() {

    override fun create(): EdgeLightNode {
        return EdgeLightNode(shape, edgeLight, fadeAlpha)
    }

    override fun update(node: EdgeLightNode) {
        node.shape = shape
        node.edgeLight = edgeLight
        node.fadeAlpha = fadeAlpha
        node.invalidateOutlineCache()
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "edgeLight"
        properties["shape"] = shape
        properties["edgeLight"] = edgeLight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EdgeLightElement) return false
        if (shape != other.shape) return false
        if (edgeLight != other.edgeLight) return false
        if (fadeAlpha != other.fadeAlpha) return false
        return true
    }

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + edgeLight.hashCode()
        result = 31 * result + fadeAlpha.hashCode()
        return result
    }
}

internal class EdgeLightNode(
    var shape: androidx.compose.ui.graphics.Shape,
    var edgeLight: () -> EdgeLight?,
    var fadeAlpha: () -> Float
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var edgeLightLayer: GraphicsLayer? = null

    private val paint =
        Paint().apply {
            style = PaintingStyle.Stroke
        }
    private var clipPath: Path? = null

    private val shaderCache = EdgeLightShaderCache()

    private var cachedSafeSize: IntSize = IntSize(0, 0)
    private var cachedSize: Size? = null

    private var recordedSize: Size? = null
    private var recordedBlurRadius: Float = -1f
    private var recordedStrokeWidth: Float = -1f
    private var recordedColor: androidx.compose.ui.graphics.Color? = null
    private var recordedIntensity: Float = -1f
    private var recordedBlendMode: androidx.compose.ui.graphics.BlendMode? = null
    private var recordedShaderKey: String? = null
    private var recordedOutlineHashCode: Int = 0
    private var needsRecord = true

    // outline 仅在 (shape, size, layoutDirection) 变化时重建：原先每帧都重走一次路径构建
    private var cachedOutline: Outline? = null
    private var cachedOutlineShape: Any? = null
    private var cachedOutlineSize: Size? = null
    private var cachedOutlineLayoutDirection: LayoutDirection? = null

    fun invalidateOutlineCache() {
        cachedOutline = null
        cachedOutlineShape = null
        cachedOutlineSize = null
        cachedOutlineLayoutDirection = null
    }

    override fun ContentDrawScope.draw() {
        val edgeLight = edgeLight()
        if (edgeLight == null || edgeLight.width.value <= 0f) {
            return drawContent()
        }

        drawContent()

        // 滑动/翻页进行中降级：边光随全局系数**连续淡出**，不是硬切 ——
        // 描边是很细的一条线，硬切反而比玻璃更扎眼。layer.alpha 是可连续量，
        // 乘上去不会触发重新录制（recordedIntensity 比的是原始 intensity，不变）。
        // 它是"每张卡每帧一次的前景 RuntimeShader + 一次 native SkMaskFilter"，
        // 与 blur/lens 同属每帧每卡的开销。读的是同一个全局系数 ——
        // DrawModifierNode.draw 里的快照读会登记成绘制期依赖，变化只失效绘制、不触发重组。
        val edgeLightFade = fadeAlpha()
        if (edgeLightFade <= 0.01f) return

        val edgeLightLayer = edgeLightLayer
        if (edgeLightLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val safeSize = if (cachedSize != size) {
                cachedSize = size
                cachedSafeSize = IntSize(
                    ceil(size.width).toInt() + 2,
                    ceil(size.height).toInt() + 2
                )
                cachedSafeSize
            } else {
                cachedSafeSize
            }

            val outline =
                if (cachedOutline != null &&
                    cachedOutlineShape === shape &&
                    cachedOutlineSize == size &&
                    cachedOutlineLayoutDirection == layoutDirection
                ) {
                    cachedOutline!!
                } else {
                    shape.createOutline(size, layoutDirection, density).also {
                        cachedOutlineShape = shape
                        cachedOutlineSize = size
                        cachedOutlineLayoutDirection = layoutDirection
                        cachedOutline = it
                    }
                }

            val clipPath =
                if (outline is Outline.Rounded) {
                    clipPath ?: Path().also { clipPath = it }
                } else {
                    null
                }

            configurePaint(edgeLight)

            val strokeWidth = ceil(edgeLight.width.toPx().coerceAtMost(size.minDimension / 2f)) * 2f
            val blurRadius = edgeLight.blurRadius.toPx()
            val color = edgeLight.style.color
            val intensity = edgeLight.intensity
            val blendMode = edgeLight.style.blendMode
            val shaderKey = if (isRuntimeShaderSupported() && edgeLight.style !is EdgeLightStyle.Uniform) {
                edgeLight.style::class.simpleName
            } else null

            edgeLightLayer.alpha = 1f
            edgeLightLayer.blendMode = blendMode

            val outlineHashCode = outline.hashCode()
            val paramsChanged = recordedOutlineHashCode != outlineHashCode
                    || recordedSize != size
                    || recordedBlurRadius != blurRadius
                    || recordedStrokeWidth != strokeWidth
                    || recordedColor != color
                    || recordedIntensity != intensity
                    || recordedBlendMode != blendMode
                    || recordedShaderKey != shaderKey
                    || needsRecord

            if (paramsChanged) {
                recordedOutlineHashCode = outlineHashCode
                recordedSize = size
                recordedBlurRadius = blurRadius
                recordedStrokeWidth = strokeWidth
                recordedColor = color
                recordedIntensity = intensity
                recordedBlendMode = blendMode
                recordedShaderKey = shaderKey
                needsRecord = false

                edgeLightLayer.record(safeSize) {
                    translate(1f, 1f) {
                        val canvas = drawContext.canvas
                        canvas.save()
                        canvas.clipOutline(outline, clipPath)
                        canvas.drawOutline(outline, paint)
                        canvas.restore()
                    }
                }
            }

            // 乘淡出系数（不改 intensity：改了会触发 recordedIntensity 失配、每帧重录）
            edgeLightLayer.alpha = intensity * edgeLightFade

            translate(-1f, -1f) {
                drawLayer(edgeLightLayer)
            }
        }
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        edgeLightLayer = graphicsContext.createGraphicsLayer()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        edgeLightLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            edgeLightLayer = null
        }
        clipPath = null
        shaderCache.clear()
        needsRecord = true
    }

    // 描边模糊走本模块的 expect/actual（Android=RenderEffect，iOS/Desktop=SkSL），不用 android.graphics.BlurMaskFilter + asFrameworkPaint()——
    // 后者会把整个文件钉死在 Android，是当初进不了 backdrop 模块的唯一障碍。
    private fun DrawScope.configurePaint(edgeLight: EdgeLight) {
        paint.color = edgeLight.style.color

        val strokeWidth = ceil(edgeLight.width.toPx().coerceAtMost(size.minDimension / 2f)) * 2f
        paint.strokeWidth = strokeWidth

        paint.blur(edgeLight.blurRadius.toPx())

        if (isRuntimeShaderSupported() && edgeLight.style !is EdgeLightStyle.Uniform) {
            val shader = createEdgeLightShader(
                edgeLight = edgeLight,
                size = size,
                shape = shape,
                layoutDirection = layoutDirection,
                density = this,
                shaderCache = shaderCache
            )
            if (shader != null) {
                paint.setRuntimeShader(shader)
            }
        }
    }
}

fun Modifier.edgeLight(
    shape: androidx.compose.ui.graphics.Shape,
    edgeLight: EdgeLight = EdgeLight.Default,
    fadeAlpha: () -> Float = FullFadeAlpha
): Modifier {
    return this.then(
        EdgeLightElement(
            shape = shape,
            edgeLight = { edgeLight },
            fadeAlpha = fadeAlpha
        )
    )
}

fun Modifier.edgeLight(
    shape: androidx.compose.ui.graphics.Shape,
    edgeLight: () -> EdgeLight? = { EdgeLight.Default },
    fadeAlpha: () -> Float = FullFadeAlpha
): Modifier {
    return this.then(
        EdgeLightElement(
            shape = shape,
            edgeLight = edgeLight,
            fadeAlpha = fadeAlpha
        )
    )
}

fun Modifier.edgeLightIf(
    shape: androidx.compose.ui.graphics.Shape,
    condition: Boolean,
    edgeLight: EdgeLight = EdgeLight.Default,
    fadeAlpha: () -> Float = FullFadeAlpha
): Modifier {
    return this.then(
        EdgeLightElement(
            shape = shape,
            edgeLight = if (condition) {
                { edgeLight }
            } else {
                { null }
            },
            fadeAlpha = fadeAlpha
        )
    )
}
