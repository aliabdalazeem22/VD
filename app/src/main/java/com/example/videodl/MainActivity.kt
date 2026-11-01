package com.example.videodl

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.videodl.ui.DownloadViewModel
import com.example.videodl.ui.HomeScreen
import com.example.videodl.ui.theme.VideoDLTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VideoDLTheme {
                HomeScreen(viewModel)
            }
        }
        consumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    /**
     * Share-sheet delivery. ACTION_SEND arrives here (not in a receiver), and
     * singleTask + onNewIntent keeps the activity from stacking.
     */
    private fun consumeIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        viewModel.onSharedText(text)
    }
}
