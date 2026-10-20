package com.mahmoud.scrobot

/**
 * Android's list of enabled accessibility services: colon-separated `package/class` entries in the
 * secure setting `enabled_accessibility_services`. Reading and editing that text is all plain
 * string work, kept here so it can be unit tested; [AccessibilityRepair] does the Android part.
 */
object AccessibilityList {

    const val PACKAGE = "com.mahmoud.scrobot"
    const val CLASS = "com.mahmoud.scrobot.HandsService"

    /** Android writes either form, so both have to be recognised. */
    val FORMS = listOf("$PACKAGE/$CLASS", "$PACKAGE/.HandsService")

    fun entries(list: String?): List<String> =
        list.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    fun contains(list: String?): Boolean = entries(list).any { it in FORMS }

    /** The list with Scrobot removed, leaving every other service alone. */
    fun without(list: String?): String = entries(list).filter { it !in FORMS }.joinToString(":")

    /** The list with Scrobot present exactly once, at the end. */
    fun with(list: String?): String =
        (entries(list).filter { it !in FORMS } + FORMS[0]).joinToString(":")
}
