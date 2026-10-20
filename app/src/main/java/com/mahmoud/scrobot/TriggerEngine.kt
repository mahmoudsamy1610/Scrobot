package com.mahmoud.scrobot

import java.util.Calendar
import java.util.TimeZone

/** Something that happened on the phone and that a trigger may be waiting for. */
class TriggerEvent(
    val kind: Trigger.Kind,
    /** The package for `app`, the text for `text`, the state for `screen` and `power`. */
    val value: String = "",
    val pkg: String? = null,
    val window: String? = null,
)

/**
 * Decides which triggers a given event should start.
 *
 * The rules that keep this from going haywire live here:
 *
 *  - nothing fires while a script is running, so a script cannot set itself off again;
 *  - each trigger waits out its cooldown before it may run again;
 *  - a daily time only counts while the trigger is armed, so a phone that was off at 08:30 does
 *    not run the morning script at lunchtime;
 *  - `app` and `text` fire on the edge — when the app comes to the front, or the text first
 *    appears — not over and over while it stays there.
 *
 * The clock is injected, so all of it is unit tested.
 */
class TriggerEngine(private val now: () -> Long = System::currentTimeMillis) {

    enum class Decision { FIRE, DISABLED, NO_MATCH, COOLDOWN, BUSY, NOT_DUE }

    private class State {
        var lastFired = 0L
        var matching = false
        var armedAt = 0L
        var lastDaily = 0L
    }

    private val states = HashMap<String, State>()

    private fun state(name: String) = states.getOrPut(name) { State().apply { armedAt = now() } }

    /** Forgets everything: used when the trigger list is replaced. */
    fun reset() = states.clear()

    fun lastFired(name: String): Long = states[name]?.lastFired ?: 0L

    /**
     * The triggers that [event] starts, in order. Also remembers what is currently matching, so a
     * trigger does not fire again while the same app stays open or the same text stays on screen.
     */
    fun onEvent(triggers: List<Trigger>, event: TriggerEvent, busy: Boolean): List<Trigger> {
        val firing = ArrayList<Trigger>()
        for (trigger in triggers) {
            if (trigger.kind != event.kind) continue
            if (decide(trigger, event, busy) == Decision.FIRE) firing.add(trigger)
        }
        return firing
    }

    /** The same judgement for one trigger, spelled out so the reason can be logged and tested. */
    fun decide(trigger: Trigger, event: TriggerEvent, busy: Boolean): Decision {
        if (trigger.kind != event.kind) return Decision.NO_MATCH
        val state = state(trigger.name)
        val matches = matches(trigger, event)

        // Edge triggers: remember whether we are inside the match, and only act on the way in.
        val edge = trigger.kind == Trigger.Kind.APP || trigger.kind == Trigger.Kind.TEXT
        val wasMatching = state.matching
        if (edge) state.matching = matches

        if (!matches) return Decision.NO_MATCH
        if (edge && wasMatching) return Decision.NO_MATCH
        if (!trigger.enabled) return Decision.DISABLED
        if (busy) return Decision.BUSY
        val moment = now()
        if (state.lastFired > 0 && moment - state.lastFired < trigger.cooldown * 1000L) return Decision.COOLDOWN
        state.lastFired = moment
        return Decision.FIRE
    }

    private fun matches(trigger: Trigger, event: TriggerEvent): Boolean = when (trigger.kind) {
        Trigger.Kind.APP ->
            event.value.equals(trigger.match, true) &&
                (trigger.window.isEmpty() || event.window.orEmpty().contains(trigger.window, true))
        Trigger.Kind.TEXT ->
            event.value.contains(trigger.match, true) &&
                (trigger.pkg.isEmpty() || trigger.pkg.equals(event.pkg, true))
        Trigger.Kind.SCREEN, Trigger.Kind.POWER -> event.value.equals(trigger.match, true)
        Trigger.Kind.BOOT -> true
        Trigger.Kind.SCHEDULE -> false
    }

    // ---------------------------------------------------------------- schedules

    /**
     * The scheduled triggers that are due. Checked from the foreground service's heartbeat, so the
     * phone needs no alarm permission; the price is that a time can be a few seconds late, and
     * later than that if Android has stopped the app.
     */
    fun due(triggers: List<Trigger>, busy: Boolean, zone: TimeZone = TimeZone.getDefault()): List<Trigger> {
        val firing = ArrayList<Trigger>()
        val moment = now()
        for (trigger in triggers) {
            if (trigger.kind != Trigger.Kind.SCHEDULE) continue
            val state = state(trigger.name)
            if (!trigger.enabled) continue
            val isDue = if (trigger.every > 0) {
                val since = if (state.lastFired > 0) state.lastFired else state.armedAt
                moment - since >= trigger.every * 60_000L
            } else {
                // Only for a time that came round while this trigger was armed: a phone that was
                // asleep at 08:30 should not run the morning script when it wakes at lunchtime.
                val last = lastOccurrence(trigger.at, moment, zone)
                last > 0 && state.lastDaily < last && last >= state.armedAt
            }
            if (!isDue) continue
            if (busy) continue
            if (trigger.every <= 0) state.lastDaily = lastOccurrence(trigger.at, moment, zone)
            state.lastFired = moment
            firing.add(trigger)
        }
        return firing
    }

    /** When "HH:MM" last came round, at or before [moment], or 0 before the first one today. */
    fun lastOccurrence(at: String, moment: Long, zone: TimeZone): Long {
        val parts = at.split(':')
        if (parts.size != 2) return 0
        val hour = parts[0].toIntOrNull() ?: return 0
        val minute = parts[1].toIntOrNull() ?: return 0
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = moment
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis > moment) calendar.add(Calendar.DAY_OF_MONTH, -1)
        return calendar.timeInMillis
    }

    /** When a scheduled trigger is expected next, for the screen. 0 when it is not scheduled. */
    fun nextRun(trigger: Trigger, zone: TimeZone = TimeZone.getDefault()): Long {
        if (trigger.kind != Trigger.Kind.SCHEDULE || !trigger.enabled) return 0
        val state = state(trigger.name)
        val moment = now()
        return if (trigger.every > 0) {
            val since = if (state.lastFired > 0) state.lastFired else state.armedAt
            since + trigger.every * 60_000L
        } else {
            val last = lastOccurrence(trigger.at, moment, zone)
            val missed = state.lastDaily >= last || last < state.armedAt
            if (last <= 0) 0 else last + if (missed) 24 * 3600_000L else 0L
        }
    }
}
