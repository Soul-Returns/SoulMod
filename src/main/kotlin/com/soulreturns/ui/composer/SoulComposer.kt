package com.soulreturns.ui.composer

/**
 * Thread-local builder that turns nested `@SoulComposable` calls into a [SoulNode] tree.
 *
 * The model is intentionally minimal — no slot table, no incremental composition. Every
 * frame [build] runs the root composable, which calls inner composables that call deeper
 * composables, and the call hierarchy mirrors the resulting tree. Each composable:
 *  1. Constructs its node (with `modifier` captured from its parameters).
 *  2. Calls [startNode] to push the node onto the composer's stack — the new node becomes the
 *     current "parent under construction".
 *  3. Invokes its `content: @SoulComposable () -> Unit` lambda; nested composables register
 *     themselves as children of the top-of-stack node.
 *  4. Calls [endNode] to pop the stack.
 *
 * Use [composable] for the typical case — it wraps the start/end into a single call.
 *
 * **Threading:** the composer is thread-local because Minecraft's render thread is the only
 * place composables run, but a thread-local is the safest way to guarantee no cross-thread
 * leakage if a future feature ever runs background composition.
 */
class SoulComposer internal constructor() {
    private val stack: ArrayDeque<SoulNode> = ArrayDeque()
    private var root: SoulNode? = null

    /** Push [node] onto the parent stack; attach it to the current top as a child (or root). */
    fun startNode(node: SoulNode) {
        val parent = stack.lastOrNull()
        if (parent != null) {
            parent.children += node
        } else {
            check(root == null) {
                "SoulComposer: multiple root nodes — composable lambdas must produce exactly one root component"
            }
            root = node
        }
        stack.addLast(node)
    }

    fun endNode() {
        check(stack.isNotEmpty()) { "SoulComposer.endNode without matching startNode" }
        stack.removeLast()
    }

    private fun reset() {
        stack.clear()
        root = null
    }

    /**
     * Run [content] and return the resulting root node. Throws if the lambda produces zero
     * or multiple root nodes.
     *
     * Note that this is invoked once per frame for HUDs / screens — there is no
     * recomposition. State changes show up because the very next [build] call sees the
     * updated state.
     */
    fun build(content: @SoulComposable () -> Unit): SoulNode {
        reset()
        threadLocal.set(this)
        try {
            content()
        } finally {
            threadLocal.remove()
        }
        return root ?: error("SoulComposer.build: composable produced no root node")
    }

    companion object {
        private val threadLocal: ThreadLocal<SoulComposer> = ThreadLocal()

        /**
         * The composer active for the current thread. Throws if accessed outside a
         * [build] block — guarantees composables can't be invoked stray (e.g. from a
         * background thread or inside a non-composer render path).
         */
        val current: SoulComposer
            get() =
                threadLocal.get()
                    ?: error("SoulComposer.current accessed outside a SoulComposer.build { ... } scope")

        /** Construct a fresh, single-use composer. Typically [build] is used directly. */
        fun create(): SoulComposer = SoulComposer()
    }
}

/**
 * Helper for composable functions: construct the node, push it, run [content] children, pop.
 *
 * Use this instead of calling `startNode` / `endNode` manually — it's safer and shorter.
 *
 * ```
 * @SoulComposable
 * fun Column(modifier: SoulModifier = SoulModifier.Empty, content: @SoulComposable () -> Unit) {
 *     SoulComposer.current.composable(ColumnNode(modifier), content)
 * }
 * ```
 */
inline fun SoulComposer.composable(
    node: SoulNode,
    content: @SoulComposable () -> Unit = {},
) {
    startNode(node)
    try {
        content()
    } finally {
        endNode()
    }
}
