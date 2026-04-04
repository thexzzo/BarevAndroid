# Barev Messenger (Capstone Project)

## 📌 Overview

This project is a **serverless peer-to-peer messaging application** built for Android. It implements a lightweight version of the **Barev protocol**, which is inspired by **XMPP**, and operates over direct peer-to-peer connections without centralized servers.

The application uses **TCP sockets** for communication and is designed to run over the **Yggdrasil overlay network**, enabling secure and decentralized messaging using IPv6 addressing.

---

## 🚀 Features Implemented

### ✅ Core Networking
- Direct peer-to-peer TCP connection
- Two-way communication between devices
- No central server required

### ✅ XMPP-style Protocol (Barev-inspired)
- Stream initialization (`<stream:stream>`)
- Message stanzas (`<message>`)
- Presence stanzas (`<presence>`)
- Basic stanza parsing

### ✅ Messaging
- Send and receive messages
- XML-based structured communication

### ✅ Presence System
- Online (`<presence/>`)
- Offline (`<presence type="unavailable"/>`)

### ✅ Chat State Notifications (XEP-0085)
- Typing indicator (`<composing/>`)
- Paused typing (`<paused/>`)

### ✅ Protocol Abstraction
- `BarevProtocol.kt` handles:
  - stanza creation
  - stanza parsing
- `MainActivity.kt` handles:
  - UI
  - connection lifecycle

---

## 🔧 Technologies Used

- Kotlin (Android)
- TCP Sockets
- XML (XMPP-style stanzas)
- Yggdrasil Network (planned integration)

---

## 🧪 Testing Tools

- Python TCP server used for testing:
  - sending/receiving XML stanzas
  - simulating peer behavior

---

## 📈 Next Steps

- Yggdrasil integration (real IPv6 peer-to-peer)
- XMPP Ping (XEP-0199)
- Advanced presence states (away, dnd)
- File transfer (inspired by XEP-0095 / XEP-0096)
- UI improvements

---

## 🎯 Goal

To implement a **fully decentralized messaging system** based on Barev and XMPP principles, demonstrating peer-to-peer communication without centralized infrastructure.

---

## 👤 Author

Reza  
Capstone Project – 2026