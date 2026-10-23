package com.freease.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.youtube.YouTubeRepository
import com.freease.music.ui.FreeaseRoot
import com.freease.music.ui.navigation.AppActions

@UnstableApi
class MainActivity : ComponentActivity() {
    private val container get() = (application as FreeaseApp).container
    private var actions: AppActions? = null
    private var pendingIntent: Intent? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingIntent = intent
        setContent {
            FreeaseRoot(container) { a ->
                actions = a
                pendingIntent?.let { handleIntent(it) }
                pendingIntent = null
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) container.touchInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        container.player.connect()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) container.player.disconnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (actions != null) handleIntent(intent) else pendingIntent = intent
    }

    private fun handleIntent(intent: Intent) {
        val a = actions ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            a.playerExpanded = true
            return
        }
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        if (YouTubeRepository.isYouTubeUrl(text)) a.handleLink(text)
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
