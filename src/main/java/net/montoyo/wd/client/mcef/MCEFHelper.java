package net.montoyo.wd.client.mcef;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

import net.montoyo.wd.utilities.Log;

/**
 * Reflection-based helper for MCEF integration.
 * No compile-time dependency on MCEF classes.
 */
import java.util.concurrent.ConcurrentHashMap;

public class MCEFHelper {
    private static boolean available = false;
    private static boolean checked = false;
    private static final ConcurrentHashMap<String, Method> methodCache = new ConcurrentHashMap<>();
    private static final Map<Object, Set<String>> consoleListeners = Collections.synchronizedMap(new IdentityHashMap<>());

    public static boolean isMCEFAvailable() {
        if (!checked) {
            checked = true;
            try {
                Class.forName("com.cinemamod.mcef.MCEF");
                available = true;
                Log.info("MCEF classes found");
            } catch (Exception e) {
                available = false;
                Log.info("MCEF not available: {}", e.getMessage());
            }
        }
        return available;
    }

    public static boolean isMCEFInitialized() {
        if (!isMCEFAvailable()) return false;
        try {
            Class<?> mcefClass = Class.forName("com.cinemamod.mcef.MCEF");
            Method initMethod = mcefClass.getMethod("isInitialized");
            return (boolean) initMethod.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    public static void scheduleInit(Consumer<Boolean> callback) {
        try {
            if (!isMCEFAvailable()) {
                callback.accept(false);
                return;
            }
            Class<?> mcefClass = Class.forName("com.cinemamod.mcef.MCEF");
            Class<?> listenerClass = Class.forName("com.cinemamod.mcef.listeners.MCEFInitListener");
            Method scheduleMethod = mcefClass.getMethod("scheduleForInit", listenerClass);
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("onInit".equals(method.getName())) {
                            callback.accept((boolean) args[0]);
                        }
                        return null;
                    });
            scheduleMethod.invoke(null, listener);
        } catch (Exception e) {
            Log.warning("Failed to schedule MCEF init: {}", e.getMessage());
            callback.accept(false);
        }
    }

