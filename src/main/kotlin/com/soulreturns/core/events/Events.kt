package com.soulreturns.core.events

import com.soulreturns.util.SoulLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process pub/sub bus used to decouple data sources from features.
 *
 * Two ways to subscribe:
 *  1. **Annotation style** — pass `this` to [subscribe] and every `@HandleEvent`-annotated
 *     method gets wired up.
 *  2. **Lambda style** — [subscribe] with a reified type and a function reference.
 *
 * [publish] dispatches synchronously on the calling thread. Handlers run in registration order;
 * an exception in one handler is logged and swallowed so the others still run.
 *
 * Handler lists are [CopyOnWriteArrayList] so subscribing from inside a handler is safe.
 * Inheritance is NOT walked — subscribe to the concrete subclass you want.
 */
object Events {
    private val logger = SoulLogger("Soul/Events")

    @PublishedApi
    internal val handlers: ConcurrentHashMap<Class<out Event>, CopyOnWriteArrayList<(Event) -> Unit>> = ConcurrentHashMap()

    inline fun <reified T : Event> subscribe(noinline handler: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        registerHandler(T::class.java, handler as (Event) -> Unit)
    }

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
        handler: (Event) -> Unit,
    ) {
        handlers.computeIfAbsent(eventClass) { CopyOnWriteArrayList() }.add(handler)
    }
}
