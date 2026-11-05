package com.pachka.leftmessages;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * LeftMessages — renders your own outgoing messages on the LEFT side of the chat,
 * exactly like messages from the other person (Telegram Desktop style), with zero
 * extra avatars and with media/reactions/quotes handled natively.
 *
 * The smart thread this whole plugin hangs on:
 *
 *     org.telegram.messenger.MessageObject.isOutOwner()
 *
 * This single boolean drives the entire outgoing-vs-incoming rendering pipeline:
 * ChatMessageCell calls it 300+ times for bubble side, colors, corner radii, text/media
 * layout, reactions alignment, time/checkmark style, and cell placement in the list.
 *
 * The lie is scoped to MessageObject instances that are currently bound to a
 * ChatMessageCell (weak set filled from setMessageObject), so network-side behavior
 * (notifications, call ringing, sending) is untouched: fresh messages are not bound yet.
 *
 * Colors: an in<->out theme-key remap on ChatMessageCell.getThemedColor(int) plus a
 * transient MessageDrawable.overrideColor around the color-baking setTop() calls puts
 * the bubbles back into the user's own "out" color while keeping incoming geometry.
 *
 * Bubble merging: ChatActivityAdapter.onBindViewHolder computes the "glue to previous /
 * next message" flags by comparing isOutOwner() of two messages. While it runs, the
 * current message is not yet bound (real value) but its neighbor may already be bound
 * (lie), which breaks merging. The lie is therefore suspended during onBindViewHolder
 * (and re-activated inside the nested setMessageObject call), so the flags are computed
 * from real out-statuses and then applied to cells that render with the lie.
 */
public class LeftHookBridge {

    private static final String TAG = "LeftMessages";
    private static final List<XC_MethodHook.Unhook> unhooks = new ArrayList<>();
    private static volatile boolean installed = false;

    // ---------------- runtime state (controlled from Python) ----------------

    /** Master switch: render outgoing messages on the left. */
    private static volatile boolean enabled = false;

    /** Also flip messages inside Saved Messages. */
    private static volatile boolean flipSaved = true;

    /** Paint flipped messages in the user's own (out) bubble color. */
    private static volatile boolean ownColors = true;

    /**
     * > 0 while ChatActivityAdapter.onBindViewHolder is running: isOutOwner() must
     * return the REAL value so the merge (pinned top/bottom) flags are computed
     * consistently for both compared messages.
     */
    private static volatile int outOwnerSuspensions = 0;

    /** Message objects currently bound to a ChatMessageCell (weak, synchronized). */
    private static final Set<Object> rendered =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    /** Last successful setMessageObject invocation per cell, for cheap hot rebind on toggle. */
    private static final Map<Object, Object[]> lastBinds =
            Collections.synchronizedMap(new WeakHashMap<Object, Object[]>());

    /** Cached client user id per account (Saved Messages detection). */
    private static final Map<Integer, Long> clientUserIds = new HashMap<>();

    // ---------------- reflection caches (hot path) ----------------

    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();
    private static final Map<String, Method> methodCache = new ConcurrentHashMap<>();

    /** key_chat_in* -> key_chat_out* theme color remap (built lazily from Theme fields). */
    private static final Map<Integer, Integer> inToOutColors = new HashMap<>();
    private static volatile boolean inToOutColorsBuilt = false;

    /** Marker: a lying message's background color bake (setTop) is in progress on this thread. */
    private static final ThreadLocal<Boolean> lieBakeMarker = new ThreadLocal<Boolean>();

    /** Pending suspension restore for the nested setMessageObject call (self-healing). */
    private static final ThreadLocal<Integer> pendingRestore = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ---------------- lifecycle ----------------

    public void onLoad(Object context, Object bridge) {
        ClassLoader cl = context != null
                ? context.getClass().getClassLoader()
                : getClass().getClassLoader();
        installHooks(cl);
    }

    public void onUnload() {
        setEnabled(false);
        unhookAll();
        synchronized (rendered) {
            rendered.clear();
        }
        lastBinds.clear();
    }

