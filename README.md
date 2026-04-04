**Barev Android Messenger **

**Overview**

This project implements a serverless peer-to-peer messaging application for Android, inspired by the Barev protocol. Unlike traditional messaging systems, this application does not rely on centralized servers. Instead, it establishes direct TCP connections between peers and exchanges structured XML-based messages (stanzas).

The goal of this project is to recreate the core functionality of Barev in a standalone Android application, demonstrating decentralized communication principles and protocol design.

**Implemented Features (Current Stage)**

The application currently supports the following:

**✅ Core Communication**
Direct peer-to-peer TCP connection
Client (connect) and server (listen) modes
Communication over configurable IP and port (default: 5299)
**✅ Protocol (Barev-style Stanzas)**

The system exchanges structured XML stanzas similar to XMPP:

<stream> → session initialization
<message> → chat messages
<presence> → status updates
<ping/> / <pong/> → keepalive
<composing/> / <paused/> → typing notifications
**✅ Messaging**
Send and receive text messages
Proper XML escaping/unescaping
Structured message format:
<message from="android@barev" to="pc@barev" type="chat">
  <body>Hello</body>
</message>
**✅ Presence / Status**
Online status
Custom status messages
Offline notification (type="unavailable")
**✅ Typing Indicators**
Shows when peer is typing
Shows when typing stops
**✅ Connection Management**
Smart ping system:
Only sends ping when idle
Waits for pong
Disconnects if no response




**Architecture**

The system follows a simple peer-to-peer model:

Phone A (listener)  ←→  Phone B (connector)
         OR
Android App         ←→  Python Test Server

Each peer:

Opens a socket
Exchanges XML stanzas
Maintains connection state
Handles incoming messages asynchronously
**Current Testing Setup**

At this stage, the system is tested using:

1. Android ↔ PC (Python Server)

A Python-based peer (chat_server.py) is used to:

simulate another Barev client
validate protocol behavior
debug communication
2. Same Wi-Fi Network
Both devices must be on the same LAN
One acts as listener, the other connects
**How to Run the Project**
🔹 Step 1 — Run Python Server (PC)
python chat_server.py

Expected output:

Listening on 0.0.0.0:5299
Waiting for Android phone to connect...
🔹 Step 2 — Run Android App
Open app
Fill in:
Peer IP → your PC IP (e.g. 192.168.x.x)
Port → 5299
Press Connect
🔹 Step 3 — Test Communication

On Android:

Send message
Send presence

On PC:

You: hello

Try commands:

/stream
/presence online
/typing
/paused