package com.shriram.cinecode.data.agent

import com.shriram.cinecode.domain.model.ChatAttachment
import com.shriram.cinecode.domain.model.ChatMessage
import com.shriram.cinecode.domain.model.Conversation
import com.shriram.cinecode.domain.model.ToolCall
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * State of the chat on screen, shared between ChatViewModel and the agent backend that owns the active conversation.
 */
class ChatSessionStore {
    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val currentConversation = MutableStateFlow<Conversation?>(null)
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val conversationError = MutableStateFlow<String?>(null)
    val userEmail = MutableStateFlow<String?>(null)
    val attachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val terminatedToolDialog = MutableStateFlow<Pair<ToolCall, String>?>(null)
}
