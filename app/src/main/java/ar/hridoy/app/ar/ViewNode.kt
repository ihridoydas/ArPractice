package ar.hridoy.app.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import io.github.sceneview.SceneScope
import io.github.sceneview.collision.HitResult
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.ViewNode.WindowManager
import io.github.sceneview.safeDestroyStream
import io.github.sceneview.safeDestroyTexture
import kotlin.math.abs
import io.github.sceneview.node.Node as NodeImpl
import io.github.sceneview.node.Node

/**
 * A Node that can display an Android [View]
 */
open class ViewNode(
    engine: Engine,
    val windowManager: WindowManager,
    private val materialLoader: MaterialLoader,
    val view: View,
    unlit: Boolean = false,
    invertFrontFaceWinding: Boolean = false,
) : PlaneNode(engine = engine) {

    private var isDestroyed = false

    // Updated when the view is added to the view manager
    var pxPerUnits = 250.0f
        set(value) {
            if (abs(field - value) > 10.0f) {
                field = value
                updateGeometrySize()
            }
        }

    var viewSize = Size(0.0f)
        set(value) {
            if (abs(field.x - value.x) > 10.0f || abs(field.y - value.y) > 10.0f) {
                field = value
                updateGeometrySize()
            }
        }

    val layout: Layout = Layout(view.context).apply {
        addView(view)
    }

    private val surfaceTexture = SurfaceTexture(0)
    private val surface = Surface(surfaceTexture)

    val stream: Stream = Stream.Builder()
        .stream(surfaceTexture)
        .build(engine)

    val texture: Texture = Texture.Builder()
        .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
        .format(Texture.InternalFormat.RGB8)
        .build(engine)
        .apply {
            setExternalStream(engine, stream)
        }

    override var materialInstance: MaterialInstance = materialLoader.createViewInstance(
        viewTexture = texture,
        unlit = unlit,
        invertFrontFaceWinding = invertFrontFaceWinding
    ).also {
        setMaterialInstanceAt(0, it)
    }
        set(value) {
            val old = field
            if (old != value) {
                field = value
                setMaterialInstanceAt(0, value)
                materialLoader.destroyMaterialInstance(old)
            }
        }

    constructor(
        engine: Engine,
        windowManager: WindowManager,
        materialLoader: MaterialLoader,
        @LayoutRes viewLayoutRes: Int,
        unlit: Boolean = false,
        invertFrontFaceWinding: Boolean = false
    ) : this(
        engine = engine,
        windowManager = windowManager,
        materialLoader = materialLoader,
        view = LayoutInflater.from(materialLoader.context).inflate(viewLayoutRes, null, false),
        unlit = unlit,
        invertFrontFaceWinding = invertFrontFaceWinding
    )

    constructor(
        engine: Engine,
        windowManager: WindowManager,
        materialLoader: MaterialLoader,
        unlit: Boolean = false,
        invertFrontFaceWinding: Boolean = false,
        content: @Composable () -> Unit
    ) : this(
        engine = engine,
        windowManager = windowManager,
        materialLoader = materialLoader,
        view = ComposeView(materialLoader.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        },
        unlit = unlit,
        invertFrontFaceWinding = invertFrontFaceWinding
    )

    fun updateGeometrySize() {
        updateGeometry(size = viewSize / pxPerUnits)
    }

    override fun onAddedToScene(scene: Scene) {
        super.onAddedToScene(scene)
        windowManager.addView(layout)
    }

    override fun onRemovedFromScene(scene: Scene) {
        super.onRemovedFromScene(scene)
        removeLayoutFromWindow()
    }

    private fun removeLayoutFromWindow() {
        try {
            windowManager.removeView(layout)
        } catch (_: Exception) {
        }
    }

    override fun onTouchEvent(e: MotionEvent, hitResult: HitResult): Boolean {
        return super.onTouchEvent(e, hitResult)
    }

    override fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        removeLayoutFromWindow()

        val mi = materialInstance
        super.destroy()
        materialLoader.destroyMaterialInstance(mi)
        engine.safeDestroyTexture(texture)
        engine.safeDestroyStream(stream)
        surface.release()
        surfaceTexture.release()
    }

    var useHardwareCanvas: Boolean = false

    inner class Layout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        defStyleRes: Int = 0
    ) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            surfaceTexture.setDefaultBufferSize(width, height)
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            viewSize = Size(width.toFloat(), height.toFloat())
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (!isAttachedToWindow || isDestroyed) return
            val viewSurface = surface.takeIf { it.isValid } ?: return

            val surfaceCanvas = if (useHardwareCanvas) {
                try {
                    viewSurface.lockHardwareCanvas()
                } catch (_: Exception) {
                    try {
                        viewSurface.lockCanvas(null)
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                try {
                    viewSurface.lockCanvas(null)
                } catch (_: Exception) {
                    null
                }
            } ?: return

            try {
                surfaceCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                super.dispatchDraw(surfaceCanvas)
            } catch (_: Exception) {
            } finally {
                try {
                    viewSurface.unlockCanvasAndPost(surfaceCanvas)
                } catch (_: Exception) {
                }
            }
        }
    }
}

/**
 * A node that renders Jetpack Compose UI content onto a flat plane in 3D space.
 */
@Composable
fun SceneScope.ViewNode(
    windowManager: WindowManager,
    unlit: Boolean = false,
    invertFrontFaceWinding: Boolean = false,
    position: Position = Position(x = 0f),
    rotation: Rotation = Rotation(x = 0f),
    scale: Scale = Scale(1f),
    isVisible: Boolean = true,
    apply: ViewNode.() -> Unit = {},
    content: (@Composable io.github.sceneview.NodeScope.() -> Unit)? = null,
    viewContent: @Composable () -> Unit
) {
    val node = remember(engine, windowManager) {
        ViewNode(
            engine = engine,
            windowManager = windowManager,
            materialLoader = materialLoader,
            unlit = unlit,
            invertFrontFaceWinding = invertFrontFaceWinding,
            content = viewContent
        )
    }

    DisposableEffect(node) {
        onDispose {
            node.parent?.removeChildNode(node)
            node.destroy()
        }
    }

    // Call the base Node composable (extension on SceneScope)
    Node(
        position = position,
        rotation = rotation,
        scale = scale,
        isVisible = isVisible,
        apply = {
            addChildNode(node)
            node.apply(apply)
        },
        content = content
    )
}
