package com.soulreturns.core.events

/**
 * Marks a single-argument method on a registered subscriber as an event handler.
 *
 * The annotated method:
 *  - Must take exactly one parameter typed as a subclass of [Event].
 *  - Returns `Unit` (return value, if any, is ignored).
 *  - Will be invoked synchronously on whatever thread calls [Events.publish].
 *
 * Register a subscriber once — typically from a feature's `register()` — via
 * [Events.subscribe]; every annotated method on it gets wired to its event type.
 *
 * ```
 * object MyFeature {
 *     fun register() = Events.subscribe(this)
 *
 *     @HandleEvent
 *     fun onAreaChanged(event: AreaChanged) { … }
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HandleEvent
