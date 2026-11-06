package shots

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URLClassLoader

/**
 * The SAIC launcher's own code, converted from the head unit's firmware (see
 * tools/firmware/README.md; it's SAIC's, so it's never in the repo).  SAIC's
 * classes come from the converted jar; everything else (Android, the app,
 * libraries) from the test, so the launcher talks to the app's real service.
 */
class LauncherCode(jar: File, parent: ClassLoader) : URLClassLoader(arrayOf(jar.toURI().toURL()), parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        if (SAIC.none { name.startsWith(it) }) return super.loadClass(name, resolve)
        val c = findLoadedClass(name) ?: findClass(name)
        if (resolve) resolveClass(c)
        c
    }

    /**
     * The converted classes have no stack map frames (dex has nothing to make
     * them from), which Java's verifier needs: they're worked out here.
     */
    override fun findClass(name: String): Class<*> {
        val bytes = classBytes(name.replace('.', '/')) ?: standIn(name) ?: throw ClassNotFoundException(name)
        val reader = ClassReader(bytes)
        val writer = object : ClassWriter(COMPUTE_FRAMES) {
            override fun getCommonSuperClass(a: String, b: String): String = commonSuperClass(a, b)
        }
        reader.accept(writer, 0)
        val fixed = writer.toByteArray()
        return defineClass(name, fixed, 0, fixed.size)
    }

    /** SAIC's own additions to Android that the launcher names (stood in for, empty, as they're never used here). */
    val standIns = mutableListOf<String>()

    private fun standIn(name: String): ByteArray? {
        if (!name.startsWith("android.")) return null
        standIns += name
        val w = ClassWriter(0)
        w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            name.replace('.', '/'), null, "java/lang/Object", null)
        w.visitEnd()
        return w.toByteArray()
    }

    private fun classBytes(internalName: String): ByteArray? =
        getResourceAsStream("$internalName.class")?.use { it.readBytes() }

    /** A class's superclass and whether it's an interface, without loading it (it may be the one being made). */
    private fun describe(internalName: String): Pair<String?, Boolean> {
        if (SAIC.any { internalName.replace('/', '.').startsWith(it) }) {
            val r = ClassReader(classBytes(internalName) ?: return "java/lang/Object" to false)
            return r.superName to (r.access and Opcodes.ACC_INTERFACE != 0)
        }
        val c = try { Class.forName(internalName.replace('/', '.'), false, parent) } catch (_: Throwable) { return "java/lang/Object" to false }
        return c.superclass?.name?.replace('.', '/') to c.isInterface
    }

    private fun ancestors(internalName: String): List<String> {
        val out = ArrayList<String>()
        var t: String? = internalName
        while (t != null) { out += t; t = describe(t).first }
        return out
    }

    private fun commonSuperClass(a: String, b: String): String {
        if (describe(a).second || describe(b).second) return "java/lang/Object"
        val ofB = ancestors(b).toSet()
        return ancestors(a).firstOrNull { it in ofB } ?: "java/lang/Object"
    }

    /** An instance of [className] made without running its constructor (the launcher's need the whole car). */
    fun bare(className: String): Any = UNSAFE_ALLOCATE.invoke(UNSAFE, loadClass(className))

    companion object {
        /** The launcher's own packages and the car library it carries. */
        private val SAIC = listOf("com.saicmotor.launcher.", "com.saicmotor.carapi.", "com.saicmotor.adapterservice.", "com.saicmotor.ev.")

        /** Where the converted launcher is, if it was made for this run (FIRMWARE_LAUNCHER_APK). */
        val jar: File? get() = System.getProperty("launcher.jar")?.let(::File)?.takeIf { it.isFile }

        private val UNSAFE = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        private val UNSAFE_ALLOCATE = UNSAFE.javaClass.getMethod("allocateInstance", Class::class.java)

        fun set(target: Any, field: String, value: Any?) {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                try { c.getDeclaredField(field).apply { isAccessible = true }.set(target, value); return } catch (_: NoSuchFieldException) {}
                c = c.superclass
            }
            throw NoSuchFieldException(field)
        }

        fun get(target: Any, field: String): Any? {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                try { return c.getDeclaredField(field).apply { isAccessible = true }.get(target) } catch (_: NoSuchFieldException) {}
                c = c.superclass
            }
            throw NoSuchFieldException(field)
        }
    }
}
