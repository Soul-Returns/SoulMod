import java.io.IOException;
import java.io.PrintStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Dev-only java agent of `c up --hot` (tools/testclient): pushes Soul classes that Gradle recompiles into
 * the running test client. Needs JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition, so changes
 * that add or remove methods, fields and lambdas redefine too.
 *
 * Argument: the class output directories, ':'-separated (build/classes/kotlin/main:build/classes/java/main).
 * - Loaded classes whose .class file changes are redefined in one batch once the compiler has stopped writing.
 * - Classes loaded later get the newest compiled bytes instead of the installed jar's (only files written
 *   after launch, so untouched classes keep the bytes Knot loaded).
 * - The directories join Knot's classpath, so classes that did not exist at launch load.
 * Mixins are never touched (they are applied once at load). Log: logs/hotswap.log in the game directory.
 */
public final class SoulHotswap {
    private static final String PREFIX = "com/soulreturns/";
    private static final String MIXINS = "com/soulreturns/mixin/";
    private static final long POLL_MS = 150, QUIET_MS = 250;

    private static Instrumentation inst;
    private static List<Path> dirs;
    /** mtimes at launch: the transformer substitutes only files written after it */
    private static Map<Path, Long> launch;
    /** mtimes as of the last redefinition (watcher thread only) */
    private static final Map<Path, Long> seen = new HashMap<>();
    private static PrintStream log;

    public static void premain(String args, Instrumentation instrumentation) throws IOException {
        inst = instrumentation;
        dirs = new ArrayList<>();
        for (String d : args.split(":")) dirs.add(Path.of(d).toAbsolutePath());
        Files.createDirectories(Path.of("logs"));
        log = new PrintStream(Files.newOutputStream(Path.of("logs/hotswap.log")), true);
        if (!inst.isRedefineClassesSupported()) log("redefinition unsupported by this JVM");
        launch = Map.copyOf(scan());
        seen.putAll(launch);
        log("watching " + dirs + " (" + seen.size() + " class files)");
        inst.addTransformer(new Fresh(), false);
        Thread t = new Thread(SoulHotswap::watch, "soul-hotswap");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void log(String msg) {
        log.println(System.currentTimeMillis() + " " + msg);
    }

    private static Map<Path, Long> scan() {
        Map<Path, Long> out = new HashMap<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    try {
                        out.put(p, Files.getLastModifiedTime(p).toMillis());
                    } catch (IOException ignored) {
                        // deleted between walk and stat: the compiler is still writing; the next scan sees it
                    }
                });
            } catch (IOException | java.io.UncheckedIOException ignored) {
                // same race on a directory
            }
        }
        return out;
    }

    private static String internalName(Path file) {
        for (Path dir : dirs) {
            if (file.startsWith(dir)) {
                String rel = dir.relativize(file).toString();
                return rel.substring(0, rel.length() - ".class".length());
            }
        }
        return null;
    }

    private static void watch() {
        boolean onClassPath = false;
        while (true) {
            try {
                Thread.sleep(POLL_MS);
                if (!onClassPath) onClassPath = joinKnot();
                Map<Path, Long> now = scan();
                List<Path> changed = changedSince(now);
                if (changed.isEmpty()) continue;
                // kotlinc writes many files: wait until the directories are quiet
                long t0 = System.currentTimeMillis();
                while (true) {
                    Thread.sleep(QUIET_MS);
                    Map<Path, Long> again = scan();
                    if (again.equals(now)) break;
                    now = again;
                }
                changed = changedSince(now);
                seen.clear();
                seen.putAll(now);
                redefine(changed, t0);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                log("watcher error: " + t);
            }
        }
    }

    private static List<Path> changedSince(Map<Path, Long> now) {
        List<Path> out = new ArrayList<>();
        now.forEach((p, m) -> {
            if (!m.equals(seen.get(p))) out.add(p);
        });
        return out;
    }

    /** Knot exists once the game's main has started; until then there is nothing to join. */
    private static boolean joinKnot() {
        try {
            Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", false,
                    ClassLoader.getSystemClassLoader());
            Object launcher = base.getMethod("getLauncher").invoke(null);
            if (launcher == null) return false;
            var add = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher", false,
                    ClassLoader.getSystemClassLoader()).getMethod("addToClassPath", Path.class, String[].class);
            for (Path dir : dirs) add.invoke(launcher, dir, new String[0]);
            log("class directories added to Knot's classpath");
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static void redefine(List<Path> changed, long firstSeen) {
        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.startsWith("com.soulreturns.") && !c.isHidden() && c.getClassLoader() != null) loaded.put(n, c);
        }
        List<ClassDefinition> defs = new ArrayList<>();
        int fresh = 0;
        for (Path p : changed) {
            String name = internalName(p);
            if (name == null || !name.startsWith(PREFIX) || name.startsWith(MIXINS)) continue;
            Class<?> c = loaded.get(name.replace('/', '.'));
            if (c == null) {
                fresh++;
                continue;
            }
            try {
                defs.add(new ClassDefinition(c, Files.readAllBytes(p)));
            } catch (IOException e) {
                log("unreadable " + p + ": " + e);
            }
        }
        if (defs.isEmpty()) {
            log("changed " + changed.size() + " files, none loaded (" + fresh + " load fresh when first used)");
            return;
        }
        long t = System.currentTimeMillis();
        try {
            inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
            StringBuilder names = new StringBuilder();
            for (ClassDefinition d : defs) names.append(' ').append(d.getDefinitionClass().getName());
            log("redefined " + defs.size() + " classes in " + (System.currentTimeMillis() - t) + " ms ("
                    + (t - firstSeen) + " ms after the first changed file; " + fresh + " not loaded yet):" + names);
        } catch (Throwable e) {
            log("redefinition FAILED for " + defs.size() + " classes: " + e);
        }
    }

    /** Hands out the newest compiled bytes for Soul classes first loaded after a recompile. */
    private static final class Fresh implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                                ProtectionDomain pd, byte[] bytes) {
            if (redefining != null || name == null || !name.startsWith(PREFIX) || name.startsWith(MIXINS)) return null;
            for (Path dir : dirs) {
                Path p = dir.resolve(name + ".class");
                try {
                    Long atLaunch = launch.get(p);
                    if (Files.isRegularFile(p) && (atLaunch == null || Files.getLastModifiedTime(p).toMillis() != atLaunch)) {
                        return Files.readAllBytes(p);
                    }
                } catch (IOException ignored) {
                    return null;
                }
            }
            return null;
        }
    }
}
