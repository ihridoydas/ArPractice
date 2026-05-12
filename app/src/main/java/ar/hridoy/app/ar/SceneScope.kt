// SceneScope is the base class for both 3D and AR scopes.
// The class hierarchy (SceneScope -> ARSceneScope -> NodeScope) is designed so that arsceneview
// extends sceneview without code duplication. Modules are kept separate intentionally:
// sceneview = lightweight 3D-only (no ARCore dependency), arsceneview = opt-in AR.

package ar.hridoy.app.ar

import androidx.annotation.RestrictTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.google.android.filament.Engine
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.CameraNode as CameraNodeImpl
import io.github.sceneview.node.Node as NodeImpl


/**
 * DSL marker annotation that prevents implicit access to outer [SceneScope] from inside a
 * [NodeScope], enforcing correct nesting.
 */
@DslMarker
annotation class SceneDsl

/**
 * The composable DSL scope for building 3D scenes inside [io.github.sceneview.Scene].
 *
 * `SceneScope` is the receiver of `SceneView { }` content blocks. Every node type — models, lights,
 * geometry, images, Compose UI planes, custom meshes — is a `@Composable` function in this scope.
 * Nodes enter the Filament scene on first composition and are automatically destroyed when they
 * leave, with no manual lifecycle management.
 *
 * Build scenes the same way you build Compose UI: nest composables, react to state, use
 * `remember` and `LaunchedEffect`. The 3D scene graph mirrors the Compose tree.
 *
 * ```kotlin
 * SceneView(modifier = Modifier.fillMaxSize()) {
 *     // Async model — null while loading, node appears on recomposition when ready
 *     rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
 *         ModelNode(modelInstance = instance, scaleToUnits = 0.5f)
 *     }
 *     // Nested nodes build a scene graph hierarchy
 *     Node(position = Position(y = 1.0f)) {
 *         CubeNode(size = Size(0.1f))
 *         SphereNode(radius = 0.05f)
 *     }
 *     LightNode(type = LightManager.Type.DIRECTIONAL)
 * }
 * ```
 *
 * **Naming convention:** Composable functions in this scope (e.g. `ModelNode`, `CubeNode`) share
 * their names with the underlying imperative node classes they wrap (e.g. `io.github.sceneview.node.ModelNode`).
 * This is intentional — the composable is the primary API surface and internally creates/manages
 * the imperative node instance. Import aliases (`ModelNodeImpl`, `CubeNodeImpl`, etc.) are used
 * internally to disambiguate.
 *
 * @param engine            The Filament [Engine] shared with the parent [io.github.sceneview.Scene].
 * @param modelLoader       [ModelLoader] for loading glTF/GLB models.
 * @param materialLoader    [MaterialLoader] for creating material instances.
 * @param environmentLoader [EnvironmentLoader] for loading HDR/KTX environments.
 * @param _nodes            Internal SnapshotStateList backing the scene's root node list.
 */
