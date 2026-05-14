package com.soulreturns.ui.composer

/**
 * Marks a function as a Soul UI composable — i.e. it must be invoked inside a
 * [SoulComposer.build] block, pushes/pops a node on the thread-local composer, and may invoke
 * other `@SoulComposable` functions as its children.
 *
 * This annotation is intentionally **not enforced** at compile time (no Compose-style compiler
 * plugin). It exists as a marker / documentation hint so call-site readers know "this affects
 * the current composition" without having to read the body. The runtime enforces composer
 * scope by throwing if [SoulComposer.current] is accessed outside a [SoulComposer.build] call.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
annotation class SoulComposable
