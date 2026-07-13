/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

/**
 * Replacement for the Otto event bus: typed, reflection-free subscriptions, but still delivers
 * synchronously and inline like Otto did - post() directly invokes every registered handler,
 * in registration order, before returning. Several call sites rely on that ordering (posting an
 * event and then reading state a handler is expected to have already mutated), so this is not a
 * Flow/coroutine-based bus.
 */
object EventBus {

    private class Subscription<T>(val owner: Any, val handler: (T) -> Unit)

    private val subscribers = HashMap<Class<*>, MutableList<Subscription<*>>>()

    fun <T : Any> subscribe(owner: Any, eventClass: Class<T>, handler: (T) -> Unit) {
        subscribers.getOrPut(eventClass) { mutableListOf() }.add(Subscription(owner, handler))
    }

    fun unsubscribeAll(owner: Any) {
        subscribers.values.forEach { list -> list.removeAll { it.owner === owner } }
    }

    fun post(event: Any) {
        CrashTracking.log("post(${event.javaClass.simpleName})")
        val list = subscribers[event.javaClass] ?: return
        // Snapshot before iterating: a handler may subscribe/unsubscribe (including itself)
        // while running, which would otherwise throw or skip entries mid-iteration.
        for (subscription in list.toList()) {
            try {
                @Suppress("UNCHECKED_CAST")
                (subscription as Subscription<Any>).handler(event)
            } catch (exc: RuntimeException) {
                // Matches the old Otto-era postEvent() behavior: one broken handler shouldn't
                // crash the app or stop the rest of this event's subscribers from running.
                CrashTracking.logHandledException(exc)
            }
        }
    }
}
