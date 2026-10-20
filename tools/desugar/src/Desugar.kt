package com.mahmoud.scrobot.desugar

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.system.exitProcess

/**
 * Rewrites `invokedynamic` lambdas into plain classes.
 *
 * Android's runtime cannot run `LambdaMetafactory`, and `dx` — the only dexer that can be
 * installed here — does not desugar it, so anything that reached such a call site crashed with
 * `NoSuchMethodError: No static method metafactory(...)`. Google's D8 does this job in a normal
 * Android build, but it is only published on Google's Maven, which this build cannot reach.
 *
 * Each call site becomes a small generated class that implements the functional interface and
 * forwards to the lambda's implementation method, exactly as D8 and Retrolambda do.
 *
 * Only the shape the Kotlin compiler emits is supported: `LambdaMetafactory.metafactory` with a
 * static implementation method. Anything else fails the build rather than being quietly skipped.
 */
private const val ASM_API = Opcodes.ASM9
private const val METAFACTORY = "java/lang/invoke/LambdaMetafactory"

fun main(args: Array<String>) {
    if (args.size < 2 || args.size % 2 != 0) {
        System.err.println("usage: desugar <input.jar> <output.jar> [<input.jar> <output.jar> ...]")
        exitProcess(2)
    }
    var rewritten = 0
    var generated = 0
    for (i in args.indices step 2) {
        val result = DesugarJar(File(args[i]), File(args[i + 1])).run()
        rewritten += result.first
        generated += result.second
        println("desugar: ${File(args[i]).name} -> ${result.first} call sites, ${result.second} new classes")
    }
    println("desugar: $rewritten lambda call sites rewritten into $generated classes")
}

class DesugarJar(private val input: File, private val output: File) {

    /** Call sites per jar entry, in the order they appear, so the two passes line up exactly. */
    private val lambdas = LinkedHashMap<String, MutableList<LambdaClass>>()
    /** Implementation methods that a generated class has to be able to call. */
    private val widen = HashMap<String, MutableSet<String>>()
    private var callSites = 0

    fun run(): Pair<Int, Int> {
        val classes = LinkedHashMap<String, ByteArray>()
        val others = LinkedHashMap<String, ByteArray>()
        JarFile(input).use { jar ->
            for (entry in jar.entries()) {
                if (entry.isDirectory) continue
                val bytes = jar.getInputStream(entry).readBytes()
                if (entry.name.endsWith(".class")) classes[entry.name] = bytes else others[entry.name] = bytes
            }
        }

        // Pass 1: find the call sites and the implementation methods they need to reach.
        for ((name, bytes) in classes) scan(name, bytes)

        // Pass 2: rewrite the call sites and open up those implementation methods.
        val rewrittenClasses = LinkedHashMap<String, ByteArray>()
        for ((name, bytes) in classes) rewrittenClasses[name] = rewrite(name, bytes)

        var generated = 0
        JarOutputStream(output.outputStream().buffered()).use { out ->
            for ((name, bytes) in others) write(out, name, bytes)
            for ((name, bytes) in rewrittenClasses) write(out, name, bytes)
            for (list in lambdas.values) {
                for (lambda in list) {
                    write(out, lambda.internalName + ".class", lambda.generate())
                    generated++
                }
            }
        }
        return callSites to generated
    }

    private fun write(out: JarOutputStream, name: String, bytes: ByteArray) {
        out.putNextEntry(JarEntry(name))
        out.write(bytes)
        out.closeEntry()
    }

    // ---------------------------------------------------------------- pass 1

    /** Java 9+ copies inside a multi-release jar are never dexed, so they are left alone. */
    private fun skip(entry: String) = entry.startsWith("META-INF/versions/")

