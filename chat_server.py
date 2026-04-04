import socket
import threading

HOST = "0.0.0.0"
PORT = 5299

def make_message(body: str) -> str:
    return f'<message from="pc@test" to="phone@test" type="chat"><body>{body}</body></message>'

def make_presence() -> str:
    return '<presence from="pc@test"/>'

def make_unavailable() -> str:
    return '<presence type="unavailable"/>'

def make_stream() -> str:
    return '<stream:stream from="pc@test" to="phone@test" version="1.0">'

def receive_loop(conn):
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                print("Client disconnected.")
                break

            text = data.decode("utf-8", errors="replace").strip()
            if text:
                print(f"Phone raw: {text}")
    except Exception as e:
        print("Receive error:", e)

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind((HOST, PORT))
    server.listen(1)

    print(f"Listening on {HOST}:{PORT} ...")
    conn, addr = server.accept()
    print("Client connected from:", addr)

    receive_thread = threading.Thread(target=receive_loop, args=(conn,), daemon=True)
    receive_thread.start()

    print("\nCommands:")
    print("  /stream        send <stream:stream>")
    print("  /presence      send <presence/>")
    print('  /offline       send <presence type="unavailable"/>')
    print("  anything else  send as <message><body>...</body></message>")
    print("  /exit          close server\n")

    try:
        while True:
            user_input = input("You: ").strip()

            if not user_input:
                continue

            if user_input == "/exit":
                break
            elif user_input == "/stream":
                stanza = make_stream()
            elif user_input == "/presence":
                stanza = make_presence()
            elif user_input == "/offline":
                stanza = make_unavailable()
            else:
                stanza = make_message(user_input)

            conn.sendall((stanza + "\n").encode("utf-8"))
            print(f"Sent stanza: {stanza}")

    except KeyboardInterrupt:
        print("\nStopping server...")
    finally:
        try:
            conn.close()
        except Exception:
            pass
        server.close()

if __name__ == "__main__":
    main()