    public static synchronized void installHooks(ClassLoader classLoader) {
        if (installed) {
            log("Hooks already installed");
            return;
        }
        if (classLoader == null) {
            classLoader = LeftHookBridge.class.getClassLoader();
        }
        try {
            Class<?> moClass = classLoader.loadClass("org.telegram.messenger.MessageObject");
            Class<?> cellClass = classLoader.loadClass("org.telegram.ui.Cells.ChatMessageCell");
            Class<?> mdClass = classLoader.loadClass("org.telegram.ui.ActionBar.MessageDrawable");

            // 1) THE SMART THREAD: isOutOwner() -> false for rendered outgoing messages.
            hookNoArg(moClass, "isOutOwner", OUT_OWNER_HOOK);

            // 2) Track rendered messages. Avatars are NOT suppressed: with the flip the
            //    incoming layout naturally shows avatar/name wherever it belongs, even
            //    for the user's own messages. The nickname/tag header above the bubble is
            //    suppressed though (isNeedAuthorName -> false for flipped messages).
            for (Method m : cellClass.getDeclaredMethods()) {
                if (m.getName().equals("setMessageObject") && m.getParameterTypes().length >= 1
                        && m.getParameterTypes()[0].getName().contains("MessageObject")) {
                    unhooks.add(XposedBridge.hookMethod(m, SET_MESSAGE_OBJECT_HOOK));
                }
            }
            hookNoArg(cellClass, "isNeedAuthorName", NEED_AUTHOR_NAME_HOOK);

            // 3) Album grid consistency: GroupedMessages.calculate() bakes the album layout
            //    for the real side (computed before cells bind), so force the lie during
            //    it — the grid is then built for the same (incoming) side the cells render.
            Class<?> groupClass = null;
            for (String name : new String[]{
                    "org.telegram.messenger.MessageObject$GroupedMessages",
                    "org.telegram.messenger.GroupedMessages"
            }) {
                try {
                    groupClass = classLoader.loadClass(name);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (groupClass != null) {
                hookNoArg(groupClass, "calculate", CALCULATE_HOOK);
            } else {
                log("GroupedMessages class not found");
            }

            // 4) Own bubble colors: theme-key remap on color lookups.
            hookNoArgFirstInt(cellClass, "getThemedColor", GET_THEMED_COLOR_HOOK);

            // 5) Own bubble colors: transient overrideColor around background color bakes.
            hookByFirstParam(cellClass, "drawBackground", Canvas.class, LIE_BAKE_SCOPE_HOOK);
            hookByFirstParam(cellClass, "drawBackgroundInternal", Canvas.class, LIE_BAKE_SCOPE_HOOK);
            hookByName(cellClass, "setBackgroundTopY", LIE_BAKE_SCOPE_HOOK);
            hookAll(mdClass, "setTop", SET_TOP_HOOK);

            // 5b) Flat themes render the bubble as a cached nine-patch whose color is
            //     resolved inside getBackgroundDrawable() via getColor(isOut ? out : in)
            //     at draw time. Remap in->out keys there (marker-scoped) so the flat path
            //     picks the out palette too; gradient themes are handled by SET_TOP_HOOK.
            hookNoArgFirstInt(mdClass, "getColor", COLOR_KEY_REMAP_HOOK);
            hookNoArgFirstInt(mdClass, "getCurrentColor", COLOR_KEY_REMAP_HOOK);

            // 6) Merge fix: suspend the lie while the adapter computes merge flags.
            Class<?> adapterClass = null;
            for (String name : new String[]{
                    "org.telegram.ui.ChatActivity$ChatActivityAdapter",
                    "org.telegram.ui.ChatActivityAdapter"
            }) {
                try {
                    adapterClass = classLoader.loadClass(name);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (adapterClass != null) {
                hookByFirstParamName(adapterClass, "onBindViewHolder", BIND_HOOK);
                log("Chat adapter hooked: " + adapterClass.getName());
            } else {
                log("Chat adapter class not found");
            }

            installed = true;
            log("Hooks installed: " + unhooks.size());
        } catch (Throwable t) {
            log("installHooks fatal: " + t);
        }
    }

    private static synchronized void unhookAll() {
        for (XC_MethodHook.Unhook u : unhooks) {
            try {
                u.unhook();
            } catch (Throwable ignored) {
            }
        }
        unhooks.clear();
        installed = false;
    }

    private static void hookNoArg(Class<?> clazz, String name, XC_MethodHook hook) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    unhooks.add(XposedBridge.hookMethod(m, hook));
                    log("Hooked " + clazz.getSimpleName() + "." + name);
                    return;
                }
            }
            log("Method not found: " + clazz.getSimpleName() + "." + name);
        } catch (Throwable t) {
            log("Failed to hook " + clazz.getSimpleName() + "." + name + ": " + t);
        }
    }