    private fun scan(entry: String, bytes: ByteArray) {
        if (skip(entry)) return
        val found = ArrayList<LambdaClass>()
        val reader = ClassReader(bytes)
        val owner = reader.className
        var counter = 0
        reader.accept(
            object : ClassVisitor(ASM_API) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(ASM_API) {
                    override fun visitInvokeDynamicInsn(
                        samName: String,
                        descriptor: String,
                        bsm: Handle,
                        vararg bsmArgs: Any?,
                    ) {
                        if (bsm.owner != METAFACTORY) {
                            fail("$owner.$name uses an unsupported bootstrap method: ${bsm.owner}.${bsm.name}")
                        }
                        if (bsm.name != "metafactory") {
                            fail("$owner.$name uses LambdaMetafactory.${bsm.name}, which this desugarer does not handle")
                        }
                        val impl = bsmArgs[1] as Handle
                        if (impl.tag != Opcodes.H_INVOKESTATIC) {
                            fail("$owner.$name captures a non-static lambda body (${impl.owner}.${impl.name}), which this desugarer does not handle")
                        }
                        found.add(
                            LambdaClass(
                                internalName = "$owner$\$Lambda$${counter++}",
                                callSiteOwner = owner,
                                samName = samName,
                                factoryDescriptor = descriptor,
                                samMethodType = bsmArgs[0] as Type,
                                impl = impl,
                                instantiated = bsmArgs[2] as Type,
                            ),
                        )
                        widen.getOrPut(impl.owner) { HashSet() }.add(impl.name + impl.desc)
                        callSites++
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        if (found.isNotEmpty()) lambdas[entry] = found
    }

    // ---------------------------------------------------------------- pass 2

    private fun rewrite(entry: String, bytes: ByteArray): ByteArray {
        if (skip(entry)) return bytes
        val reader = ClassReader(bytes)
        val owner = reader.className
        val here = lambdas[entry] ?: emptyList<LambdaClass>()
        val toOpen = widen[owner] ?: emptySet<String>()
        if (toOpen.isEmpty() && here.isEmpty()) return bytes

        // A fresh constant pool, so the BootstrapMethods table disappears with the call sites.
        val writer = ClassWriter(0)
        var counter = 0
        reader.accept(
            object : ClassVisitor(ASM_API, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    // A generated class is a separate class, so a private lambda body has to open up.
                    // Same package, so package-private is enough.
                    val opened = if (name + descriptor in toOpen) access and Opcodes.ACC_PRIVATE.inv() else access
                    val delegate = super.visitMethod(opened, name, descriptor, signature, exceptions)
                    return object : MethodVisitor(ASM_API, delegate) {
                        override fun visitInvokeDynamicInsn(
                            samName: String,
                            indyDescriptor: String,
                            bsm: Handle,
                            vararg bsmArgs: Any?,
                        ) {
                            val lambda = here[counter++]
                            // The captured values are already on the stack, in order.
                            super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                lambda.internalName,
                                FACTORY,
                                indyDescriptor,
                                false,
                            )
                        }
                    }
                }
            },
            ClassReader.EXPAND_FRAMES,
        )
        return writer.toByteArray()
    }

    companion object {
        const val FACTORY = "create"

        fun fail(message: String): Nothing {
            System.err.println("desugar: $message")
            exitProcess(1)
        }
    }
}

/** One call site, and the class generated to replace it. */
class LambdaClass(
    val internalName: String,
    val callSiteOwner: String,
    private val samName: String,
    /** Captured values in, functional interface out. */
    private val factoryDescriptor: String,
    private val samMethodType: Type,
    private val impl: Handle,
    private val instantiated: Type,
) {
    private val captured: Array<Type> = Type.getArgumentTypes(factoryDescriptor)
    private val iface: String = Type.getReturnType(factoryDescriptor).internalName
    private val samArgs: Array<Type> = samMethodType.argumentTypes
    private val samReturn: Type = samMethodType.returnType
    private val implArgs: Array<Type> = Type.getArgumentTypes(impl.desc)
    private val implReturn: Type = Type.getReturnType(impl.desc)

    fun generate(): ByteArray {
        // COMPUTE_MAXS only: the generated methods are straight-line code, so they need no frames,
        // and computing frames would mean loading the classes being rewritten.
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_FINAL or Opcodes.ACC_SUPER or Opcodes.ACC_SYNTHETIC,
            internalName,
            null,
            "java/lang/Object",
            arrayOf(iface),
        )
        for ((i, type) in captured.withIndex()) {
            writer.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC, "f$i", type.descriptor, null, null).visitEnd()
        }
        if (captured.isEmpty()) {
            writer.visitField(
                Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
                INSTANCE,
                "L$internalName;",
                null,
                null,
            ).visitEnd()
        }
        constructor(writer)
        factory(writer)
        samMethod(writer)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun constructor(writer: ClassWriter) {
        val descriptor = Type.getMethodDescriptor(Type.VOID_TYPE, *captured)
        val mv = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", descriptor, null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        var slot = 1
        for ((i, type) in captured.withIndex()) {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot)
            mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "f$i", type.descriptor)
            slot += type.size
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        if (captured.isEmpty()) {
            val clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
            clinit.visitCode()
            clinit.visitTypeInsn(Opcodes.NEW, internalName)
            clinit.visitInsn(Opcodes.DUP)
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false)
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, INSTANCE, "L$internalName;")
            clinit.visitInsn(Opcodes.RETURN)
            clinit.visitMaxs(0, 0)
            clinit.visitEnd()
        }
    }

    /** What the call site now calls: the captured values in, the interface out. */
    private fun factory(writer: ClassWriter) {
        val mv = writer.visitMethod(
            Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            DesugarJar.FACTORY,
            factoryDescriptor,
            null,
            null,
        )
        mv.visitCode()
        if (captured.isEmpty()) {
            // Nothing is captured, so one shared instance will do, like the runtime's own lambdas.
            mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, INSTANCE, "L$internalName;")
        } else {
            mv.visitTypeInsn(Opcodes.NEW, internalName)
            mv.visitInsn(Opcodes.DUP)
            var slot = 0
            for (type in captured) {
                mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot)
                slot += type.size
            }
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                internalName,
                "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE, *captured),
                false,
            )
        }
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** The interface method: captured values, then the arguments, then the lambda body. */
    private fun samMethod(writer: ClassWriter) {
        val mv = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            samName,
            samMethodType.descriptor,
            null,
            null,
        )
        mv.visitCode()
        for ((i, type) in captured.withIndex()) {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "f$i", type.descriptor)
            adapt(mv, type, implArgs[i])
        }
        var slot = 1
        val instantiatedArgs = instantiated.argumentTypes
        for ((i, type) in samArgs.withIndex()) {
            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot)
            slot += type.size
            // The interface's erased type first becomes the type the lambda was written with,
            // then whatever the body actually takes.
            val wanted = instantiatedArgs.getOrNull(i) ?: implArgs[captured.size + i]
            adapt(mv, type, wanted)
            adapt(mv, wanted, implArgs[captured.size + i])
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, impl.owner, impl.name, impl.desc, impl.isInterface)
        if (samReturn.sort == Type.VOID) {
            when (implReturn.size) {
                0 -> {}
                1 -> mv.visitInsn(Opcodes.POP)
                else -> mv.visitInsn(Opcodes.POP2)
            }
            mv.visitInsn(Opcodes.RETURN)
        } else {
            adapt(mv, implReturn, samReturn)
            mv.visitInsn(samReturn.getOpcode(Opcodes.IRETURN))
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Boxes, unboxes or casts the value on the stack from one type to another. */
    private fun adapt(mv: MethodVisitor, from: Type, to: Type) {
        if (from == to) return
        val fromPrimitive = from.sort in Type.BOOLEAN..Type.DOUBLE
        val toPrimitive = to.sort in Type.BOOLEAN..Type.DOUBLE
        when {
            fromPrimitive && toPrimitive -> widen(mv, from, to)
            fromPrimitive && !toPrimitive -> box(mv, from)
            !fromPrimitive && toPrimitive -> unbox(mv, to)
            else -> if (to.internalName != "java/lang/Object") mv.visitTypeInsn(Opcodes.CHECKCAST, to.internalName)
        }
    }

    private fun box(mv: MethodVisitor, primitive: Type) {
        val boxed = boxedName(primitive)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, boxed, "valueOf", "(${primitive.descriptor})L$boxed;", false)
    }

    private fun unbox(mv: MethodVisitor, primitive: Type) {
        val boxed = boxedName(primitive)
        mv.visitTypeInsn(Opcodes.CHECKCAST, boxed)
        val method = when (primitive.sort) {
            Type.BOOLEAN -> "booleanValue"
            Type.CHAR -> "charValue"
            Type.BYTE -> "byteValue"
            Type.SHORT -> "shortValue"
            Type.INT -> "intValue"
            Type.FLOAT -> "floatValue"
            Type.LONG -> "longValue"
            else -> "doubleValue"
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxed, method, "()${primitive.descriptor}", false)
    }

    private fun widen(mv: MethodVisitor, from: Type, to: Type) {
        val opcode = when (from.sort to to.sort) {
            Type.INT to Type.LONG, Type.SHORT to Type.LONG, Type.BYTE to Type.LONG, Type.CHAR to Type.LONG -> Opcodes.I2L
            Type.INT to Type.FLOAT, Type.SHORT to Type.FLOAT, Type.BYTE to Type.FLOAT, Type.CHAR to Type.FLOAT -> Opcodes.I2F
            Type.INT to Type.DOUBLE, Type.SHORT to Type.DOUBLE, Type.BYTE to Type.DOUBLE, Type.CHAR to Type.DOUBLE -> Opcodes.I2D
            Type.LONG to Type.FLOAT -> Opcodes.L2F
            Type.LONG to Type.DOUBLE -> Opcodes.L2D
            Type.FLOAT to Type.DOUBLE -> Opcodes.F2D
            Type.BYTE to Type.INT, Type.SHORT to Type.INT, Type.CHAR to Type.INT,
            Type.BOOLEAN to Type.INT, Type.INT to Type.INT,
            -> return
            else -> DesugarJar.fail("cannot convert ${from.className} to ${to.className} in $internalName")
        }
        mv.visitInsn(opcode)
    }

    private fun boxedName(primitive: Type): String = when (primitive.sort) {
        Type.BOOLEAN -> "java/lang/Boolean"
        Type.CHAR -> "java/lang/Character"
        Type.BYTE -> "java/lang/Byte"
        Type.SHORT -> "java/lang/Short"
        Type.INT -> "java/lang/Integer"
        Type.FLOAT -> "java/lang/Float"
        Type.LONG -> "java/lang/Long"
        else -> "java/lang/Double"
    }

    companion object {
        private const val INSTANCE = "INSTANCE"
    }
}