    public static Object createBrowser(String url, boolean transparent, int width, int height) {
        try {
            Class<?> mcefClass = Class.forName("com.cinemamod.mcef.MCEF");
            Method createMethod = mcefClass.getMethod("createBrowser", String.class, boolean.class, int.class, int.class);
            Object browser = createMethod.invoke(null, url, transparent, width, height);
            if (browser != null) {
                disableMCEFCursor(browser);
                resetPixelStoreAfterPaint(browser);
            }
            return browser;
        } catch (Exception e) {
            Log.warning("Failed to create browser: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Set a no-op cursor change listener to prevent MCEF from changing the system cursor.
     * The webdisplays mod handles its own cursor display.
     */
    public static void disableMCEFCursor(Object browser) {
        try {
            Class<?> listenerClass = Class.forName("com.cinemamod.mcef.listeners.MCEFCursorChangeListener");
            Method setListenerMethod = findCachedMethod(browser.getClass(), "setCursorChangeListener", listenerClass);
            if (setListenerMethod != null) {
                Object noOpListener = java.lang.reflect.Proxy.newProxyInstance(
                        listenerClass.getClassLoader(),
                        new Class[]{listenerClass},
                        (proxy, method, args) -> null  // no-op: don't change system cursor
                );
                setListenerMethod.invoke(browser, noOpListener);
            }
        } catch (Exception e) {
            Log.warning("Failed to disable MCEF cursor: {}", e.getMessage());
        }
    }

    /**
     * Wrap the browser's onPaint to reset GL pixel store state after texture updates.
     * MCEF sets GL_UNPACK_ROW_LENGTH/SKIP_PIXELS/SKIP_ROWS for partial updates
     * but never resets them, which corrupts Minecraft's subsequent texture loading.
     */
    public static void resetPixelStoreAfterPaint(Object browser) {
        try {
            // We use a mixin approach - see MCEFStateCleanupMixin
        } catch (Exception e) {
            // Ignore
        }
    }

    public static void goBack(Object browser) {
        invokeBrowserAction(browser, "goBack");
    }

    public static void goForward(Object browser) {
        invokeBrowserAction(browser, "goForward");
    }

    private static void invokeBrowserAction(Object browser, String action) {
        if (browser == null) return;
        try {
            Method method = findCachedMethod(browser.getClass(), action);
            if (method != null) method.invoke(browser);
        } catch (Exception e) {
            Log.warning("Failed to {} browser: {}", action, e.getMessage());
        }
    }

    public static boolean loadBrowserUrl(Object browser, String url) {
        if (browser == null) return false;
        try {
            Method loadURLMethod = findCachedMethod(browser.getClass(), "loadURL", String.class);
            if (loadURLMethod == null) loadURLMethod = findCachedMethod(browser.getClass(), "loadUrl", String.class);
            if (loadURLMethod == null) loadURLMethod = findCachedMethod(browser.getClass(), "navigate", String.class);
            if (loadURLMethod == null) {
                Log.warning("Browser does not expose a URL loading method");
                return false;
            }
            loadURLMethod.invoke(browser, url);
            return true;
        } catch (Exception e) {
            Log.warning("Failed to load URL: {}", e.getMessage());
            return false;
        }
    }

    public static void registerConsoleMessageListener(Object browser, Consumer<String> listener) {
        registerConsoleMessageListener(browser, "default", listener);
    }

    /** Registers at most one listener per (browser, key); several independent keys may coexist on one browser. */
    public static void registerConsoleMessageListener(Object browser, String key, Consumer<String> listener) {
        if (browser == null) return;
        Set<String> keys = consoleListeners.computeIfAbsent(browser, ignored -> ConcurrentHashMap.newKeySet());
        if (!keys.add(key)) return;
        try {
            Class<?> mcefClass = Class.forName("com.cinemamod.mcef.MCEF");
            Object client = mcefClass.getMethod("getClient").invoke(null);
            Class<?> handlerClass = Class.forName("org.cef.handler.CefDisplayHandler");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    handlerClass.getClassLoader(), new Class<?>[]{handlerClass}, (ignored, method, args) -> {
                        if ("onConsoleMessage".equals(method.getName()) && args != null && args.length > 2
                                && args[0] == browser) {
                            listener.accept(String.valueOf(args[2]));
                        }
                        return method.getReturnType() == boolean.class ? false : null;
                    });
            Method addHandler = findCachedMethod(client.getClass(), "addDisplayHandler", handlerClass);
            if (addHandler == null) throw new NoSuchMethodException("MCEFClient.addDisplayHandler");
            addHandler.invoke(client, proxy);
        } catch (Exception e) {
            keys.remove(key);
            Log.warning("Failed to register browser console listener: {}", e.getMessage());
        }
    }

    public static String getBrowserTitle(Object browser) {
        try {
            Method getTitleMethod = findCachedMethod(browser.getClass(), "getTitle");
            if (getTitleMethod != null) {
                Object result = getTitleMethod.invoke(browser);
                return result != null ? result.toString() : "";
            }
        } catch (Exception e) {
        }
        return "";
    }

    public static String getBrowserUrl(Object browser) {
        try {
            Method getURLM = findCachedMethod(browser.getClass(), "getURL");
            if (getURLM != null) {
                Object result = getURLM.invoke(browser);
                return result != null ? result.toString() : "";
            }
        } catch (Exception e) {
        }
        return "";
    }

    public static void closeBrowser(Object browser) {
        HybridFrameCapture.unregister(browser);
        consoleListeners.remove(browser);
        try {
            Method execJSMethod = findCachedMethod(browser.getClass(), "executeJavaScript", String.class, String.class, int.class);
            if (execJSMethod != null) {
                execJSMethod.invoke(browser,
                    "try{document.querySelectorAll('video,audio').forEach(function(e){e.pause();e.muted=true;e.src='';e.load()});" +
                    "if(window.__wdAudioCtx)window.__wdAudioCtx.close();" +
                    "window.__wdAudioCtx=null;" +
                    "var OrigAC=window.AudioContext||window.webkitAudioContext;" +
                    "if(OrigAC){window.AudioContext=function(){var c=new OrigAC();c.close();return c}}}" +
                    "catch(e){}",
                    "", 0);
            }
        } catch (Exception e) {
        }
        try {
            injectJavascript(browser, "(function(){try{document.querySelectorAll('video,audio').forEach(function(e){e.pause();e.muted=true;e.src='';e.load()});if(window.__wdAudioCtx)window.__wdAudioCtx.close();}catch(e){}})()");
        } catch (Exception e) {
        }
        try {
            loadBrowserUrl(browser, "about:blank");
        } catch (Exception e) {
        }
        try {
            Method closeMethod = browser.getClass().getMethod("close");
            closeMethod.invoke(browser);
        } catch (Exception e) {
            Log.warning("Failed to close browser: {}", e.getMessage());
        }
    }

    /** Client only: is the local player the given display owner? */
    public static boolean isLocalPlayerOwner(String owner, String ownerUuid) {
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return false;
        return ownerUuid != null ? player.getUUID().toString().equals(ownerUuid)
                : player.getGameProfile().getName().equals(owner);
    }

    /** Forces CEF to repaint (OSR browsers only paint on change, so static pages would never feed a stream). */
    public static void requestRepaint(Object browser) {
        if (browser == null) return;
        try {
            browser.getClass().getMethod("invalidate").invoke(browser);
            return;
        } catch (Exception ignored) {
        }
        injectJavascript(browser, "(function(){var d=document.documentElement;if(!d)return;d.style.outline='1px solid transparent';"
                + "requestAnimationFrame(function(){d.style.outline=''})})()");
    }

    public static void resizeBrowser(Object browser, int width, int height) {
        try {
            Method resizeMethod = browser.getClass().getMethod("resize", int.class, int.class);
            resizeMethod.invoke(browser, width, height);
        } catch (Exception e) {
            Log.warning("Failed to resize browser: {}", e.getMessage());
        }
    }

    public static void sendMouseClick(Object browser, int x, int y, int button, boolean release, int clickCount) {
        try {
            if (release) {
                Method releaseMethod = findCachedMethod(browser.getClass(), "sendMouseRelease", int.class, int.class, int.class);
                if (releaseMethod != null) releaseMethod.invoke(browser, x, y, button);
            } else {
                Method pressMethod = findCachedMethod(browser.getClass(), "sendMousePress", int.class, int.class, int.class);
                if (pressMethod != null) pressMethod.invoke(browser, x, y, button);
            }
        } catch (Exception e) {
            Log.warning("Failed to send mouse click: {}", e.getMessage());
        }
    }

    public static void sendMouseMove(Object browser, int x, int y, boolean leave) {
        try {
            Method moveMethod = findCachedMethod(browser.getClass(), "sendMouseMove", int.class, int.class);
            if (moveMethod != null) moveMethod.invoke(browser, x, y);
        } catch (Exception e) {
            Log.warning("Failed to send mouse move: {}", e.getMessage());
        }
    }

    /** OSR browsers drop key events unless focused; remote typing arrives while nobody clicked the page locally. */
    private static void focus(Object browser) {
        try {
            Method setFocus = findCachedMethod(browser.getClass(), "setFocus", boolean.class);
            if (setFocus != null) setFocus.invoke(browser, true);
        } catch (Exception ignored) {
        }
    }

    public static void sendKeyEvent(Object browser, char c) {
        focus(browser);
        try {
            Method keyMethod = findCachedMethod(browser.getClass(), "sendKeyTyped", char.class, int.class);
            if (keyMethod != null) keyMethod.invoke(browser, c, 0);
        } catch (Exception e) {
            Log.warning("Failed to send key event: {}", e.getMessage());
        }
    }

    public static void sendKeyPress(Object browser, int keyCode, long scanCode, int modifiers) {
        focus(browser);
        try {
            int vkCode = glfwToVk(keyCode);
            Method method = findCachedMethod(browser.getClass(), "sendKeyPress", int.class, int.class, int.class);
            if (method != null) {
                method.invoke(browser, vkCode, (int) scanCode, modifiers);
            } else {
                method = findCachedMethod(browser.getClass(), "sendKeyPress", int.class, long.class, int.class);
                if (method != null) method.invoke(browser, vkCode, scanCode, modifiers);
            }
        } catch (Exception e) {
        }
    }

    public static void sendKeyRelease(Object browser, int keyCode, long scanCode, int modifiers) {
        try {
            int vkCode = glfwToVk(keyCode);
            Method method = findCachedMethod(browser.getClass(), "sendKeyRelease", int.class, int.class, int.class);
            if (method != null) {
                method.invoke(browser, vkCode, (int) scanCode, modifiers);
            } else {
                method = findCachedMethod(browser.getClass(), "sendKeyRelease", int.class, long.class, int.class);
                if (method != null) method.invoke(browser, vkCode, scanCode, modifiers);
            }
        } catch (Exception e) {
        }
    }

    private static int glfwToVk(int keyCode) {
        if (keyCode >= 65 && keyCode <= 90) return keyCode;
        if (keyCode >= 48 && keyCode <= 57) return keyCode;
        if (keyCode >= 290 && keyCode <= 301) return keyCode - 290 + 112;
        return switch (keyCode) {
            case 256 -> 27;
            case 257 -> 13;
            case 258 -> 9;
            case 259 -> 8;
            case 260 -> 45;
            case 261 -> 127;
            case 262 -> 39;
            case 263 -> 37;
            case 264 -> 40;
            case 265 -> 38;
            case 266 -> 33;
            case 267 -> 34;
            case 268 -> 36;
            case 269 -> 35;
            case 340 -> 16;
            case 341 -> 17;
            case 342 -> 18;
            case 344 -> 20;
            case 32 -> 32;
            case 39 -> 222;
            case 44 -> 188;
            case 45 -> 189;
            case 46 -> 190;
            case 47 -> 191;
            case 59 -> 186;
            case 61 -> 187;
            case 91 -> 219;
            case 92 -> 220;
            case 93 -> 221;
            case 96 -> 192;
            default -> keyCode;
        };
    }

    public static void sendMouseWheel(Object browser, int x, int y, double amount, int modifiers) {
        try {
            Method wheelMethod = findCachedMethod(browser.getClass(), "sendMouseWheel", int.class, int.class, double.class, int.class);
            if (wheelMethod != null) wheelMethod.invoke(browser, x, y, amount, modifiers);
        } catch (Exception e) {
            Log.warning("Failed to send mouse wheel: {}", e.getMessage());
        }
    }

    public static int getBrowserTextureId(Object browser) {
        try {
            Method getRendererMethod = findCachedMethod(browser.getClass(), "getRenderer");
            if (getRendererMethod == null) return 0;
            Object renderer = getRendererMethod.invoke(browser);
            if (renderer != null) {
                Method getTextureIDMethod = findCachedMethod(renderer.getClass(), "getTextureID");
                if (getTextureIDMethod != null) return (int) getTextureIDMethod.invoke(renderer);
            }
        } catch (Exception e) {
        }
        return 0;
    }

    public static void injectJavascript(Object browser, String code) {
        try {
            Method execJSMethod = findCachedMethod(browser.getClass(), "executeJavaScript", String.class, String.class, int.class);
            if (execJSMethod != null) {
                execJSMethod.invoke(browser, code, "", 0);
                return;
            }
        } catch (Exception e) {
        }
        try {
            Method execJSMethod = findCachedMethod(browser.getClass(), "executeJavaScript", String.class);
            if (execJSMethod != null) {
                execJSMethod.invoke(browser, code);
            }
        } catch (Exception e) {
        }
    }

    private static Method findCachedMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        String key = clazz.getName() + "#" + name + "(" + java.util.Arrays.toString(paramTypes) + ")";
        Method cached = methodCache.get(key);
        if (cached != null) return cached;
        cached = findMethod(clazz, name, paramTypes);
        if (cached != null) {
            cached.setAccessible(true);
            methodCache.put(key, cached);
        }
        return cached;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            try {
                return iface.getMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                // Continue through the interface hierarchy below.
            }
            Method inherited = findMethod(iface, name, paramTypes);
            if (inherited != null) return inherited;
        }
        return null;
    }
}