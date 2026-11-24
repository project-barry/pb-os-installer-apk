package org.projectbarry.pbosinstaller.release

/** Release tags as pb-os makes them ("alpha-v0.5.4"). Anything else never counts as new enough. */
object ReleaseTag {
    /** The series ("alpha") and the numbers, or null. */
    fun parse(tag: String): Pair<String, List<Int>>? {
        val m = Regex("([a-z]+)-v(\\d{1,6}(?:\\.\\d{1,6})*)").matchEntire(tag) ?: return null
        return m.groupValues[1] to m.groupValues[2].split('.').map { it.toInt() }
    }

    /** Whether [tag] is [first] or newer: of the same series, its numbers compared one by one. */
    fun atLeast(tag: String, first: String): Boolean {
        val (series, a) = parse(tag) ?: return false
        val (wanted, b) = parse(first) ?: return false
        if (series != wanted) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c > 0
        }
        return true
    }
}
