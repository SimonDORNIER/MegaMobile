package com.megamobile.game;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class MainActivity extends Activity {
    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        immersive();
        gameView = new GameViewV7Final(this);
        setContentView(gameView);
        UpdateManager.checkAndUpdate(this);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (gameView != null) gameView.resumeGame();

        // Returning from the Android "install unknown apps" screen can race the permission state.
        // Retry shortly after resume, then check the live channel.
        getWindow().getDecorView().postDelayed(() -> {
            UpdateManager.tryInstallPending(this);
            UpdateManager.checkAndUpdate(this);
        }, 650L);
    }

    @Override
    protected void onPause() {
        if (gameView != null) gameView.pauseGame();
        super.onPause();
    }
}
