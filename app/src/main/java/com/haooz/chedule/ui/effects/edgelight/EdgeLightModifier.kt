package com.haooz.chedule.ui.effects.edgelight

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
import kotlin.math.ceil

internal class EdgeLightElement(
    val shape: androidx.compose.ui.graphics.Shape,
    val edgeLight: () -> EdgeLight?,
    /**
     * 「值重载」（[Modifier.edgeLight] 传 EdgeLight 的那个）把原始值也带上：
     * 那个重载每次调用都会新建一个 `{ edgeLight }` lambda，而 lambda 只能按引用比较，
     * 于是每次重组 `equals` 都判不等 → `update()` → `invalidateOutlineCache()`，
     * 轮廓（ContinuousRoundedRectangle 走的是 Path 构建）就每次重组重算一遍。
     * 带上原值后两个值重载之间按**值**比较，重组不再无故失效。
     * 动态 lambda 重载不传这个字段，仍按引用比较，语义不变。
     */
    private val edgeLightValue: EdgeLight? = null,
) : ModifierNodeElement<EdgeLightNode>() {

    override fun create(): EdgeLightNode {
        return EdgeLightNode(shape, edgeLight)
    }

    override fun update(node: EdgeLightNode) {
        node.shape = shape
        node.edgeLight = edgeLight
        node.invalidateOutlineCache()
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "edgeLight"
        properties["shape"] = shape
        properties["edgeLight"] = edgeLightValue ?: edgeLight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EdgeLightElement) return false
        if (shape != other.shape) return false
        // 两边都是值重载 → 按值比；否则退回按引用比（动态 lambda 的原语义）
        if (edgeLightValue != null && other.edgeLightValue != null) {
            return edgeLightValue == other.edgeLightValue
        }
        if (edgeLight != other.edgeLight) return false
        return true
    }

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + (edgeLightValue?.hashCode() ?: edgeLight.hashCode())
        return result
    }
}

internal class EdgeLightNode(
    var shape: androidx.compose.ui.graphics.Shape,
    var edgeLight: () -> EdgeLight?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var edgeLightLayer: GraphicsLayer? = null

    private val paint =
        Paint().apply {
            style = PaintingStyle.Stroke
        }
    private var clipPath: Path? = null

    private val shaderCache = EdgeLightShaderCache()

    private var cachedBlurRadius: Float = -1f
    private var cachedBlurMaskFilter: android.graphics.BlurMaskFilter? = null
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

    // outline 只在 (shape, size, layoutDirection) 变化时重建：原来每帧都要重走一次路径构建
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

            edgeLightLayer.alpha = intensity

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
        cachedBlurMaskFilter = null
        needsRecord = true
    }

    private fun DrawScope.configurePaint(edgeLight: EdgeLight) {
        paint.color = edgeLight.style.color

        val strokeWidth = ceil(edgeLight.width.toPx().coerceAtMost(size.minDimension / 2f)) * 2f
        paint.strokeWidth = strokeWidth

        val blurRadius = edgeLight.blurRadius.toPx()
        val cachedFilter = cachedBlurMaskFilter
        if (cachedFilter == null || cachedBlurRadius != blurRadius) {
            cachedBlurRadius = blurRadius
            cachedBlurMaskFilter = if (blurRadius > 0f) {
                android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
            } else null
        }
        paint.asFrameworkPaint().maskFilter = cachedBlurMaskFilter

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
    edgeLight: EdgeLight = EdgeLight.Default
): Modifier {
    return this.then(
        EdgeLightElement(
            shape = shape,
            edgeLight = { edgeLight },
            edgeLightValue = edgeLight,
        )
    )
}

fun Modifier.edgeLight(
    shape: androidx.compose.ui.graphics.Shape,
    edgeLight: () -> EdgeLight? = { EdgeLight.Default }
): Modifier {
    return this.then(
        EdgeLightElement(
            shape = shape,
            edgeLight = edgeLight
        )
    )
}

fun Modifier.edgeLightIf(
    shape: androidx.compose.ui.graphics.Shape,
    condition: Boolean,
    edgeLight: EdgeLight = EdgeLight.Default
): Modifier {
    return this.then(
        EdgeLightElement(
            shape = shape,
            edgeLight = if (condition) {
                { edgeLight }
            } else {
                { null }
            }
        )
    )
}