@Suppress("FunctionName") // Composable functions follow PascalCase (Compose convention)
@SceneDsl
open class SceneScope @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX) constructor(
    val engine: Engine,
    val modelLoader: ModelLoader,
    val materialLoader: MaterialLoader,
    val environmentLoader: EnvironmentLoader,
    internal val _nodes: SnapshotStateList<NodeImpl>,
    // Called synchronously in detach() to remove the node from the Filament scene before
    // node.destroy() runs. This prevents the SIGABRT caused by destroying a MaterialInstance
    // while its Renderable entity is still registered in the scene.
    internal val nodeRemover: ((NodeImpl) -> Unit)? = null
) {

    // ── Attachment helpers ────────────────────────────────────────────────────────────────────────

    /**
     * Attach [node] to this scope's container. Overridden in [NodeScope] to attach to a parent.
     */
    internal open fun attach(node: NodeImpl) {
        _nodes.add(node)
    }

    /**
     * Detach [node] from this scope's container. Overridden in [NodeScope] to remove from parent.
     */
    internal open fun detach(node: NodeImpl) {
        // Remove from the Filament scene synchronously before node.destroy() is called.
        // For child nodes (NodeScope) this happens via parentNode.removeChildNode → onChildRemoved.
        // For root-level nodes the LaunchedEffect that watches scopeChildNodes is async, so we
        // must remove explicitly here to guarantee the entity leaves the scene first.
        nodeRemover?.invoke(node)
        _nodes.remove(node)
    }


    /**
     * A secondary camera node that can be used as an alternative viewpoint.
     *
     * **Note:** This does NOT automatically become the scene's active rendering camera.
     * The main rendering camera is configured via the `cameraNode` parameter of [io.github.sceneview.Scene].
     * Use this composable to add cameras as named scene nodes (e.g. imported from a glTF model).
     *
     * @param apply   Configuration applied to the [CameraNodeImpl] on creation.
     * @param content Optional child nodes declared in a [NodeScope].
     */
    @Composable
    fun SecondaryCamera(
        apply: CameraNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) {
        val node = remember(engine) {
            CameraNodeImpl(engine = engine).apply(apply)
        }
        NodeLifecycle(node, content)
    }

    /**
     * @deprecated Use [SecondaryCamera] instead. This composable creates a non-active camera —
     * the name `CameraNode` is misleading because the scene's active camera is set via the
     * `cameraNode` parameter of [io.github.sceneview.Scene], not via this composable.
     */
    @Deprecated(
        message = "Renamed to SecondaryCamera for clarity. CameraNode creates a non-active camera.",
        replaceWith = ReplaceWith("SecondaryCamera(apply, content)")
    )
    @Composable
    fun CameraNode(
        apply: CameraNodeImpl.() -> Unit = {},
        content: (@Composable NodeScope.() -> Unit)? = null
    ) = SecondaryCamera(apply, content)

    // ── Internal lifecycle helper ─────────────────────────────────────────────────────────────────

    /**
     * Internal helper shared by all node composables.
     *
     * Attaches [node] to this scope's container on entry and detaches/destroys it on exit.
     * Also runs any nested [content] inside a [NodeScope] receiver.
     *
     * **Extension point:** subclasses (e.g. [io.github.sceneview.ar.ARSceneScope]) should call this
     * method from their own node composables to get automatic lifecycle management. The [attach] and
     * [detach] methods can be overridden to customize how nodes are added/removed (see [NodeScope]).
     */
    @Composable
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun NodeLifecycle(
        node: NodeImpl,
        content: (@Composable NodeScope.() -> Unit)?
    ) {
        DisposableEffect(node) {
            attach(node)
            onDispose {
                detach(node)
                node.destroy()
            }
        }
        if (content != null) {
            NodeScope(parentNode = node, scope = this).content()
        }
    }
}

// ── NodeScope ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Composable DSL scope for declaring child nodes under a specific parent node.
 *
 * `NodeScope` is the receiver of the optional `content` trailing lambda accepted by every node
 * composable in [SceneScope]. Composables declared inside are attached as children of
 * [parentNode] rather than the scene root, mirroring how nested `Column`/`Box` composables
 * work in standard Compose UI.
 *
 * ```kotlin
 * SceneView {
 *     Node(position = Position(y = 0.5f)) {  // <- this block is a NodeScope
 *         ModelNode(modelInstance = helmet)   // child of the Node above
 *         CubeNode(size = Size(0.05f))        // sibling, also a child of Node
 *     }
 * }
 * ```
 *
 * @param parentNode The node that newly declared composables are attached to as children.
 * @param scope      The parent [SceneScope] providing shared resources (engine, loaders, etc.).
 */
@SceneDsl
class NodeScope internal constructor(
    val parentNode: NodeImpl,
    scope: SceneScope
) : SceneScope(
    engine = scope.engine,
    modelLoader = scope.modelLoader,
    materialLoader = scope.materialLoader,
    environmentLoader = scope.environmentLoader,
    _nodes = scope._nodes
) {
    /**
     * Attaches [node] as a child of [parentNode].
     */
    override fun attach(node: NodeImpl) {
        parentNode.addChildNode(node)
    }

    /**
     * Removes [node] from [parentNode]'s children.
     */
    override fun detach(node: NodeImpl) {
        parentNode.removeChildNode(node)
    }
}
