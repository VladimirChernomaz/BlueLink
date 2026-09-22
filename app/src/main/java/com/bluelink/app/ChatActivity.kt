package com.bluelink.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluelink.app.databinding.ActivityChatBinding

class ChatActivity : AppCompatActivity(), BluetoothChatService.Listener {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        title = BluetoothChatService.connectedDeviceName ?: getString(R.string.app_name)

        adapter = MessageAdapter()
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this)
        binding.recyclerMessages.adapter = adapter

        binding.buttonSend.setOnClickListener { sendCurrentText() }
        binding.buttonDisconnect.setOnClickListener {
            BluetoothChatService.disconnect()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        BluetoothChatService.listener = this
        if (!BluetoothChatService.isConnected) {
            finish()
        }
    }

    private fun sendCurrentText() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        BluetoothChatService.sendMessage(text)
        adapter.add(ChatMessage(text, isMine = true))
        binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
        binding.editMessage.text?.clear()
    }

    override fun onMessageReceived(text: String) {
        adapter.add(ChatMessage(text, isMine = false))
        binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
    }

    override fun onDisconnected() {
        android.widget.Toast.makeText(this, getString(R.string.disconnected), android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
}
