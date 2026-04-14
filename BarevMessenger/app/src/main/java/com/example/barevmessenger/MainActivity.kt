package com.example.barevmessenger

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var buddyList:   ListView
    private lateinit var localIdInput: EditText
    private lateinit var addButton:   Button

    private lateinit var contacts: MutableList<Contact>
    private lateinit var adapter:  ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buddyList    = findViewById(R.id.buddyList)
        localIdInput = findViewById(R.id.localIdInput)
        addButton    = findViewById(R.id.addButton)

        localIdInput.setText("")

        contacts = ContactManager.load(this)
        adapter  = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList())
        buddyList.adapter = adapter

        addButton.setOnClickListener { showAddBuddyDialog() }

        buddyList.setOnItemClickListener { _, _, position, _ ->
            val contact = contacts[position]
            val intent  = Intent(this, ChatActivity::class.java)
            intent.putExtra("localId", localIdInput.text.toString().trim())
            intent.putExtra("peerNick", contact.nick)
            intent.putExtra("peerIpv6", contact.ipv6)
            intent.putExtra("peerPort", contact.port.toString())
            startActivity(intent)
        }

        buddyList.setOnItemLongClickListener { _, _, position, _ ->
            val contact = contacts[position]
            AlertDialog.Builder(this)
                .setTitle("Remove buddy")
                .setMessage("Remove ${contact.nick}?")
                .setPositiveButton("Remove") { _, _ ->
                    contacts = ContactManager.remove(this, contact.nick)
                    refreshList()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        contacts = ContactManager.load(this)
        refreshList()
    }

    private fun showAddBuddyDialog() {
        val view  = layoutInflater.inflate(R.layout.dialog_add_buddy, null)
        val nick  = view.findViewById<EditText>(R.id.inputNick)
        val ipv6  = view.findViewById<EditText>(R.id.inputIpv6)
        val port  = view.findViewById<EditText>(R.id.inputPort)
        port.setText("1337")

        AlertDialog.Builder(this)
            .setTitle("Add Buddy")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val n = nick.text.toString().trim()
                val i = ipv6.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull() ?: 1337
                if (n.isNotEmpty() && i.isNotEmpty()) {
                    contacts = ContactManager.add(this, n, i, p)
                    refreshList()
                } else {
                    Toast.makeText(this, "Nick and IPv6 are required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun displayList(): List<String> =
        contacts.map { "${it.nick}  —  ${it.ipv6}" }

    private fun refreshList() {
        adapter.clear()
        adapter.addAll(displayList())
        adapter.notifyDataSetChanged()
    }
}