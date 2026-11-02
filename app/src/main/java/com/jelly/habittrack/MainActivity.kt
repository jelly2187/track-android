package com.jelly.habittrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jelly.habittrack.ui.HabitRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { HabitRoot(intent.getBooleanExtra("pending", false), intent.getStringExtra("habit")) }
    }
    override fun onResume() { super.onResume(); habitApp.scope.launch { habitApp.reconcile() } }
}
