import socket
import threading
import base64
import struct

HOST = "0.0.0.0"
PORT = 5299

LOCAL_ID = "pc@barev"
PEER_ID  = "android@barev"

def escape(text: str) -> str:
    return (text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace('"', "&quot;")
                .replace("'", "&apos;"))

def unescape(text: str) -> str:
    return (text.replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&quot;", '"')
                .replace("&apos;", "'")
                .replace("&amp;",  "&"))

def extract_tag(data: str, tag: str) -> str:
    open_tag, close_tag = f"<{tag}>", f"</{tag}>"
    if open_tag in data and close_tag in data:
        return data.split(open_tag, 1)[1].split(close_tag, 1)[0]
    return ""

def extract_attr(data: str, attr: str) -> str:
    for quote in ('"', "'"):
        marker = f'{attr}={quote}'
        if marker in data:
            return data.split(marker, 1)[1].split(quote, 1)[0]
    return ""

def make_stream() -> str:
    return f'<stream from="{LOCAL_ID}" to="{PEER_ID}" version="1.0"/>'

def make_presence(status: str = "available", text: str = "") -> str:
    show_tag   = ""
    type_attr  = ""
    if status == "away":
        show_tag = "<show>away</show>"
    elif status == "dnd":
        show_tag = "<show>dnd</show>"
    elif status == "offline":
        return f'<presence from="{LOCAL_ID}" to="{PEER_ID}" type="unavailable"/>'
    status_tag = f"<status>{escape(text)}</status>" if text else ""
    return f'<presence from="{LOCAL_ID}" to="{PEER_ID}">{show_tag}{status_tag}</presence>'

def make_unavailable() -> str:
    return make_presence("offline")

def make_message(body: str) -> str:
    return (f'<message from="{LOCAL_ID}" to="{PEER_ID}" type="chat">'
            f'<body>{escape(body)}</body></message>')

def make_composing() -> str:
    return (f'<message from="{LOCAL_ID}" to="{PEER_ID}" type="chat">'
            f'<composing xmlns="http://jabber.org/protocol/chatstates"/>'
            f'</message>')

def make_paused() -> str:
    return (f'<message from="{LOCAL_ID}" to="{PEER_ID}" type="chat">'
            f'<paused xmlns="http://jabber.org/protocol/chatstates"/>'
            f'</message>')

def make_ping() -> str:  return "<ping/>"
def make_pong() -> str:  return "<pong/>"
_TINY_JPEG_B64 = (
    "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB"
    "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAARC"
    "AABAAEDASIA2gABEQECEQH/xAAUAAEAAAAAAAAAAAAAAAAAAAAK/8QAFBABAAAA"
    "AAAAAAAAAAAAAAAAP/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAA"
    "AAAAAAAAAAAP/aAAwDAQACEQMRAD8AJgAB/9k="
)

def make_vcard_update() -> str:
    return (f'<iq from="{LOCAL_ID}" to="{PEER_ID}" type="set" id="vc2">'
            f'<vCard xmlns="vcard-temp">'
            f'<PHOTO><TYPE>image/jpeg</TYPE><BINVAL>{_TINY_JPEG_B64}</BINVAL></PHOTO>'
            f'</vCard></iq>')

def make_file_offer(sid: str, name: str = "hello.txt", size: int = 13) -> str:
    return (f'<iq from="{LOCAL_ID}" to="{PEER_ID}" type="set" id="ft_{sid}">'
            f'<si xmlns="http://jabber.org/protocol/si" id="{sid}" mime-type="text/plain">'
            f'<file xmlns="http://jabber.org/protocol/si/profile/file-transfer"'
            f' name="{escape(name)}" size="{size}"/>'
            f'</si></iq>')

def receive_loop(conn: socket.socket) -> None:
    buffer = ""
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                print("Phone disconnected.")
                break
            buffer += data.decode("utf-8", errors="replace")
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                text = line.strip()
                if not text:
                    continue
                print(f"\nPhone raw: {text}")
                handle_stanza(conn, text)
    except Exception as e:
        print("Receive error:", e)

