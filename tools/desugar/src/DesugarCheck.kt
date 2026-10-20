package com.mahmoud.scrobot.desugar

import java.io.File
import kotlin.reflect.typeOf
import kotlin.time.TimeSource

/**
 * Exercises the Kotlin standard library functions that were rewritten by [DesugarJar], so the
 * rewritten bytecode can be run and compared against the original. `tools/check-desugar.sh` runs
 * this twice — once with the original kotlin-stdlib, once with the desugared one — and the two
 * outputs have to match exactly.
 *
 * It covers every stdlib class that contained an `invokedynamic` lambda, starting with
 * `File.readLines`, which is what crashed the app.
 */
fun main() {
    val out = StringBuilder()
    fun show(name: String, value: Any?) {
        out.append(name).append(" = ").append(value).append('\n')
    }

    // kotlin.io.FilesKt — the crash in the report came from readLines.
    val file = File.createTempFile("desugar-check", ".txt")
    file.writeText("one\ntwo\nthree\n")
    show("readLines", file.readLines())
    val collected = ArrayList<String>()
    file.forEachLine { collected.add(it.uppercase()) }
    show("forEachLine", collected)
    show("useLines", file.useLines { lines -> lines.map { it.length }.toList() })
    show("readText", file.readText().trim().split("\n"))
    val dir = File(file.parentFile, "desugar-check-dir").apply { mkdirs() }
    File(dir, "a.txt").writeText("x")
    show("walkTopDown", dir.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    show("copyTo", file.copyTo(File(dir, "copy.txt"), overwrite = true).readLines().size)
    dir.deleteRecursively()
    file.delete()

    // kotlin.io.TextStreamsKt
    show("reader.readLines", "a\nb".reader().readLines())
    show("lineSequence", "a\nb\nc".reader().buffered().lineSequence().map { it + "!" }.toList())

    // kotlin.collections
    val people = listOf("Mahmoud" to 31, "Ahmed" to 24, "Sara" to 24)
    show("sortedBy", people.sortedBy { it.second }.map { it.first })
    show("sortedWith", people.sortedWith(compareBy({ it.second }, { it.first })).map { it.first })
    show("groupBy", people.groupBy { it.second }.mapValues { entry -> entry.value.map { it.first } })
    show("associateBy", people.associateBy { it.first }.keys.sorted())
    show("minus", listOf(1, 2, 3, 4) - setOf(2, 4))
    show("sumOf", people.sumOf { it.second })
    show("maxByOrNull", people.maxByOrNull { it.second }?.first)
    show("flatMap", listOf("ab", "cd").flatMap { it.toList() })
    show("partition", (1..6).partition { it % 2 == 0 })
    show("distinctBy", listOf("aa", "ab", "bc").distinctBy { it.first() })

    // kotlin.collections on arrays, including unsigned ones
    show("array sortedBy", arrayOf("ccc", "a", "bb").sortedBy { it.length })
    show("array groupBy", intArrayOf(1, 2, 3, 4).toTypedArray().groupBy { it % 2 })
    show("uint sorted", uintArrayOf(3u, 1u, 2u).sorted())
    show("uint sumOf", uintArrayOf(3u, 1u, 2u).sumOf { it.toInt() })

    // kotlin.sequences
    val seq = sequenceOf(5, 3, 9, 1)
    show("sequence sortedBy", seq.sortedBy { it }.toList())
    show("sequence minus list", (seq - listOf(3, 9)).toList())
    show("sequence minus one", (seq - 5).toList())
    show("sequence minus array", (seq - arrayOf(1)).toList())
    show("sequence minus sequence", (seq - sequenceOf(9)).toList())
    show("sequence flatMap", seq.flatMap { listOf(it, it) }.take(4).toList())
    show("generateSequence", generateSequence(1) { if (it < 5) it + 1 else null }.toList())
    show("sequence groupBy", seq.groupBy { it % 2 })

    // kotlin.text
    show("split", "a,b,,c".split(",").filter { it.isNotEmpty() })
    show("splitToSequence", "a b  c".splitToSequence(" ").filter { it.isNotBlank() }.toList())
    show("trimIndent", "\n    one\n      two\n    ".trimIndent())
    show("prependIndent", "one\ntwo".prependIndent("> "))
    show("replaceIndent", "   one\n   two".replaceIndent("-"))
    show("string sumOf", "abc".sumOf { it.code })
    show("groupBy chars", "hello".groupBy { it }.keys.joinToString(""))
    show("directionality", CharCategory.values().take(2).map { it.name })

    // kotlin.text.Regex
    val regex = Regex("(\\w+)@(\\w+)\\.com")
    show("regex replace", regex.replace("write to me@example.com now") { m -> m.groupValues[1].uppercase() })
    show("regex find", regex.find("a@b.com")?.groupValues)
    show("regex groups", regex.find("a@b.com")?.groups?.map { it?.value })
    show("regex split", Regex("[,;]").split("a,b;c"))
    show("regex findAll", Regex("\\d+").findAll("1 22 333").map { it.value }.toList())

    // kotlin.comparisons
    val byLength = compareBy<String> { it.length }.thenByDescending { it }
    show("compareBy", listOf("bb", "a", "cc").sortedWith(byLength))
    show("maxOf", maxOf("a", "bb", byLength))

    // kotlin.coroutines context (CombinedContext, CoroutineContext$DefaultImpls)
    val context = TestElement("first") + TestElement2("second")
    show("context fold", context.fold("") { acc, element -> acc + element })
    show("context get", context[TestElement2]?.name)
    show("context minusKey", context.minusKey(TestElement2).fold("") { acc, e -> acc + e })

    // kotlin.time
    val mark = TimeSource.Monotonic.markNow()
    show("time source", mark.elapsedNow() >= kotlin.time.Duration.ZERO)

    // kotlin.jvm.internal.TypeReference
    show("typeOf", typeOf<List<Map<String, Int>>>().toString())

    // kotlin.collections.AbstractMap / AbstractCollection
    show("map equals", mapOf("a" to 1) == LinkedHashMap<String, Int>().apply { put("a", 1) })
    show("collection toString", object : AbstractCollection<String>() {
        override val size = 2
        override fun iterator() = listOf("x", "y").iterator()
    }.toString())

    print(out)
}

private class TestElement(val name: String) : kotlin.coroutines.CoroutineContext.Element {
    override val key = Key

    companion object Key : kotlin.coroutines.CoroutineContext.Key<TestElement>

    override fun toString() = "[$name]"
}

private class TestElement2(val name: String) : kotlin.coroutines.CoroutineContext.Element {
    override val key = Key

    companion object Key : kotlin.coroutines.CoroutineContext.Key<TestElement2>

    override fun toString() = "[$name]"
}
