import hashlib
import os
import shutil
import traceback

from base_plugin import BasePlugin
from android_utils import log
from elyx import strings

try:
    from elyx import mvel_execute
except Exception:
    mvel_execute = None

try:
    from org.mvel2 import MVEL
except Exception:
    MVEL = None

from dalvik.system import DexClassLoader
from java.io import File
from java.util import HashMap
from java.lang import Object
from java import jclass

ENABLED = True
OWN_COLORS = True
FLIP_SAVED = True


def execute_java_script(script: str, variables: dict):
    """Runs a small Java snippet in the JVM (same approach as BlurBGMessage)."""
    if mvel_execute:
        try:
            return mvel_execute(script, variables)
        except Exception as e:
            log(f"[LeftMessages] elyx.mvel_execute notice: {e}, falling back to MVEL.eval")

    if MVEL:
        jmap = HashMap()
        for k, v in variables.items():
            jmap.put(k, v)
        return MVEL.eval(script, jmap)

    raise RuntimeError("Neither elyx.mvel_execute nor org.mvel2.MVEL is available")


class LeftMessagesPlugin(BasePlugin):
    """
    Left Messages: draws your own outgoing messages on the LEFT side of the chat,
    exactly like messages from the other person (Telegram Desktop style), in your
    own bubble color, with avatars wherever the layout puts them.

    The smart thread: MessageObject.isOutOwner() — the single flag that drives the
    whole outgoing-vs-incoming rendering pipeline. Native Java hooks from the bundled
    DEX bridge flip it only for message objects bound to visible ChatMessageCells:

    - media and albums lay out and open natively (the album grid is built with the
      same flip via the GroupedMessages.calculate() hook)
    - bubbles glue only to same-sender neighbors (merge flags computed on real
      out-statuses while ChatActivityAdapter.onBindViewHolder runs)
    - upload progress keeps working (MessageObject.isOut() is not touched)
    - notifications and call ringing are not affected
    """

    def __init__(self):
        super().__init__()
        self.ctx = None
        self.java_holder = HashMap()

    # ---------------- lifecycle ----------------

    def on_plugin_load(self):
        try:
            from org.telegram.messenger import ApplicationLoader
            self.ctx = ApplicationLoader.applicationContext
            if not self.ctx:
                log("[LeftMessages] ApplicationContext is null, aborting")
                return

            self._do_unload()

            bundled_dex = self._resolve_dex()
            log(f"[LeftMessages] Found bundled DEX at: {bundled_dex}")
            self.logger.info(f"[LeftMessages] Found bundled DEX at: {bundled_dex}")

            dest_path = self._deploy_dex(bundled_dex)

            opt_dir = self.ctx.getDir("dex_opt", 0).getAbsolutePath()
            loader = DexClassLoader(dest_path, opt_dir, None, self.ctx.getClassLoader())
            log("[LeftMessages] DexClassLoader initialized")
            self.logger.info("[LeftMessages] DexClassLoader initialized")

            bridge = Object()
            vars = {
                "loader": loader,
                "ctx": self.ctx,
                "bridge": bridge,
                "holder": self.java_holder,
                "object_cls": jclass("java.lang.Object"),
            }

            load_script = """
Class cls = loader.loadClass("com.pachka.leftmessages.LeftHookBridge");
Object inst = cls.newInstance();
cls.getMethod("onLoad", object_cls, object_cls).invoke(inst, ctx, bridge);
holder.put("instance", inst);
return "SUCCESS";
"""
            res = execute_java_script(load_script, vars)
            log(f"[LeftMessages] Bridge initialized via JVM: {res}")
            self.logger.info(f"[LeftMessages] Bridge initialized via JVM: {res}")

            self._apply_settings()
            self.logger.info(strings("loaded"))

        except Exception as e:
            err = traceback.format_exc()
            log(f"[LeftMessages] Critical error in on_plugin_load:\n{err}")
            self.logger.error(err)
            raise e

    def on_plugin_unload(self):
        self._do_unload()
        self.logger.info(strings("unloaded"))

    # ---------------- bridge state ----------------

    def _apply_settings(self):
        try:
            enabled = bool(self.get_setting("enabled", ENABLED))
            own_colors = bool(self.get_setting("own_colors", OWN_COLORS))
            flip_saved = bool(self.get_setting("flip_saved", FLIP_SAVED))
            self._call_bridge("setEnabled", enabled)
            self._call_bridge("setOwnColors", own_colors)
            self._call_bridge("setFlipSaved", flip_saved)
            log(f"[LeftMessages] applied: enabled={enabled}, ownColors={own_colors}, flipSaved={flip_saved}")
            self.logger.info(f"[LeftMessages] applied: enabled={enabled}, ownColors={own_colors}, flipSaved={flip_saved}")
        except Exception as e:
            err = traceback.format_exc()
            log(f"[LeftMessages] Error applying settings:\n{err}")
            self.logger.error(err)

    def _call_bridge(self, method: str, value: bool):
        if not (self.java_holder and self.java_holder.containsKey("instance")):
            return
        try:
            from java.lang import Boolean
            script = f"""
Object inst = holder.get("instance");
if (inst != null) {{
    inst.getClass().getMethod("{method}", bool_cls).invoke(inst, {str(bool(value)).lower()});
}}
return "SUCCESS";
"""
            execute_java_script(script, {"holder": self.java_holder, "bool_cls": Boolean.TYPE})
        except Exception as e:
            log(f"[LeftMessages] {method}({value}) failed: {e}")

    # ---------------- dex deployment (BlurBGMessage pattern) ----------------

    def _resolve_dex(self):
        bundled_dex = None
        try:
            from elyx import assets
            asset = assets.get("dex/classes.dex")
            if asset and hasattr(asset, "path"):
                bundled_dex = str(asset.path)
        except Exception as e:
            log(f"[LeftMessages] assets.get notice: {e}")

        if not bundled_dex or not os.path.exists(bundled_dex):
            cands = [
                os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "res", "dex", "classes.dex")),
                os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "res", "classes.dex")),
                os.path.abspath(os.path.join(os.path.dirname(__file__), "classes.dex")),
                os.path.abspath(os.path.join(os.path.dirname(__file__), "res", "dex", "classes.dex")),
            ]
            for c in cands:
                if os.path.exists(c):
                    bundled_dex = c
                    break

        if not bundled_dex or not os.path.exists(bundled_dex):
            raise FileNotFoundError(f"classes.dex not found near {__file__}")
        return bundled_dex

    def _deploy_dex(self, bundled_dex):
        dest_dir = File(self.ctx.getFilesDir(), "leftmessages_plugin")
        if not dest_dir.exists():
            dest_dir.mkdirs()

        dest_file = File(dest_dir, "classes.dex")
        dest_path = dest_file.getAbsolutePath()

        def file_md5(p):
            try:
                with open(p, "rb") as f:
                    return hashlib.md5(f.read()).hexdigest()
            except Exception:
                return ""

        need_copy = not dest_file.exists() or (file_md5(bundled_dex) != file_md5(dest_path))
        if need_copy:
            if dest_file.exists():
                try:
                    dest_file.setWritable(True, True)
                except Exception:
                    pass
                try:
                    dest_file.delete()
                except Exception:
                    pass
            shutil.copyfile(bundled_dex, dest_path)
            log(f"[LeftMessages] Deployed fresh DEX to {dest_path}")
            self.logger.info(f"[LeftMessages] Deployed fresh DEX to {dest_path}")

        try:
            dest_file.setWritable(False, False)
            dest_file.setReadOnly()
        except Exception as e:
            log(f"[LeftMessages] setReadOnly notice: {e}")

        return dest_path

    def _do_unload(self):
        try:
            if self.java_holder and self.java_holder.containsKey("instance"):
                unload_script = """
Object inst = holder.get("instance");
if (inst != null) {
    inst.onUnload();
    holder.clear();
}
return "SUCCESS";
"""
                execute_java_script(unload_script, {"holder": self.java_holder})
                log("[LeftMessages] Bridge unloaded.")
                self.logger.info("[LeftMessages] Bridge unloaded.")
        except Exception as e:
            log(f"[LeftMessages] Error in unload: {e}")
