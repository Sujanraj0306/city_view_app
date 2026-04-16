package com.civicshield.app.ui.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.civicshield.app.databinding.ItemChatAgentBinding
import com.civicshield.app.databinding.ItemChatTypingBinding
import com.civicshield.app.databinding.ItemChatUserBinding

sealed class ChatMessage(val id: Long, open val text: String) {
    class User(id: Long, text: String) : ChatMessage(id, text)
    class Agent(id: Long, text: String) : ChatMessage(id, text)
    /** Placeholder shown while we wait for /agent/chat to return. */
    class Typing(id: Long) : ChatMessage(id, "")
}

class AgentChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ChatMessage.User -> TYPE_USER
        is ChatMessage.Agent -> TYPE_AGENT
        is ChatMessage.Typing -> TYPE_TYPING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(ItemChatUserBinding.inflate(inflater, parent, false))
            TYPE_AGENT -> AgentVH(ItemChatAgentBinding.inflate(inflater, parent, false))
            else -> TypingVH(ItemChatTypingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatMessage.User -> (holder as UserVH).bind(item)
            is ChatMessage.Agent -> (holder as AgentVH).bind(item)
            is ChatMessage.Typing -> (holder as TypingVH).bind()
        }
    }

    class UserVH(private val b: ItemChatUserBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: ChatMessage.User) { b.tvBubble.text = m.text }
    }

    class AgentVH(private val b: ItemChatAgentBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: ChatMessage.Agent) { b.tvBubble.text = m.text }
    }

    class TypingVH(private val b: ItemChatTypingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind() {
            b.lottieTyping.setFailureListener { _ -> /* degrade silently */ }
            b.lottieTyping.setAnimationFromUrl(TYPING_DOTS_URL)
            b.lottieTyping.playAnimation()
        }
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AGENT = 1
        private const val TYPE_TYPING = 2

        private const val TYPING_DOTS_URL =
            "https://assets4.lottiefiles.com/packages/lf20_usmfx6bp.json"

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) =
                a.id == b.id && a.text == b.text && a.javaClass == b.javaClass
        }
    }
}
