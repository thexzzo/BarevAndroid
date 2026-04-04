import socket
import threading

HOST = "0.0.0.0"
PORT = 5299

LOCAL_ID = "pc@barev"
PEER_ID = "android@barev"


def escape_xml(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


def unescape_xml(text: str) -> str:
    return (
        text.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", '"')
        .replace("&apos;", "'")
        .replace("&amp;", "&")
    )


def extract_tag(data: str, tag: str) -> str:
    open_tag = f"<{tag}>"
    close_tag = f"</{tag}>"
    if open_tag in data and close_tag in data:
        return data.split(open_tag, 1)[1].split(close_tag, 1)[0]
    return ""


def extract_attr(data: str, attr: str) -> str:
    dq = f'{attr}="'
    sq = f"{attr}='"

    if dq in data:
        return data.split(dq, 1)[1].split('"', 1)[0]
    if sq in data:
        return data.split(sq, 1)[1].split("'", 1)[0]
    return ""


def make_stream() -> str:
    return f'<stream from="{LOCAL_ID}" to="{PEER_ID}" version="1.0"/>'


def make_presence(status: str = "online") -> str:
    return f'<presence from="{LOCAL_ID}" to="{PEER_ID}"><status>{escape_xml(status)}</status></presence>'


def make_unavailable() -> str:
    return f'<presence from="{LOCAL_ID}" to="{PEER_ID}" type="unavailable"/>'


def make_message(body: str) -> str:
    return f'<message from="{LOCAL_ID}" to="{PEER_ID}" type="chat"><body>{escape_xml(body)}</body></message>'


def make_composing() -> str:
    return (
        f'<message from="{LOCAL_ID}" to="{PEER_ID}" type="chat">'
        f'<composing xmlns="http://jabber.org/protocol/chatstates"/>'
        f"</message>"
    )


def make_paused() -> str:
    return (
        f'<message from="{LOCAL_ID}" to="{PEER_ID}" type="chat">'
        f'<paused xmlns="http://jabber.org/protocol/chatstates"/>'
        f"</message>"
    )


def make_ping() -> str:
    return "<ping/>"


def make_pong() -> str:
    return "<pong/>"


def send_stanza(conn: socket.socket, stanza: str, log_output: bool = True) -> None:
    conn.sendall((stanza + "\n").encode("utf-8"))
    if log_output:
        print(f"Sent stanza: {stanza}")


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

                print(f"Phone raw: {text}")

                if "<ping" in text:
                    send_stanza(conn, make_pong(), log_output=False)
                    print("Replied with <pong/>")

                elif "<pong" in text:
                    print("Pong received")

                elif "<composing" in text:
                    from_id = extract_attr(text, "from") or "android@barev"
                    print(f"{from_id} is typing...")

                elif "<paused" in text:
                    from_id = extract_attr(text, "from") or "android@barev"
                    print(f"{from_id} stopped typing")

                elif "<message" in text and "<body>" in text:
                    from_id = extract_attr(text, "from") or "android@barev"
                    body = unescape_xml(extract_tag(text, "body"))
                    print(f"{from_id} says: {body}")

                elif "<presence" in text:
                    from_id = extract_attr(text, "from") or "android@barev"
                    stanza_type = extract_attr(text, "type")
                    status = unescape_xml(extract_tag(text, "status"))

                    if stanza_type == "unavailable":
                        print(f"{from_id} went offline")
                    elif status:
                        print(f"{from_id} status: {status}")
                    else:
                        print(f"Presence received from {from_id}")

                elif "<stream" in text:
                    from_id = extract_attr(text, "from") or "android@barev"
                    print(f"Stream started by {from_id}")

    except Exception as e:
        print("Receive error:", e)


def main() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(1)

    print(f"Listening on {HOST}:{PORT} ...")
    print("Waiting for Android phone to connect...\n")

    conn, addr = server.accept()
    print("Client connected from:", addr)

    receive_thread = threading.Thread(target=receive_loop, args=(conn,), daemon=True)
    receive_thread.start()

    print("\nCommands:")
    print("  /stream                 send <stream/>")
    print("  /presence               send online presence")
    print("  /presence hello         send status presence")
    print("  /offline                send unavailable presence")
    print("  /typing                 send composing state")
    print("  /paused                 send paused state")
    print("  /ping                   send <ping/>")
    print("  anything else           send chat message")
    print("  /exit                   close server\n")

    try:
        while True:
            user_input = input("You: ").strip()

            if not user_input:
                continue

            if user_input == "/exit":
                try:
                    send_stanza(conn, make_unavailable())
                except Exception:
                    pass
                break
            elif user_input == "/stream":
                stanza = make_stream()
            elif user_input == "/presence":
                stanza = make_presence("online")
            elif user_input.startswith("/presence "):
                status_text = user_input[len("/presence "):].strip()
                stanza = make_presence(status_text)
            elif user_input == "/offline":
                stanza = make_unavailable()
            elif user_input == "/typing":
                stanza = make_composing()
            elif user_input == "/paused":
                stanza = make_paused()
            elif user_input == "/ping":
                stanza = make_ping()
            else:
                stanza = make_message(user_input)

            send_stanza(conn, stanza)

    except KeyboardInterrupt:
        print("\nStopping server...")

    finally:
        try:
            conn.close()
        except Exception:
            pass

        try:
            server.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()