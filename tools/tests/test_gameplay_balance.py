"""Execute the production combat/reward code without an Android device.

Android drawing and lifecycle calls are inert here; combat maths, lists, choices,
XP, damage and loot are the actual GameView implementation. Device rendering and
touch latency still require a real phone.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    "android/content/Context.java": "package android.content; public class Context {}",
    "android/app/Activity.java": "package android.app; public class Activity extends android.content.Context { public void finish() {} }",
    "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long uptimeMillis(){ return 0; } }",
    "android/view/Choreographer.java": """package android.view; public class Choreographer {
        public interface FrameCallback { void doFrame(long time); }
        public static Choreographer getInstance(){ return new Choreographer(); }
        public void postFrameCallback(FrameCallback callback){} }""",
    "android/view/View.java": """package android.view; public class View {
        private final android.content.Context context;
        public View(android.content.Context context){this.context=context;}
        public android.content.Context getContext(){return context;}
        public void setFocusable(boolean value){} public void setKeepScreenOn(boolean value){}
        public int getWidth(){return 1080;} public int getHeight(){return 2400;}
        protected void onSizeChanged(int w,int h,int oldw,int oldh){}
        protected void onDraw(android.graphics.Canvas canvas){}
        public boolean onTouchEvent(MotionEvent event){return true;}
        public boolean post(Runnable action){action.run();return true;}
        public void invalidate(){} }""",
    "android/view/MotionEvent.java": """package android.view; public class MotionEvent {
        public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,
            ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
        public int getActionMasked(){return 0;} public int getActionIndex(){return 0;}
        public int getPointerId(int index){return 0;} public int findPointerIndex(int id){return 0;}
        public float getX(int index){return 0;} public float getY(int index){return 0;} }""",
    "android/graphics/Canvas.java": """package android.graphics; public class Canvas {
        public int save(){return 0;} public void restore(){}
        public void translate(float x,float y){} public void scale(float x,float y){}
        public void rotate(float angle,float x,float y){}
        public void drawColor(int color){} public void drawArc(Object... args){}
        public void drawCircle(Object... args){} public void drawLine(Object... args){}
        public void drawOval(Object... args){} public void drawPath(Object... args){}
        public void drawRect(Object... args){} public void drawRoundRect(Object... args){}
        public void drawText(Object... args){} }""",
    "android/graphics/Paint.java": """package android.graphics; public class Paint {
        public static final int ANTI_ALIAS_FLAG=1; private float size=12;
        public enum Style {STROKE,FILL} public enum Align {LEFT,CENTER,RIGHT}
        public enum Cap {ROUND,BUTT}
        public Paint(int flags){} public void setStyle(Style style){}
        public void setColor(int color){} public void setStrokeWidth(float width){}
        public void setStrokeCap(Cap cap){} public void setTypeface(Typeface font){}
        public void setFakeBoldText(boolean value){} public void setShader(Shader shader){}
        public void setTextAlign(Align align){} public void setTextSize(float size){this.size=size;}
        public float measureText(String text){return text.length()*size*0.5f;} }""",
    "android/graphics/Typeface.java": """package android.graphics; public class Typeface {
        public static final int NORMAL=0; public static Typeface create(String name,int style){return new Typeface();} }""",
    "android/graphics/Color.java": """package android.graphics; public class Color {
        public static final int WHITE=-1,YELLOW=0xffffff00,MAGENTA=0xffff00ff,BLACK=0xff000000;
        public static int argb(int a,int r,int g,int b){return a<<24|r<<16|g<<8|b;}
        public static int rgb(int r,int g,int b){return argb(255,r,g,b);}
        public static int red(int c){return (c>>16)&255;} public static int green(int c){return (c>>8)&255;}
        public static int blue(int c){return c&255;} }""",
    "android/graphics/RectF.java": """package android.graphics; public class RectF {
        public float left,top,right,bottom;
        public RectF(){} public RectF(float l,float t,float r,float b){set(l,t,r,b);}
        public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
        public void setEmpty(){set(0,0,0,0);} public float centerX(){return (left+right)/2;}
        public float centerY(){return (top+bottom)/2;}
        public boolean contains(float x,float y){return x>=left&&x<right&&y>=top&&y<bottom;} }""",
    "android/graphics/Path.java": """package android.graphics; public class Path {
        public void moveTo(float x,float y){} public void lineTo(float x,float y){} public void close(){} }""",
    "android/graphics/Shader.java": "package android.graphics; public class Shader {public enum TileMode {CLAMP}}",
    "android/graphics/RadialGradient.java": """package android.graphics; public class RadialGradient extends Shader {
        public RadialGradient(Object... args){} }""",
    "com/megamobile/game/RemoteConfig.java": """package com.megamobile.game; public class RemoteConfig {
        public float playerBaseSpeed=290,spawnInterval=0.64f,difficultySeconds=80,bossInterval=52,xpMagnet=235;
        public int enemyCap=160; public String contentVersion="test";
        public interface Callback {void onLoaded(RemoteConfig config);}
        public static void loadAsync(Callback callback){} public void applyFrom(RemoteConfig config){} }""",
}


class GameplayBalanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java = shutil.which("java")
        if not java:
            raise unittest.SkipTest("Java 17 is required for the production gameplay checks")
        javac = shutil.which("javac")
        compiler = [javac] if javac else [java, "-m", "jdk.compiler/com.sun.tools.javac.Main"]
        cls.temp = tempfile.TemporaryDirectory(prefix="megamobile-gameplay-")
        cls.addClassCleanup(cls.temp.cleanup)
        directory = Path(cls.temp.name)
        sources = []
        for name, content in STUBS.items():
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(str(path))
        sources.extend(str(ROOT / "app/src/main/java/com/megamobile/game" / name)
                       for name in ("GameBalance.java", "GameView.java"))
        sources.append(str(ROOT / "tools/tests/gameplay/GameViewChecks.java"))
        result = subprocess.run(compiler + ["-d", str(directory), *sources], capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        cls.command = [java, "-cp", str(directory), "com.megamobile.game.GameViewChecks"]

    def check_gameplay(self, scenario):
        result = subprocess.run(self.command + [scenario], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_wave_rhythm_and_long_run_population(self): self.check_gameplay("waves")
    def test_guaranteed_loot_after_unlucky_streaks(self): self.check_gameplay("loot")
    def test_no_duplicate_or_saturated_choices(self): self.check_gameplay("choices")
    def test_auto_selection_moves_and_prioritizes_survival(self): self.check_gameplay("auto")
    def test_queued_levels_finish_without_new_xp(self): self.check_gameplay("xp")
    def test_boss_attack_can_be_dodged(self): self.check_gameplay("boss")
    def test_explosion_preserves_rewards(self): self.check_gameplay("explosion")
    def test_lifesteal_is_bounded_per_second(self): self.check_gameplay("lifesteal")
    def test_temporary_frenzy_preserves_permanent_upgrades(self): self.check_gameplay("frenzy")
    def test_ten_minutes_of_combat_keep_progressing(self): self.check_gameplay("run")
