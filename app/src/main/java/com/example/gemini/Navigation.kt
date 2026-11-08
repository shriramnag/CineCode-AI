package com.shriram.cinecode

import androidx.compose.runtime.Composable
import com.shriram.cinecode.ui.chat.ChatScreen
import com.shriram.cinecode.ui.chat.ChatViewModel

@Composable
fun MainNavigation(viewModel: ChatViewModel? = null) {
    if (viewModel != null) {
        ChatScreen(viewModel = viewModel)
    } else {
        ChatScreen()
    }
}
