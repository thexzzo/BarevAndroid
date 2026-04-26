package com.example.barevmessenger

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), BarevService.ServiceListener {

    private lateinit var buddyListView:  ListView
    private lateinit var accountButton:  Button
    private lateinit var addButton:      Button
    private lateinit var chatView:       TextView
    private lateinit var messageInput:   EditText
    private lateinit var sendButton:     Button
    private lateinit var scrollView:     ScrollView
    private lateinit var statusSpinner:  Spinner
    private lateinit var peerStatusDot:  ImageView
    private lateinit var chatTitleBar:   TextView
    private lateinit var connectButton:  Button
    private lateinit var noChatSelected: TextView
    private lateinit var typingIndicator: TextView
    private lateinit var buddyPanel:     LinearLayout
    private lateinit var togglePanelButton: ImageButton
    private var isPanelVisible = true

    private var service: BarevService? = null
    private var isBound = false
    private var selectedNick: String? = null
    private var contacts: MutableList<Contact> = mutableListOf()
    private lateinit var buddyAdapter: BuddyAdapter
    private var account: Account = Account("", "")

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastSpinnerPosition = 0

    private val presenceSendRunnable = Runnable {
        val status = when (statusSpinner.selectedItemPosition) {
            1    -> PresenceStatus.AWAY
            2    -> PresenceStatus.DND
            else -> PresenceStatus.AVAILABLE
        }
        service?.sendPresenceToAll(status)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as BarevService.BarevBinder).getService()
            isBound = true
            service?.listener = this@MainActivity
            service?.localId  = account.jid
            contacts.forEach { service?.addBuddy(it) }
            service?.startListening(account.port)
            refreshBuddyList()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buddyListView  = findViewById(R.id.buddyList)
        accountButton  = findViewById(R.id.accountButton)
        addButton      = findViewById(R.id.addButton)
        chatView       = findViewById(R.id.chatView)
        messageInput   = findViewById(R.id.messageInput)
        sendButton     = findViewById(R.id.sendButton)
        scrollView     = findViewById(R.id.scrollView)
        statusSpinner  = findViewById(R.id.statusSpinner)
        peerStatusDot  = findViewById(R.id.peerStatusDot)
        chatTitleBar   = findViewById(R.id.chatTitleBar)
        connectButton  = findViewById(R.id.connectButton)
        noChatSelected = findViewById(R.id.noChatSelected)
        typingIndicator = findViewById(R.id.typingIndicator)
        buddyPanel      = findViewById(R.id.buddyPanel)
        togglePanelButton = findViewById(R.id.togglePanelButton)

        account  = AccountManager.load(this)
        contacts = ContactManager.load(this)

        updateAccountButton()
        setupBuddyAdapter()
        setupStatusSpinner()

        accountButton.setOnClickListener { showAccountDialog() }
        addButton.setOnClickListener     { showAddBuddyDialog() }
        sendButton.setOnClickListener    { sendMessage() }
        connectButton.setOnClickListener { toggleConnection() }
        togglePanelButton.setOnClickListener { toggleBuddyPanel() }

        showNoChatSelected()
        bindService(
            Intent(this, BarevService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        if (isBound) {
            service?.listener = null
            unbindService(serviceConnection)
        }
    }

    private fun updateAccountButton() {
        accountButton.text = if (account.nick.isNotEmpty()) account.nick else "Account"
    }

    private fun showAccountDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_account, null)
        val nick = view.findViewById<EditText>(R.id.inputNick)
        val ipv6 = view.findViewById<EditText>(R.id.inputIpv6)
        val port = view.findViewById<EditText>(R.id.inputPort)

        nick.setText(account.nick)
        ipv6.setText(account.ipv6)
        port.setText(account.port.toString())

        AlertDialog.Builder(this)
            .setTitle("My Account")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val n = nick.text.toString().trim()
                val i = ipv6.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull() ?: 1337
                if (n.isNotEmpty() && i.isNotEmpty()) {
                    AccountManager.save(this, n, i, p)
                    account = Account(n, i, p)
                    updateAccountButton()
                    service?.localId = account.jid
                    service?.stopListening()
                    service?.startListening(p)
                } else {
                    Toast.makeText(this, "Nick and IPv6 are required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupBuddyAdapter() {
        buddyAdapter = BuddyAdapter()
        buddyListView.adapter = buddyAdapter
        buddyListView.setOnItemClickListener { _, _, position, _ ->
            selectBuddy(contacts[position].nick)
        }
        buddyListView.setOnItemLongClickListener { _, _, position, _ ->
            val contact = contacts[position]
            AlertDialog.Builder(this)
                .setTitle("Remove buddy")
                .setMessage("Remove ${contact.nick}?")
                .setPositiveButton("Remove") { _, _ ->
                    service?.removeBuddy(contact.nick)
                    contacts = ContactManager.remove(this, contact.nick)
                    if (selectedNick == contact.nick) showNoChatSelected()
                    refreshBuddyList()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        refreshBuddyList()
    }

    private fun setupStatusSpinner() {
        val items = listOf(
            Pair(R.drawable.status_available, "Available"),
            Pair(R.drawable.status_away,      "Away"),
            Pair(R.drawable.status_dnd,       "Do Not Disturb")
        )
        val adapter = object : ArrayAdapter<Pair<Int, String>>(
            this, R.layout.spinner_status_item, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                makeView(position, convertView, parent)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                makeView(position, convertView, parent)
            private fun makeView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.spinner_status_item, parent, false)
                val item = items[position]
                view.findViewById<ImageView>(R.id.statusDot).setImageResource(item.first)
                view.findViewById<TextView>(R.id.statusLabel).text = item.second
                return view
            }
        }
        statusSpinner.adapter = adapter
        statusSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != lastSpinnerPosition) {
                    lastSpinnerPosition = position
                    uiHandler.removeCallbacks(presenceSendRunnable)
                    uiHandler.postDelayed(presenceSendRunnable, 3000)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun selectBuddy(nick: String) {
        selectedNick = nick
        uiHandler.removeCallbacks(typingTimeoutRunnable)
        val conn = service?.connections?.get(nick)

        chatTitleBar.text = nick
        noChatSelected.visibility = View.GONE
        findViewById<View>(R.id.chatHeader).visibility = View.VISIBLE
        chatView.visibility      = View.VISIBLE
        scrollView.visibility    = View.VISIBLE
        messageInput.visibility  = View.VISIBLE
        sendButton.visibility    = View.VISIBLE
        connectButton.visibility = View.VISIBLE
        statusSpinner.visibility = View.VISIBLE
        peerStatusDot.visibility = View.VISIBLE

        updatePeerStatusDot(conn?.status ?: PresenceStatus.OFFLINE)
        updateConnectButton(conn?.isConnected == true)
        refreshChatView(nick)
        buddyAdapter.notifyDataSetChanged()
    }

    private fun showNoChatSelected() {
        selectedNick = null
        noChatSelected.visibility  = View.VISIBLE
        chatView.visibility        = View.GONE
        messageInput.visibility    = View.GONE
        sendButton.visibility      = View.GONE
        connectButton.visibility   = View.GONE
        statusSpinner.visibility   = View.GONE
        peerStatusDot.visibility   = View.GONE
        typingIndicator.visibility = View.GONE
        findViewById<View>(R.id.chatHeader).visibility = View.GONE
    }

    private fun toggleBuddyPanel() {
        isPanelVisible = !isPanelVisible
        buddyPanel.visibility = if (isPanelVisible) View.VISIBLE else View.GONE
        togglePanelButton.setImageResource(
            if (isPanelVisible) R.drawable.ic_arrow_left else R.drawable.ic_arrow_right
        )
    }

    private fun toggleConnection() {
        val nick = selectedNick ?: return
        service?.connectToBuddy(nick)
    }

    private fun sendMessage() {
        val nick = selectedNick ?: return
        val msg  = messageInput.text.toString().trim()
        if (msg.isEmpty()) return
        service?.sendMessage(nick, msg)
        messageInput.setText("")
    }

    private val senderColors = listOf(
        0xFF1565C0.toInt(),
        0xFF6A1B9A.toInt(),
        0xFF00695C.toInt(),
        0xFF558B2F.toInt(),
        0xFFE65100.toInt(),
        0xFF283593.toInt()
    )
    private val nickColorMap = mutableMapOf<String, Int>()
    private var colorIndex = 0

    private fun colorForNick(nick: String): Int {
        return nickColorMap.getOrPut(nick) {
            senderColors[colorIndex++ % senderColors.size]
        }
    }

    private fun refreshChatView(nick: String) {
        val messages = service?.connections?.get(nick)?.messages ?: return
        val sb = android.text.SpannableStringBuilder()

        for (m in messages) {
            if (m.isSystem) continue

            val senderColor = colorForNick(m.sender)
            val lineStart = sb.length

            val senderSpan = android.text.SpannableString(m.sender)
            senderSpan.setSpan(
                android.text.style.ForegroundColorSpan(senderColor),
                0, senderSpan.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            senderSpan.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, senderSpan.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append(senderSpan)

            val spaces = "    "
            sb.append(spaces)

            val timeSpan = android.text.SpannableString(m.timestamp)
            timeSpan.setSpan(
                android.text.style.ForegroundColorSpan(0xFF888888.toInt()),
                0, timeSpan.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            timeSpan.setSpan(
                android.text.style.RelativeSizeSpan(0.85f),
                0, timeSpan.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append(timeSpan)

            sb.append("\n")

            val bodySpan = android.text.SpannableString(m.body)
            bodySpan.setSpan(
                android.text.style.ForegroundColorSpan(0xFF000000.toInt()),
                0, bodySpan.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append(bodySpan)
            sb.append("\n\n")
        }

        runOnUiThread {
            chatView.text = sb
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun refreshBuddyList() {
        runOnUiThread { buddyAdapter.notifyDataSetChanged() }
    }

    private fun updatePeerStatusDot(status: PresenceStatus) {
        runOnUiThread { peerStatusDot.setImageResource(statusDrawable(status)) }
    }

    private fun updateConnectButton(connected: Boolean) {
        runOnUiThread {
            connectButton.visibility = if (connected) View.GONE else View.VISIBLE
        }
    }

    private fun showAddBuddyDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_buddy, null)
        val nick = view.findViewById<EditText>(R.id.inputNick)
        val ipv6 = view.findViewById<EditText>(R.id.inputIpv6)
        val port = view.findViewById<EditText>(R.id.inputPort)
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
                    service?.addBuddy(Contact(n, i, p))
                    refreshBuddyList()
                } else {
                    Toast.makeText(this, "Nick and IPv6 are required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun statusDrawable(status: PresenceStatus) = when (status) {
        PresenceStatus.AVAILABLE -> R.drawable.status_available
        PresenceStatus.AWAY      -> R.drawable.status_away
        PresenceStatus.DND       -> R.drawable.status_dnd
        PresenceStatus.OFFLINE   -> R.drawable.status_offline
    }

    private val typingTimeoutRunnable = Runnable {
        val nick = selectedNick ?: return@Runnable
        service?.connections?.get(nick)?.isTyping = false
        runOnUiThread { typingIndicator.visibility = View.GONE }
    }

    override fun onTypingChanged(nick: String) {
        if (nick != selectedNick) return
        val isTyping = service?.connections?.get(nick)?.isTyping ?: false
        runOnUiThread {
            if (isTyping) {
                typingIndicator.visibility = View.VISIBLE
                uiHandler.removeCallbacks(typingTimeoutRunnable)
                uiHandler.postDelayed(typingTimeoutRunnable, 1000)
            } else {
                uiHandler.removeCallbacks(typingTimeoutRunnable)
                typingIndicator.visibility = View.GONE
            }
        }
    }

    override fun onMessageReceived(nick: String) {
        if (nick == selectedNick) refreshChatView(nick)
        else runOnUiThread { buddyAdapter.notifyDataSetChanged() }
    }

    override fun onStatusChanged(nick: String) {
        if (nick == selectedNick) {
            val status = service?.connections?.get(nick)?.status ?: PresenceStatus.OFFLINE
            updatePeerStatusDot(status)
        }
        refreshBuddyList()
    }

    override fun onConnectionStateChanged(nick: String) {
        val conn = service?.connections?.get(nick)
        if (nick == selectedNick) updateConnectButton(conn?.isConnected == true)
        refreshBuddyList()
    }

    inner class BuddyAdapter : BaseAdapter() {
        override fun getCount() = contacts.size
        override fun getItem(position: Int) = contacts[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view    = convertView ?: layoutInflater.inflate(R.layout.buddy_list_item, parent, false)
            val contact = contacts[position]
            val conn    = service?.connections?.get(contact.nick)
            view.findViewById<TextView>(R.id.buddyNick).text = contact.nick
            view.findViewById<ImageView>(R.id.buddyStatusDot)
                .setImageResource(statusDrawable(conn?.status ?: PresenceStatus.OFFLINE))
            view.setBackgroundColor(
                if (contact.nick == selectedNick) 0x220000FF else 0x00000000
            )
            return view
        }
    }
}