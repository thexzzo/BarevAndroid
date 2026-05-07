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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), BarevService.ServiceListener {

    private lateinit var buddyListView:     ListView
    private lateinit var accountButton:     Button
    private lateinit var addButton:         Button
    private lateinit var connectButton:     Button
    private lateinit var chatView:          TextView
    private lateinit var messageInput:      EditText
    private lateinit var sendButton:        Button
    private lateinit var scrollView:        ScrollView
    private lateinit var statusSpinner:     Spinner
    private lateinit var peerStatusDot:     ImageView
    private lateinit var chatTitleBar:      TextView
    private lateinit var typingIndicator:   TextView
    private lateinit var noChatSelected:    TextView
    private lateinit var buddyPanel:        LinearLayout
    private lateinit var togglePanelButton: ImageButton

    private var service: BarevService? = null
    private var isBound = false
    private var selectedNick: String? = null
    private var contacts: MutableList<Contact> = mutableListOf()
    private lateinit var buddyAdapter: BuddyAdapter
    private var account: Account = Account("", "")
    private var isPanelVisible = true

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastSpinnerPosition = 0
    private var isComposingSent = false
    private val typingPauseMs = 2_000L

    private val pausedRunnable = Runnable {
        val nick = selectedNick ?: return@Runnable
        if (isComposingSent) {
            service?.sendPaused(nick)
            isComposingSent = false
        }
    }

    private val typingTimeoutRunnable = Runnable {
        val nick = selectedNick ?: return@Runnable
        service?.connections?.get(nick)?.isTyping = false
        runOnUiThread { typingIndicator.visibility = View.GONE }
    }

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
            lastSpinnerPosition = when (service?.currentStatus) {
                PresenceStatus.AWAY -> 1
                PresenceStatus.DND  -> 2
                else                -> 0
            }
            statusSpinner.setSelection(lastSpinnerPosition)
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

        buddyListView     = findViewById(R.id.buddyList)
        accountButton     = findViewById(R.id.accountButton)
        addButton         = findViewById(R.id.addButton)
        connectButton     = findViewById(R.id.connectButton)
        chatView          = findViewById(R.id.chatView)
        messageInput      = findViewById(R.id.messageInput)
        sendButton        = findViewById(R.id.sendButton)
        scrollView        = findViewById(R.id.scrollView)
        statusSpinner     = findViewById(R.id.statusSpinner)
        peerStatusDot     = findViewById(R.id.peerStatusDot)
        chatTitleBar      = findViewById(R.id.chatTitleBar)
        typingIndicator   = findViewById(R.id.typingIndicator)
        noChatSelected    = findViewById(R.id.noChatSelected)
        buddyPanel        = findViewById(R.id.buddyPanel)
        togglePanelButton = findViewById(R.id.togglePanelButton)

        account  = AccountManager.load(this)
        contacts = ContactManager.load(this)

        updateAccountButton()
        setupBuddyAdapter()
        setupStatusSpinner()
        setupTypingDetection()

        accountButton.setOnClickListener     { showAccountDialog() }
        addButton.setOnClickListener         { showAddBuddyDialog() }
        sendButton.setOnClickListener        { sendMessage() }
        connectButton.setOnClickListener     { connectSelected() }
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

    private fun setupTypingDetection() {
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val nick = selectedNick ?: return
                val conn = service?.connections?.get(nick) ?: return
                if (!conn.streamEstablished) return
                uiHandler.removeCallbacks(pausedRunnable)
                if (!s.isNullOrEmpty()) {
                    if (!isComposingSent) {
                        service?.sendComposing(nick)
                        isComposingSent = true
                    }
                    uiHandler.postDelayed(pausedRunnable, typingPauseMs)
                } else {
                    if (isComposingSent) {
                        service?.sendPaused(nick)
                        isComposingSent = false
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupBuddyAdapter() {
        buddyAdapter = BuddyAdapter()
        buddyListView.adapter = buddyAdapter
        buddyListView.setOnItemClickListener { _, _, position, _ ->
            selectBuddy(contacts[position].key)
        }
        buddyListView.setOnItemLongClickListener { _, _, position, _ ->
            val contact = contacts[position]
            AlertDialog.Builder(this)
                .setTitle("Remove buddy")
                .setMessage("Remove ${contact.nick}?")
                .setPositiveButton("Remove") { _, _ ->
                    service?.removeBuddy(contact.key)
                    contacts = ContactManager.remove(this, contact.key)
                    if (selectedNick == contact.key) showNoChatSelected()
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
            this, R.layout.spinner_status_selected, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.spinner_status_selected, parent, false)
                view.findViewById<ImageView>(R.id.statusDot).setImageResource(items[position].first)
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.spinner_status_item, parent, false)
                view.findViewById<ImageView>(R.id.statusDot).setImageResource(items[position].first)
                view.findViewById<TextView>(R.id.statusLabel).text = items[position].second
                return view
            }
        }
        statusSpinner.adapter = adapter
        statusSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != lastSpinnerPosition) {
                    lastSpinnerPosition = position
                    val status = when (position) {
                        1    -> PresenceStatus.AWAY
                        2    -> PresenceStatus.DND
                        else -> PresenceStatus.AVAILABLE
                    }
                    uiHandler.removeCallbacks(presenceSendRunnable)
                    val anyConnected = service?.connections?.values?.any { it.streamEstablished } == true
                    if (anyConnected) service?.sendPresenceToAll(status)
                    else uiHandler.postDelayed(presenceSendRunnable, 3000)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun selectBuddy(key: String) {
        selectedNick = key
        isComposingSent = false
        uiHandler.removeCallbacks(typingTimeoutRunnable)
        uiHandler.removeCallbacks(pausedRunnable)

        val contact = contacts.firstOrNull { it.key == key }
        chatTitleBar.text = contact?.nick ?: key

        noChatSelected.visibility = View.GONE
        findViewById<View>(R.id.chatHeader).visibility = View.VISIBLE
        chatView.visibility      = View.VISIBLE
        scrollView.visibility    = View.VISIBLE
        messageInput.visibility  = View.VISIBLE
        sendButton.visibility    = View.VISIBLE
        statusSpinner.visibility = View.VISIBLE
        peerStatusDot.visibility = View.VISIBLE
        typingIndicator.visibility = View.GONE

        val conn = service?.connections?.get(key)
        updatePeerStatusDot(conn?.status ?: PresenceStatus.OFFLINE)
        updateConnectButton(conn?.isConnected == true)
        refreshChatView(key)
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

    private fun connectSelected() {
        val key = selectedNick ?: return
        service?.connectToBuddy(key)
    }

    private fun sendMessage() {
        val nick = selectedNick ?: return
        val msg  = messageInput.text.toString().trim()
        if (msg.isEmpty()) return
        service?.sendMessage(nick, msg)
        if (isComposingSent) {
            service?.sendPaused(nick)
            isComposingSent = false
        }
        messageInput.setText("")
    }

    private fun refreshChatView(nick: String) {
        val messages = service?.connections?.get(nick)?.messages ?: return
        val sb = android.text.SpannableStringBuilder()
        var lastDateKey = ""

        for (m in messages) {
            if (m.isSystem) continue

            if (m.dateKey.isNotEmpty() && m.dateKey != lastDateKey) {
                lastDateKey = m.dateKey
                val dateLabel = try {
                    val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                    val outSdf = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.getDefault())
                    outSdf.format(sdf.parse(m.dateKey)!!)
                } catch (e: Exception) { m.dateKey }

                if (sb.isNotEmpty()) sb.append("\n")
                val sep = android.text.SpannableString("─── $dateLabel ───")
                sep.setSpan(
                    android.text.style.ForegroundColorSpan(0xFF6B8299.toInt()),
                    0, sep.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sep.setSpan(
                    android.text.style.RelativeSizeSpan(0.8f),
                    0, sep.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                val sepPara = android.text.SpannableString("\n")
                sb.append(sep)
                sb.append("\n\n")
            }

            val senderColor = colorForNick(m.sender)
            val senderSpan = android.text.SpannableString(m.sender)
            senderSpan.setSpan(android.text.style.ForegroundColorSpan(senderColor), 0, senderSpan.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            senderSpan.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, senderSpan.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(senderSpan)
            sb.append("    ")
            val timeSpan = android.text.SpannableString(m.timestamp)
            timeSpan.setSpan(android.text.style.ForegroundColorSpan(0xFF6B8299.toInt()), 0, timeSpan.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            timeSpan.setSpan(android.text.style.RelativeSizeSpan(0.85f), 0, timeSpan.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(timeSpan)
            sb.append("\n")
            val bodySpan = android.text.SpannableString(m.body)
            bodySpan.setSpan(android.text.style.ForegroundColorSpan(0xFFC8D6E5.toInt()), 0, bodySpan.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(bodySpan)
            sb.append("\n\n")
        }

        runOnUiThread {
            chatView.text = sb
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private val senderColors = listOf(
        0xFF00E5FF.toInt(), 0xFF64FFDA.toInt(), 0xFF18FFFF.toInt(),
        0xFF40C4FF.toInt(), 0xFF69FFEF.toInt(), 0xFFB2EBF2.toInt()
    )
    private val nickColorMap = mutableMapOf<String, Int>()
    private var colorIndex = 0
    private fun colorForNick(nick: String): Int =
        nickColorMap.getOrPut(nick) { senderColors[colorIndex++ % senderColors.size] }

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
                    val contact = Contact(n, i, p)
                    contacts = ContactManager.add(this, n, i, p)
                    service?.addBuddy(contact)
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
            val conn    = service?.connections?.get(contact.key)
            view.findViewById<TextView>(R.id.buddyNick).text = contact.nick
            view.findViewById<ImageView>(R.id.buddyStatusDot)
                .setImageResource(statusDrawable(conn?.status ?: PresenceStatus.OFFLINE))
            view.setBackgroundColor(
                if (contact.key == selectedNick) 0xFF0D2135.toInt() else 0xFF0A0E14.toInt()
            )
            return view
        }
    }
}