    private static void hookNoArgFirstInt(Class<?> clazz, String name, XC_MethodHook hook) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length == 1 && p[0] == int.class) {
                    unhooks.add(XposedBridge.hookMethod(m, hook));
                    log("Hooked " + clazz.getSimpleName() + "." + name);
                    return;
                }
            }
            log("Method not found: " + clazz.getSimpleName() + "." + name);
        } catch (Throwable t) {
            log("Failed to hook " + clazz.getSimpleName() + "." + name + ": " + t);
        }
    }

    private static void hookByFirstParam(Class<?> clazz, String name, Class<?> first, XC_MethodHook hook) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length >= 1 && p[0] == first) {
                    unhooks.add(XposedBridge.hookMethod(m, hook));
                    log("Hooked " + clazz.getSimpleName() + "." + name + "/" + p.length);
                }
            }
        } catch (Throwable t) {
            log("Failed to hook " + clazz.getSimpleName() + "." + name + ": " + t);
        }
    }

    private static void hookByFirstParamName(Class<?> clazz, String name, XC_MethodHook hook) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length >= 1 && p[0].getName().endsWith("ViewHolder")) {
                    unhooks.add(XposedBridge.hookMethod(m, hook));
                    log("Hooked " + clazz.getSimpleName() + "." + name + "/" + p.length);
                    return;
                }
            }
            log("Method not found: " + clazz.getSimpleName() + "." + name);
        } catch (Throwable t) {
            log("Failed to hook " + clazz.getSimpleName() + "." + name + ": " + t);
        }
    }

    private static void hookByName(Class<?> clazz, String name, XC_MethodHook hook) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    unhooks.add(XposedBridge.hookMethod(m, hook));
                    log("Hooked " + clazz.getSimpleName() + "." + name + "/" + m.getParameterTypes().length);
                }
            }
        } catch (Throwable t) {
            log("Failed to hook " + clazz.getSimpleName() + "." + name + ": " + t);
        }
    }

    private static void hookAll(Class<?> clazz, String name, XC_MethodHook hook) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    unhooks.add(XposedBridge.hookMethod(m, hook));
                    log("Hooked " + clazz.getSimpleName() + "." + name + "/" + m.getParameterTypes().length);
                }
            }
        } catch (Throwable t) {
            log("Failed to hook " + clazz.getSimpleName() + "." + name + ": " + t);
        }
    }

    // ---------------- hooks ----------------

    /** Core flip: MessageObject.isOutOwner() -> false while rendered (unless suspended). */
    private static final XC_MethodHook OUT_OWNER_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (outOwnerSuspensions > 0) {
                    return; // merge-flag computation: use the real value
                }
                if (shouldLie(param.thisObject)) {
                    param.setResult(Boolean.FALSE);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    /** Merge fix: real out-statuses while the adapter binds cells and computes glue flags. */
    private static final XC_MethodHook BIND_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            outOwnerSuspensions++;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (outOwnerSuspensions > 0) {
                outOwnerSuspensions--;
            }
        }
    };

    /**
     * GroupedMessages.calculate() — the album grid is computed before cells bind and reads
     * isOutOwner() to decide the side. Force the lie during it so sent albums get the same
     * (incoming) grid the cells will render with; afterwards remove the temporary marking.
     */
    private static final XC_MethodHook CALCULATE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object list = getObjectField(param.thisObject, "messages");
                if (!(list instanceof List)) {
                    return;
                }
                List<?> messages = (List<?>) list;
                List<Object> marked = null;
                synchronized (rendered) {
                    for (Object mo : messages) {
                        if (mo != null && rendered.add(mo)) {
                            if (marked == null) {
                                marked = new ArrayList<>();
                            }
                            marked.add(mo);
                        }
                    }
                }
                if (marked != null) {
                    calculatedGroups.set(marked);
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                List<?> marked = calculatedGroups.get();
                if (marked != null) {
                    synchronized (rendered) {
                        rendered.removeAll(marked);
                    }
                    calculatedGroups.set(null);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static final ThreadLocal<List<?>> calculatedGroups = new ThreadLocal<List<?>>();

    /**
     * setMessageObject(...) — BEFORE: register the message object as "rendered" and, if we
     * are inside a suspended onBindViewHolder, re-activate the lie so the cell lays itself
     * out with incoming geometry. AFTER: restore the suspension.
     */
    private static final XC_MethodHook SET_MESSAGE_OBJECT_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] args = param.args;
                if (args == null || args.length == 0 || args[0] == null
                        || !isMessageObjectArg(param)) {
                    return;
                }
                // Self-heal a leaked restore from a previous crashed call on this thread.
                int prev = pendingRestore.get();
                if (prev > 0) {
                    outOwnerSuspensions += prev;
                }
                int restore = 0;
                if (outOwnerSuspensions > 0) {
                    outOwnerSuspensions--;
                    restore = 1;
                }
                pendingRestore.set(restore);

                Object mo = args[0];
                synchronized (rendered) {
                    rendered.add(mo);
                }
                if (param.method != null) {
                    lastBinds.put(param.thisObject, new Object[]{param.method, args.clone()});
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                int restore = pendingRestore.get();
                if (restore > 0) {
                    outOwnerSuspensions += restore;
                    pendingRestore.set(0);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    /**
     * ChatMessageCell.getThemedColor(int) — remap key_chat_in* keys to their key_chat_out*
     * counterparts so flipped messages get the user's own bubble palette.
     */
    private static final XC_MethodHook GET_THEMED_COLOR_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!ownColors) {
                    return;
                }
                Object cell = param.thisObject;
                Object mo = getObjectField(cell, "currentMessageObject");
                if (!shouldLie(mo)) {
                    return;
                }
                buildColorRemap();
                Integer mapped;
                synchronized (inToOutColors) {
                    mapped = inToOutColors.get((Integer) param.args[0]);
                }
                if (mapped != null) {
                    param.args[0] = mapped;
                }
            } catch (Throwable ignored) {
            }
        }
    };

    /**
     * Scope marker: while one of the cell's background color-bake methods runs for a lying
     * message, MessageDrawable.setTop must override the bubble color.
     */
    private static final XC_MethodHook LIE_BAKE_SCOPE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object cell = param.thisObject;
                Object mo = getObjectField(cell, "currentMessageObject");
                lieBakeMarker.set(Boolean.valueOf(shouldLie(mo) && ownColors));
            } catch (Throwable ignored) {
                lieBakeMarker.set(Boolean.FALSE);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            lieBakeMarker.set(Boolean.FALSE);
        }
    };

    /**
     * MessageDrawable.getColor(int)/getCurrentColor(int) — inside the bake/draw scope of a
     * flipped message, remap key_chat_in* keys to their key_chat_out* counterparts. This
     * covers the flat-theme path where getBackgroundDrawable() resolves the nine-patch
     * color at draw time, plus the bubble shadow key. Out keys pass through untouched, so
     * the native isOut-branch (flipped in SET_TOP_HOOK) and this remap never conflict.
     */
    private static final XC_MethodHook COLOR_KEY_REMAP_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!ownColors || !Boolean.TRUE.equals(lieBakeMarker.get())) {
                    return;
                }
                // Force the STATIC linear-gradient bake for flipped bubbles: the shared
                // animated MotionBackground gets fought over by every cell during scroll
                // (setColors + parent invalidations -> freezes). A static LinearGradient
                // uses the same theme colors with zero per-frame rebuilds.
                int animatedKey = getGradientAnimatedKey();
                if (animatedKey > 0 && ((Integer) param.args[0]).intValue() == animatedKey) {
                    param.setResult(Integer.valueOf(0));
                    return;
                }
                buildColorRemap();
                Integer mapped;
                synchronized (inToOutColors) {
                    mapped = inToOutColors.get((Integer) param.args[0]);
                }
                if (mapped != null) {
                    param.args[0] = mapped;
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static volatile boolean gradientAnimatedKeyResolved = false;
    private static int gradientAnimatedKey = -1;

    private static int getGradientAnimatedKey() {
        if (!gradientAnimatedKeyResolved) {
            synchronized (inToOutColorsBuiltLock) {
                if (!gradientAnimatedKeyResolved) {
                    try {
                        Class<?> theme = Class.forName("org.telegram.ui.ActionBar.Theme", true,
                                LeftHookBridge.class.getClassLoader());
                        gradientAnimatedKey = staticIntField(theme, "key_chat_outBubbleGradientAnimated");
                    } catch (Throwable t) {
                        gradientAnimatedKey = -1;
                    }
                    gradientAnimatedKeyResolved = true;
                }
            }
        }
        return gradientAnimatedKey;
    }

    private static int staticIntField(Class<?> clazz, String name) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * MessageDrawable.setTop(...) — the place where the bubble color is baked. The real
     * out-bubble palette lives in the isOut branch (key_chat_outBubble + gradients +
     * animated gradient handling), so for flipped messages we transiently flip the
     * drawable's final isOut flag during the bake: the color is then resolved through the
     * exact same pipeline a genuine outgoing bubble uses. Restored afterwards, keeping
     * the shared drawable honest for everyone else.
     */
    private static final XC_MethodHook SET_TOP_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!Boolean.TRUE.equals(lieBakeMarker.get())) {
                    return;
                }
                Object drawable = param.thisObject;
                if (getBoolField(drawable, "isOut", false)) {
                    // Already an out drawable — nothing to flip.
                    flippedHere.set(Boolean.FALSE);
                    return;
                }
                Field f = findField(drawable, "isOut");
                if (f == null) {
                    flippedHere.set(Boolean.FALSE);
                    return;
                }
                try {
                    f.setBoolean(drawable, true);
                    flippedHere.set(Boolean.TRUE);
                } catch (Throwable t) {
                    flippedHere.set(Boolean.FALSE);
                }
            } catch (Throwable ignored) {
                flippedHere.set(Boolean.FALSE);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (Boolean.TRUE.equals(flippedHere.get())) {
                    Field f = findField(param.thisObject, "isOut");
                    if (f != null) {
                        f.setBoolean(param.thisObject, false);
                    }
                    flippedHere.set(Boolean.FALSE);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static final ThreadLocal<Boolean> flippedHere = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    /** isNeedAuthorName() -> false for flipped messages: no nickname/tag header on own bubbles. */
    private static final XC_MethodHook NEED_AUTHOR_NAME_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object cell = param.thisObject;
                Object mo = getObjectField(cell, "currentMessageObject");
                if (shouldLie(mo)) {
                    param.setResult(Boolean.FALSE);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    // ---------------- python control API ----------------

    public void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            synchronized (clientUserIds) {
                clientUserIds.clear();
            }
            synchronized (channelCache) {
                channelCache.clear();
            }
        }
        refreshChatViews();
        log("enabled=" + value);
    }

    public void setFlipSaved(boolean value) {
        flipSaved = value;
        refreshChatViews();
        log("flipSaved=" + value);
    }

    public void setOwnColors(boolean value) {
        ownColors = value;
        refreshChatViews();
        log("ownColors=" + value);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isFlipSaved() {
        return flipSaved;
    }

    public boolean isOwnColors() {
        return ownColors;
    }

    /**
     * Re-binds every attached chat cell with its last known bind arguments, so toggling
     * the plugin updates the open chat immediately without reopening it.
     */
    public void refreshChatViews() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                rebindAttachedCells();
            }
        });
    }

    private void rebindAttachedCells() {
        try {
            List<Object[]> rebinds = null;
            synchronized (lastBinds) {
                for (Map.Entry<Object, Object[]> e : lastBinds.entrySet()) {
                    Object cell = e.getKey();
                    Object[] bind = e.getValue();
                    if (cell == null || bind == null || bind[0] == null || bind[1] == null) {
                        continue;
                    }
                    try {
                        Method m = View_isAttachedToWindow();
                        if (m != null && !((Boolean) m.invoke(cell)).booleanValue()) {
                            continue;
                        }
                    } catch (Throwable t) {
                        // If the check is unavailable, rebind anyway.
                    }
                    if (rebinds == null) {
                        rebinds = new ArrayList<>();
                    }
                    rebinds.add(new Object[]{cell, bind[0], bind[1]});
                }
            }
            if (rebinds == null) {
                return;
            }
            for (Object[] r : rebinds) {
                try {
                    Method method = (Method) r[1];
                    Object[] args = (Object[]) r[2];
                    method.invoke(r[0], args);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log("rebindAttachedCells: " + t);
        }
    }

    private static Method attachedToWindowMethod;
    private static boolean attachedToWindowLookupFailed = false;

    private static Method View_isAttachedToWindow() {
        if (attachedToWindowMethod != null) {
            return attachedToWindowMethod;
        }
        if (attachedToWindowLookupFailed) {
            return null;
        }
        try {
            attachedToWindowMethod = android.view.View.class.getMethod("isAttachedToWindow");
            return attachedToWindowMethod;
        } catch (Throwable t) {
            attachedToWindowLookupFailed = true;
            return null;
        }
    }

    // ---------------- lie decision (hot path) ----------------

    /**
     * True when this message object must be presented as "incoming":
     * - plugin enabled
     * - message object is currently bound to a chat cell (UI scope only)
     * - it really is our own outgoing message (messageOwner.out)
     * - and (unless flipSaved) it is not inside Saved Messages
     */
    private static boolean shouldLie(Object messageObject) {
        if (!enabled || messageObject == null) {
            return false;
        }
        synchronized (rendered) {
            if (!rendered.contains(messageObject)) {
                return false;
            }
        }
        Object owner = getObjectField(messageObject, "messageOwner");
        if (owner == null) {
            return false;
        }
        if (!getBoolField(owner, "out", false)) {
            return false;
        }
        if (isBroadcastChannel(messageObject)) {
            // Channels (including own posts) keep the native look.
            return false;
        }
        if (!flipSaved && isSavedContext(messageObject)) {
            return false;
        }
        return true;
    }

    /** Cached per-dialog broadcast-channel check (channels are negative dialog ids). */
    private static final Map<Long, Boolean> channelCache = new HashMap<>();

    private static boolean isBroadcastChannel(Object messageObject) {
        try {
            long dialogId = invokeLong(messageObject, "getDialogId");
            if (dialogId >= 0L) {
                return false;
            }
            Boolean cached;
            synchronized (channelCache) {
                cached = channelCache.get(Long.valueOf(dialogId));
            }
            if (cached != null) {
                return cached.booleanValue();
            }
            boolean broadcast = false;
            int account = getIntField(messageObject, "currentAccount", 0);
            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController", true,
                    LeftHookBridge.class.getClassLoader());
            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, Integer.valueOf(account));
            Object chat = null;
            try {
                chat = mcClass.getMethod("getChat", Long.class).invoke(mc, Long.valueOf(-dialogId));
            } catch (Throwable t) {
                chat = mcClass.getMethod("getChat", long.class).invoke(mc, Long.valueOf(-dialogId));
            }
            if (chat != null) {
                Class<?> chatObject = Class.forName("org.telegram.messenger.ChatObject", true,
                        LeftHookBridge.class.getClassLoader());
                Object value = chatObject.getMethod("isChannelAndNotMegaGroup", chat.getClass())
                        .invoke(null, chat);
                broadcast = value instanceof Boolean && ((Boolean) value).booleanValue();
            }
            synchronized (channelCache) {
                channelCache.put(Long.valueOf(dialogId), Boolean.valueOf(broadcast));
            }
            return broadcast;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isSavedContext(Object messageObject) {
        try {
            if (getBoolField(messageObject, "isSaved", false)) {
                return true;
            }
            int account = getIntField(messageObject, "currentAccount", 0);
            long dialogId = invokeLong(messageObject, "getDialogId");
            return dialogId == clientUserId(account);
        } catch (Throwable t) {
            return false;
        }
    }

    private static long clientUserId(int account) {
        synchronized (clientUserIds) {
            Long cached = clientUserIds.get(Integer.valueOf(account));
            if (cached != null) {
                return cached.longValue();
            }
        }
        long uid = 0L;
        try {
            Class<?> uc = Class.forName("org.telegram.messenger.UserConfig", true,
                    LeftHookBridge.class.getClassLoader());
            Object instance = uc.getMethod("getInstance", int.class).invoke(null, Integer.valueOf(account));
            Object value = uc.getMethod("getClientUserId").invoke(instance);
            uid = ((Long) value).longValue();
        } catch (Throwable t) {
            log("clientUserId: " + t);
        }
        synchronized (clientUserIds) {
            clientUserIds.put(Integer.valueOf(account), Long.valueOf(uid));
        }
        return uid;
    }

    // ---------------- color remap ----------------

    /** Builds the key_chat_in* -> key_chat_out* remap from Theme's static int fields. */
    private static void buildColorRemap() {
        if (inToOutColorsBuilt) {
            return;
        }
        synchronized (inToOutColorsBuiltLock) {
            if (inToOutColorsBuilt) {
                return;
            }
            try {
                Class<?> theme = Class.forName("org.telegram.ui.ActionBar.Theme", true,
                        LeftHookBridge.class.getClassLoader());
                Map<String, Integer> ints = new HashMap<>();
                for (Field f : theme.getDeclaredFields()) {
                    try {
                        if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class
                                && f.getName().startsWith("key_chat_")) {
                            ints.put(f.getName(), Integer.valueOf(f.getInt(null)));
                        }
                    } catch (Throwable ignored) {
                    }
                }
                synchronized (inToOutColors) {
                    inToOutColors.clear();
                    for (Map.Entry<String, Integer> e : ints.entrySet()) {
                        String name = e.getKey();
                        if (!name.startsWith("key_chat_in")) {
                            continue;
                        }
                        // Shadows are subtle and their cached bitmaps are expensive to
                        // re-render on every in<->out alternation: keep them untouched.
                        if (name.endsWith("Shadow")) {
                            continue;
                        }
                        String outName = "key_chat_out" + name.substring("key_chat_in".length());
                        Integer outKey = ints.get(outName);
                        if (outKey != null) {
                            inToOutColors.put(e.getValue(), outKey);
                        }
                    }
                }
                inToOutColorsBuilt = true;
                log("Color remap built: " + inToOutColors.size() + " pairs");
            } catch (Throwable t) {
                log("buildColorRemap: " + t);
                inToOutColorsBuilt = true; // do not retry every call
            }
        }
    }

    private static final Object inToOutColorsBuiltLock = new Object();

    // ---------------- misc reflection helpers (cached, safe) ----------------

    private static boolean isMessageObjectArg(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.method == null) {
                return false;
            }
            Class<?>[] types = ((Method) param.method).getParameterTypes();
            return types != null && types.length >= 1
                    && types[0].getName().contains("MessageObject");
        } catch (Throwable t) {
            return false;
        }
    }

    private static Field findField(Object target, String name) {
        if (target == null) {
            return null;
        }
        String key = target.getClass().getName() + "#" + name;
        Field cached = fieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                fieldCache.put(key, f);
                return f;
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object getObjectField(Object target, String name) {
        try {
            Field f = findField(target, name);
            if (f != null) {
                return f.get(target);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean getBoolField(Object target, String name, boolean def) {
        try {
            Field f = findField(target, name);
            if (f != null) {
                return f.getBoolean(target);
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static int getIntField(Object target, String name, int def) {
        try {
            Field f = findField(target, name);
            if (f != null) {
                return f.getInt(target);
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static void setBoolField(Object target, String name, boolean value) {
        try {
            Field f = findField(target, name);
            if (f != null) {
                f.setBoolean(target, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static long invokeLong(Object target, String name) throws Exception {
        String key = target.getClass().getName() + "#" + name + "()";
        Method m = methodCache.get(key);
        if (m == null) {
            m = target.getClass().getMethod(name);
            m.setAccessible(true);
            methodCache.put(key, m);
        }
        Object result = m.invoke(target);
        return result instanceof Long ? ((Long) result).longValue() : 0L;
    }

    private static void log(String message) {
        Log.e(TAG, message);
    }
}
