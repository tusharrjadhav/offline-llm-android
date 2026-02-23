package com.android.gguf_llama_jin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.gguf_llama_jin.ui.theme.GGUF_llama_JINTheme
import com.android.gguf_llama_jin.ui.navigation.AppNav
import com.android.gguf_llama_jin.ui.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GGUF_llama_JINTheme {
                val vm: AppViewModel = viewModel()
                AppNav(vm)
            }
        }
    }
}
