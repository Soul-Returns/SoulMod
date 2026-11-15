package com.soulreturns.core.events

/**
 * Marks a single-argument method on a registered subscriber as an event handler.
 *
 * The method takes exactly one [Event]-subclass parameter and is invoked synchronously on
 * whatever thread calls [Events.publish]. Register the subscriber once (typically from a
 * feature's `register()`) via [Events.subscribe].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HandleEvent
