import java.io.IOException;
import java.io.PrintStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Dev-only java agent of the test client (tools/testclient), attached by `c up --hot` and `c up --window`, never
 * by anything else. Two jobs, both driven by request files that `c` drops into `<ctl>` (`req-<id>`: id, verb,
 * arguments one per line; answer in `res-<id>`: id, `ok`|`FAILED`, result lines):
 *
 * - **Hot reload** (`reload` + internal class names; only with `<live>`): `live` holds the classes the game runs
 *   (the installed jar's Soul classes at launch, updated by each `c reload`; the shared build/classes is never
 *   read). It joins Knot's classpath so classes new since launch load. Loaded classes are redefined in one batch
 *   (JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition: structural changes too), the others get the
 *   reloaded bytes when they first load. Mixins are never touched.
 * - **Input and frames for `--window`**, independent of the window system, so the game can sit unfocused on
 *   any workspace at any size: `move`/`button`/`scroll`/`key`/`char` go through Minecraft's own MouseHandler and
 *   KeyboardHandler on the client thread (the GLFW callbacks' targets), `shot` reads back the main render target,
 *   `size`/`cursor` report the frame. Coordinates are frame pixels. Input is refused unless this is the offline
 *   test client (-Dsoul.testclient=true, user SoulTester) on 127.0.0.1:25590 or no server.
 *
 * Argument: `<ctl>` or `<ctl>:<live>`. Log: logs/testagent.log in the game directory.
 */
public final class SoulTestAgent {
    private static final String PREFIX = "com/soulreturns/";
    private static final String MIXINS = "com/soulreturns/mixin/";
    private static final String TEST_SERVER = "127.0.0.1:25590";
    private static final long POLL_MS = 20;

    private static Instrumentation inst;
    private static Path ctl, live;
    /** classes some `c reload` changed: the ones that load later take the bytes from `live` */
    private static final Set<String> pushed = ConcurrentHashMap.newKeySet();
    private static PrintStream log;

    public static void premain(String args, Instrumentation instrumentation) throws IOException {
        inst = instrumentation;
        String[] parts = args.split(":");
        ctl = Path.of(parts[0]).toAbsolutePath();
        live = parts.length > 1 ? Path.of(parts[1]).toAbsolutePath() : null;
        Files.createDirectories(Path.of("logs"));
        log = new PrintStream(Files.newOutputStream(Path.of("logs/testagent.log")), true);
        log("requests in " + ctl + (live == null ? "" : ", live classes " + live));
        if (live != null) inst.addTransformer(new Fresh(), false);
        Thread t = new Thread(SoulTestAgent::serve, "soul-test-agent");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void log(String msg) {
        log.println(System.currentTimeMillis() + " " + msg);
    }

    private static void serve() {
        boolean onClassPath = live == null;
        while (true) {
            try {
                Thread.sleep(POLL_MS);
                if (!onClassPath) onClassPath = joinKnot();
                List<Path> requests = new ArrayList<>();
                try (DirectoryStream<Path> dir = Files.newDirectoryStream(ctl, "req-*")) {
                    dir.forEach(requests::add);
                }
                requests.sort(null);
                for (Path request : requests) {
                    List<String> lines = Files.readAllLines(request, StandardCharsets.UTF_8);
                    Files.delete(request);
                    if (lines.size() < 2) continue;
                    String body;
                    try {
                        body = handle(lines.get(1), lines.subList(2, lines.size()));
                    } catch (Throwable e) {
                        log(lines.get(1) + " FAILED: " + e);
                        body = "FAILED\n" + e;
                    }
                    answer(lines.get(0), body);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                log("request error: " + t);
            }
        }
    }

    private static void answer(String id, String body) throws IOException {
        Path tmp = ctl.resolve("res-" + id + ".tmp");
        Files.writeString(tmp, id + "\n" + body);
        Files.move(tmp, ctl.resolve("res-" + id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String handle(String verb, List<String> args) throws Exception {
        if (verb.equals("reload")) {
            if (live == null) return "FAILED\nnot started with --hot";
            return redefine(args);
        }
        Game game = Game.get();
        switch (verb) {
            case "size":
                return "ok\n" + game.width() + " " + game.height();
            case "cursor":
                return "ok\n" + game.onClient(game::cursor);
            case "shot":
                return "ok\n" + game.shot(Path.of(args.get(0)));
            default:
                game.checkTestClient();
                game.onClient(() -> {
                    game.input(verb, args);
                    return null;
                });
                return "ok";
        }
    }

    /** Knot exists once the game's main has started; until then there is nothing to join. */
    private static boolean joinKnot() {
        try {
            Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", false,
                    ClassLoader.getSystemClassLoader());
            Object launcher = base.getMethod("getLauncher").invoke(null);
            if (launcher == null) return false;
            Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher", false, ClassLoader.getSystemClassLoader())
                    .getMethod("addToClassPath", Path.class, String[].class).invoke(launcher, live, new String[0]);
            log("live classes added to Knot's classpath");
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static String redefine(List<String> names) {
        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.startsWith("com.soulreturns.") && !c.isHidden() && c.getClassLoader() != null) loaded.put(n, c);
        }
        List<ClassDefinition> defs = new ArrayList<>();
        List<String> later = new ArrayList<>();
        for (String name : names) {
            if (!name.startsWith(PREFIX) || name.startsWith(MIXINS)) continue;
            Class<?> c = loaded.get(name.replace('/', '.'));
            if (c == null) {
                later.add(name);
                continue;
            }
            try {
                defs.add(new ClassDefinition(c, Files.readAllBytes(live.resolve(name + ".class"))));
            } catch (IOException e) {
                return "FAILED\nunreadable " + name + ": " + e;
            }
        }
        long t = System.currentTimeMillis();
        try {
            if (!defs.isEmpty()) inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
        } catch (Throwable e) {
            log("redefinition FAILED for " + defs.size() + " classes: " + e);
            return "FAILED\n" + e;
        }
        pushed.addAll(later);
        for (ClassDefinition d : defs) pushed.add(d.getDefinitionClass().getName().replace('.', '/'));
        long ms = System.currentTimeMillis() - t;
        StringBuilder classes = new StringBuilder();
        for (ClassDefinition d : defs) classes.append(d.getDefinitionClass().getName()).append('\n');
        log("redefined " + defs.size() + " classes in " + ms + " ms (" + later.size() + " not loaded yet): "
                + classes.toString().replace('\n', ' '));
        return "ok\n" + defs.size() + "\n" + ms + "\n" + classes;
    }

    /** Classes first loaded after a `c reload` that changed them get the reloaded bytes, not the jar's. */
    private static final class Fresh implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                                ProtectionDomain pd, byte[] bytes) {
            if (redefining != null || name == null || !pushed.contains(name)) return null;
            try {
                return Files.readAllBytes(live.resolve(name + ".class"));
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    /** Minecraft through reflection (the agent sits on the system class loader, the game in Knot's). */
    private record Game(Object mc, ClassLoader loader) {
        static Game get() throws ReflectiveOperationException {
            ClassLoader app = ClassLoader.getSystemClassLoader();
            Object launcher = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", false, app)
                    .getMethod("getLauncher").invoke(null);
            if (launcher == null) throw new IllegalStateException("the game has not started yet");
            ClassLoader knot = (ClassLoader) Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher", false, app)
                    .getMethod("getTargetClassLoader").invoke(launcher);
            Object mc = Class.forName("net.minecraft.client.Minecraft", true, knot).getMethod("getInstance").invoke(null);
            if (mc == null) throw new IllegalStateException("the game has not started yet");
            return new Game(mc, knot);
        }

        Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        private static final Map<Class<?>, Class<?>> BOXES = Map.of(int.class, Integer.class, long.class, Long.class,
                double.class, Double.class, boolean.class, Boolean.class, float.class, Float.class);

        /** The first method of that name (any visibility, up the hierarchy) whose parameters take [args]. */
        Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                search:
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                    Class<?>[] types = m.getParameterTypes();
                    for (int i = 0; i < args.length; i++) {
                        Class<?> t = BOXES.getOrDefault(types[i], types[i]);
                        if (args[i] != null && !t.isInstance(args[i])) continue search;
                    }
                    m.setAccessible(true);
                    return m.invoke(target, args);
                }
            }
            throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        }

        Object field(String name) throws ReflectiveOperationException {
            return mc.getClass().getField(name).get(mc);
        }

        Object window() throws ReflectiveOperationException {
            return call(mc, "getWindow");
        }

        int width() throws ReflectiveOperationException {
            return (int) call(window(), "getWidth");
        }

        int height() throws ReflectiveOperationException {
            return (int) call(window(), "getHeight");
        }

        /** Runs on the client thread and waits ≤10 s for it. */
        <T> T onClient(java.util.concurrent.Callable<T> task) throws Exception {
            CompletableFuture<T> done = new CompletableFuture<>();
            call(mc, "execute", (Runnable) () -> {
                try {
                    done.complete(task.call());
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            return done.get(10, TimeUnit.SECONDS);
        }

        void checkTestClient() throws ReflectiveOperationException {
            if (!"true".equals(System.getProperty("soul.testclient")))
                throw new IllegalStateException("refused: not the test client");
            if (!"SoulTester".equals(call(call(mc, "getUser"), "getName")))
                throw new IllegalStateException("refused: not the offline test account");
            Object server = call(mc, "getCurrentServer");
            if (server != null && !TEST_SERVER.equals(server.getClass().getField("ip").get(server)))
                throw new IllegalStateException("refused: connected to a server other than " + TEST_SERVER);
        }

        /** Frame pixels -> GLFW window coordinates (equal on X11, differ on scaled Wayland outputs). */
        double[] toWindow(double x, double y) throws ReflectiveOperationException {
            Object w = window();
            return new double[] {x * (int) call(w, "getScreenWidth") / width(), y * (int) call(w, "getScreenHeight") / height()};
        }

        String cursor() throws ReflectiveOperationException {
            Object mouse = field("mouseHandler");
            Object w = window();
            double x = (double) call(mouse, "xpos") * width() / (int) call(w, "getScreenWidth");
            double y = (double) call(mouse, "ypos") * height() / (int) call(w, "getScreenHeight");
            return Math.round(x) + " " + Math.round(y);
        }

        void input(String verb, List<String> a) throws ReflectiveOperationException, ClassNotFoundException {
            long handle = (long) call(window(), "handle");
            Object mouse = field("mouseHandler"), keys = field("keyboardHandler");
            switch (verb) {
                case "move" -> {
                    double[] p = toWindow(Double.parseDouble(a.get(0)), Double.parseDouble(a.get(1)));
                    call(mouse, "onMove", handle, p[0], p[1]);
                }
                case "button" -> call(mouse, "onButton", handle,
                        type("net.minecraft.client.input.MouseButtonInfo").getConstructor(int.class, int.class)
                                .newInstance(Integer.parseInt(a.get(0)), Integer.parseInt(a.get(2))),
                        Integer.parseInt(a.get(1)));
                case "scroll" -> call(mouse, "onScroll", handle, 0.0, Double.parseDouble(a.get(0)));
                case "key" -> call(keys, "keyPress", handle, Integer.parseInt(a.get(1)),
                        type("net.minecraft.client.input.KeyEvent").getConstructor(int.class, int.class, int.class)
                                .newInstance(Integer.parseInt(a.get(0)), 0, Integer.parseInt(a.get(2))));
                case "char" -> call(keys, "charTyped", handle,
                        type("net.minecraft.client.input.CharacterEvent").getConstructor(int.class)
                                .newInstance(Integer.parseInt(a.get(0))));
                default -> throw new IllegalArgumentException("unknown verb " + verb);
            }
        }

        /** The main render target as a PNG, written off the client thread; waits ≤10 s. */
        String shot(Path target) throws Exception {
            CompletableFuture<String> done = new CompletableFuture<>();
            Object renderTarget = call(field("gameRenderer"), "mainRenderTarget");
            Method take = null;
            for (Method m : type("net.minecraft.client.Screenshot").getMethods()) {
                if (m.getName().equals("takeScreenshot") && m.getParameterCount() == 2) take = m;
            }
            Method takeShot = take;
            Consumer<Object> write = image -> new Thread(() -> {
                try {
                    Files.createDirectories(target.getParent());
                    call(image, "writeToFile", target);
                    call(image, "close");
                    done.complete(target.toString());
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            }, "soul-test-agent-shot").start();
            onClient(() -> takeShot.invoke(null, renderTarget, write));
            return done.get(10, TimeUnit.SECONDS);
        }
    }
}
