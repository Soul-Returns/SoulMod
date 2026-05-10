package com.soulreturns.core.events

import com.soulreturns.util.SoulLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process pub/sub bus used to decouple data sources from features.
 *
 * Two ways to subscribe:
 *  1. **Annotation style** (preferred for objects with multiple handlers): pass `this` to
 *     [subscribe] and every `@HandleEvent`-annotated method gets wired up.
 *  2. **Lambda style** (preferred for one-off handlers): [subscribe] with a reified type and
 *     a function reference.
 *
 * [publish] dispatches synchronously on whatever thread calls it. Handlers are invoked in
 * registration order; an exception in one handler is logged and swallowed so the others still run.
 *
 * Implementation notes:
 *  - Handler lists are [CopyOnWriteArrayList] so registering a handler from inside another
 *    handler is safe (no `ConcurrentModificationException`).
 *  - The class→handlers map is [ConcurrentHashMap]. Both register and publish are non-blocking.
 *  - Inheritance is not walked: a handler for `Event` does NOT receive `AreaChanged`. Subscribe
 *    to the concrete subclass(es) you actually want.
 */
object Events {
    private val logger = SoulLogger("Soul/Events")

    @PublishedApi
    internal val handlers: ConcurrentHashMap<Class<out Event>, CopyOnWriteArrayList<(Event) -> Unit>> = ConcurrentHashMap()

    /** Lambda-style subscription. Returns Unit; no unsubscribe (events live for the session). */
    inline fun <reified T : Event> subscribe(noinline handler: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        registerHandler(T::class.java, handler as (Event) -> Unit)
    }

    /**
     * Annotation-style subscription. Scans [subscriber] for [HandleEvent]-annotated methods
     * with a single [Event]-subclass parameter and registers each one.
     *
     * Idempotent for the same instance: re-registering creates duplicate handlers, so call
     * exactly once per subscriber lifetime (typically from `Feature.register()`).
     */
    fun subscribe(subscriber: Any) {
        val cls: Class<*> = subscriber.javaClass
        var registered = 0
        for (method in cls.declaredMethods) {
            if (!method.isAnnotationPresent(HandleEvent::class.java)) continue
            val params = method.parameterTypes
            if (params.size != 1) {
                logger.warn("@HandleEvent on ${cls.simpleName}.${method.name} ignored — must have exactly 1 parameter")
                continue
            }
            val eventClass = params[0]
            if (!Event::class.java.isAssignableFrom(eventClass)) {
                logger.warn("@HandleEvent on ${cls.simpleName}.${method.name} ignored — parameter is not an Event")
                continue
            }
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val typed = eventClass as Class<out Event>
            registerHandler(typed) { event -> method.invoke(subscriber, event) }
            registered++
        }
        if (registered > 0) logger.info("Registered $registered @HandleEvent method(s) from ${cls.simpleName}")
    }

    /**
     * Synchronously dispatch [event] to every registered handler for its concrete class.
     * Handler exceptions are logged and swallowed so a single bad handler can't take down the bus.
     */
    fun publish(event: Event) {
        val list = handlers[event.javaClass] ?: return
        for (handler in list) {
            try {
                handler(event)
            } catch (t: Throwable) {
                logger.warn("Handler threw on ${event.javaClass.simpleName}", t)
            }
        }
    }

    @PublishedApi
    internal fun registerHandler(
        eventClass: Class<out Event>,
        handler: (Event) -> Unit
    ) {
        handlers.computeIfAbsent(eventClass) { CopyOnWriteArrayList() }.add(handler)
    }
}