def handle_stanza(conn: socket.socket, text: str) -> None:
    if "<ping" in text:
        send(conn, make_pong(), silent=True)
        print("Replied with <pong/>")

    elif "<pong" in text:
        print("Pong received")

    elif "<composing" in text:
        peer = extract_attr(text, "from") or PEER_ID
        print(f"{peer} is typing…")

    elif "<paused" in text:
        peer = extract_attr(text, "from") or PEER_ID
        print(f"{peer} stopped typing")

    elif "<message" in text and "<body>" in text:
        peer = extract_attr(text, "from") or PEER_ID
        body = unescape(extract_tag(text, "body"))
        print(f"{peer}: {body}")

    elif "<presence" in text:
        peer       = extract_attr(text, "from") or PEER_ID
        ptype      = extract_attr(text, "type")
        show       = extract_tag(text, "show")
        status_txt = unescape(extract_tag(text, "status"))

        if ptype == "unavailable":
            print(f"{peer} went offline")
        elif show == "away":
            print(f"{peer} is away" + (f" – {status_txt}" if status_txt else ""))
        elif show == "dnd":
            print(f"{peer} is busy (DND)" + (f" – {status_txt}" if status_txt else ""))
        else:
            print(f"{peer} is online" + (f" – {status_txt}" if status_txt else ""))

    elif "<stream" in text:
        peer = extract_attr(text, "from") or PEER_ID
        print(f"Stream started by {peer}")
    elif "<BINVAL>" in text:
        peer   = extract_attr(text, "from") or PEER_ID
        binval = extract_tag(text, "BINVAL")
        if binval:
            try:
                img_bytes = base64.b64decode(binval)
                with open("peer_avatar.jpg", "wb") as f:
                    f.write(img_bytes)
                print(f"Avatar received from {peer} → saved to peer_avatar.jpg ({len(img_bytes)} bytes)")
            except Exception as e:
                print(f"Avatar decode error: {e}")
    elif "<vCard" in text and 'type="get"' in text:
        peer = extract_attr(text, "from") or PEER_ID
        print(f"{peer} requested our avatar – sending")
        send(conn, make_vcard_update())
    elif "<si " in text and 'type="set"' in text:
        peer = extract_attr(text, "from") or PEER_ID
        sid  = extract_attr(text, "id").removeprefix("ft_")
        name = extract_attr(text, "name")
        size = extract_attr(text, "size")
        print(f"{peer} offers file: {name} ({size} bytes) [sid={sid}]")
        accept = (f'<iq from="{LOCAL_ID}" to="{PEER_ID}" type="result" id="ft_{sid}">'
                  f'<si xmlns="http://jabber.org/protocol/si" id="{sid}"/></iq>')
        send(conn, accept)
        print("File offer accepted (auto)")
    elif "<si " in text and 'type="result"' in text:
        sid = extract_attr(text, "id").removeprefix("ft_")
        print(f"File transfer accepted by peer [sid={sid}]")

    else:
        print(f"Unknown stanza: {text}")

def send(conn: socket.socket, stanza: str, silent: bool = False) -> None:
    conn.sendall((stanza + "\n").encode("utf-8"))
    if not silent:
        print(f"Sent: {stanza}")

def main() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(1)

    print(f"Listening on {HOST}:{PORT} …  (Ctrl-C to quit)\n")
    conn, addr = server.accept()
    print(f"Phone connected from {addr}\n")

    t = threading.Thread(target=receive_loop, args=(conn,), daemon=True)
    t.start()

    print("Commands:")
    print("  /stream               send <stream/>")
    print("  /available [text]     send available presence")
    print("  /away [text]          send away presence")
    print("  /dnd  [text]          send do-not-disturb presence")
    print("  /offline              send unavailable presence")
    print("  /typing               send composing")
    print("  /paused               send paused")
    print("  /ping                 send <ping/>")
    print("  /avatar               send dummy vCard avatar")
    print("  /file                 send dummy file offer")
    print("  anything else         send as chat message")
    print("  /exit                 disconnect\n")

    import uuid
    try:
        while True:
            raw = input("You: ").strip()
            if not raw:
                continue

            if raw == "/exit":
                send(conn, make_unavailable())
                break
            elif raw == "/stream":
                stanza = make_stream()
            elif raw == "/offline":
                stanza = make_unavailable()
            elif raw.startswith("/available"):
                text = raw[len("/available"):].strip()
                stanza = make_presence("available", text)
            elif raw.startswith("/away"):
                text = raw[len("/away"):].strip()
                stanza = make_presence("away", text)
            elif raw.startswith("/dnd"):
                text = raw[len("/dnd"):].strip()
                stanza = make_presence("dnd", text)
            elif raw == "/typing":
                stanza = make_composing()
            elif raw == "/paused":
                stanza = make_paused()
            elif raw == "/ping":
                stanza = make_ping()
            elif raw == "/avatar":
                stanza = make_vcard_update()
            elif raw == "/file":
                sid = str(uuid.uuid4())[:8]
                stanza = make_file_offer(sid)
            else:
                stanza = make_message(raw)

            send(conn, stanza)

    except KeyboardInterrupt:
        print("\nStopping…")
    finally:
        try: conn.close()
        except Exception: pass
        try: server.close()
        except Exception: pass

if __name__ == "__main__":
    main()