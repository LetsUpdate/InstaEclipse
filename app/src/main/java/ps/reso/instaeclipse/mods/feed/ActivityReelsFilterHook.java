package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

/**
 * Filters Reels/Clips items from the Activity (heart/bell icon) tab.
 *
 * <p>The existing {@link ReelsClientHook} only intercepts touch events on the Reels
 * swipe-refresh layout, so it cannot prevent Reels items from being <em>loaded</em>
 * into the Notifications/Activity feed.  This hook targets the feed-item parser that
 * Instagram uses to build activity entries and nulls out any item whose type resolves
 * to a clips/reels unit <em>before</em> it ever reaches a view binder.</p>
 *
 * <p>DexKit is used with four progressively broader string-based search strategies so
 * that the hook survives across multiple Instagram versions.  All paths are wrapped in
 * try/catch so a miss causes a graceful log message rather than a crash.</p>
 */
public class ActivityReelsFilterHook {

    private static final String CACHE_KEY = "ActivityFeedReelsParser";

    /** Per-class cache of String fields to avoid repeated getDeclaredFields() reflection. */
    private static final Map<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>();

    public void install(DexKitBridge bridge, ClassLoader classLoader) {

        XC_MethodHook filterHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.disableReels && !FeatureFlags.disableReelsExceptDM) return;

                Object result = param.getResult();
                if (result == null) return;

                if (isReelsOrClipsItem(result)) {
                    param.setResult(null);
                    XposedBridge.log("(InstaEclipse | ActivityReelsFilter): 🎬 Filtered Reels/Clips item from Activity feed"
                            + " [class=" + result.getClass().getName() + "]");
                    FeatureStatusTracker.setHooked("ActivityReelsFilter");
                }
            }
        };

        // ── Cache path ────────────────────────────────────────────────────────
        if (DexKitCache.isCacheValid()) {
            String cached = DexKitCache.loadString(CACHE_KEY);
            if (cached != null) {
                try {
                    int hooked = hookBridgeMethods(cached, classLoader, filterHook);
                    XposedBridge.log("(InstaEclipse | ActivityReelsFilter): ✅ Hooked from cache: "
                            + cached + " (" + hooked + " bridge methods)");
                    return;
                } catch (Throwable t) {
                    XposedBridge.log("(InstaEclipse | ActivityReelsFilter): ⚠️ Cache hook failed: " + t.getMessage());
                }
            }
        }

        // ── DexKit path ───────────────────────────────────────────────────────
        try {
            List<MethodData> methods = findActivityReelsParser(bridge);

            if (methods == null || methods.isEmpty()) {
                XposedBridge.log("(InstaEclipse | ActivityReelsFilter): ❌ Activity feed Reels parser not found via DexKit — no hook installed");
                return;
            }

            String targetClass = methods.get(0).getClassName();
            XposedBridge.log("(InstaEclipse | ActivityReelsFilter): DexKit found class: " + targetClass
                    + " (" + methods.size() + " candidate method(s))");

            DexKitCache.saveString(CACHE_KEY, targetClass);
            int hooked = hookBridgeMethods(targetClass, classLoader, filterHook);
            XposedBridge.log("(InstaEclipse | ActivityReelsFilter): ✅ Hooked: " + targetClass
                    + " (" + hooked + " bridge methods)");

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | ActivityReelsFilter): ❌ Exception during install: " + t.getMessage());
        }
    }

    // ── DexKit search strategies (most specific → most broad) ─────────────────

    private List<MethodData> findActivityReelsParser(DexKitBridge bridge) throws Exception {

        // Strategy 1 – activity-feed clips unit strings (most specific)
        List<MethodData> results = bridge.findMethod(
                FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings("clips_unit", "activity_clips")
                )
        );
        if (!results.isEmpty()) {
            XposedBridge.log("(InstaEclipse | ActivityReelsFilter): DexKit strategy 1 matched (" + results.size() + ")");
            return results;
        }

        // Strategy 2 – notification / activity clips type strings
        results = bridge.findMethod(
                FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings("notification_clips", "clips_mention")
                )
        );
        if (!results.isEmpty()) {
            XposedBridge.log("(InstaEclipse | ActivityReelsFilter): DexKit strategy 2 matched (" + results.size() + ")");
            return results;
        }

        // Strategy 3 – broader: any method that handles clips_netego in a news/activity context
        results = bridge.findMethod(
                FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings("clips_netego", "news_feed_item")
                )
        );
        if (!results.isEmpty()) {
            XposedBridge.log("(InstaEclipse | ActivityReelsFilter): DexKit strategy 3 matched (" + results.size() + ")");
            return results;
        }

        // Strategy 4 – last resort: any parser mentioning clips_netego + activity
        results = bridge.findMethod(
                FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings("clips_netego", "activity")
                )
        );
        if (!results.isEmpty()) {
            XposedBridge.log("(InstaEclipse | ActivityReelsFilter): DexKit strategy 4 matched (" + results.size() + ")");
            return results;
        }

        return results; // empty
    }

    // ── Hook all bridge methods on the resolved class ─────────────────────────

    private int hookBridgeMethods(String className, ClassLoader classLoader, XC_MethodHook hook)
            throws ClassNotFoundException {
        Class<?> clazz = Class.forName(className, false, classLoader);
        int count = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isBridge()) {
                XposedBridge.hookMethod(m, hook);
                XposedBridge.log("(InstaEclipse | ActivityReelsFilter): hooked bridge method "
                        + m.getName() + m.toGenericString().replaceFirst(".*\\(", "("));
                count++;
            }
        }
        return count;
    }

    // ── Reels / Clips item detection ──────────────────────────────────────────

    /**
     * Returns {@code true} if {@code item} represents a Reels or Clips feed entry.
     *
     * <p>Detection is done in two passes:
     * <ol>
     *   <li>Class-name check (fast, zero-allocation).</li>
     *   <li>String-field scan for Instagram's type identifiers such as {@code "clips"},
     *       {@code "reel"}, {@code "clips_unit"}, etc.  The field list is cached per
     *       class so that {@code getDeclaredFields()} is only called once per type.</li>
     * </ol>
     */
    private boolean isReelsOrClipsItem(Object item) {
        String className = item.getClass().getName().toLowerCase(java.util.Locale.US);
        if (className.contains("clips") || className.contains("reel")) {
            return true;
        }

        Field[] fields = fieldCache.computeIfAbsent(item.getClass(), cls -> {
            Field[] all = cls.getDeclaredFields();
            int count = 0;
            for (Field f : all) {
                if (f.getType() == String.class) count++;
            }
            Field[] stringFields = new Field[count];
            int idx = 0;
            for (Field f : all) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    stringFields[idx++] = f;
                }
            }
            return stringFields;
        });

        for (Field f : fields) {
            try {
                Object val = f.get(item);
                if (!(val instanceof String)) continue;
                String s = (String) val;
                // Match common Instagram Reels/Clips type identifiers
                if (s.equalsIgnoreCase("clips")
                        || s.equalsIgnoreCase("reel")
                        || s.startsWith("clips_")
                        || s.startsWith("reel_")
                        || s.equals("clips_netego")
                        || s.equals("clips_unit")
                        || s.equals("clips_activity")
                        || s.equals("clips_mention")
                        || s.equals("notification_clips")) {
                    XposedBridge.log("(InstaEclipse | ActivityReelsFilter): matched type field '"
                            + f.getName() + "' = '" + s + "'");
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
