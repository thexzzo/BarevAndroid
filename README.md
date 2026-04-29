first patch:
- an extremely simple yet working android app that connects to a pidgin client
- Nick and Yggdrasil address hard coded for now 

second patch:
- Yggdrasil address no longer hard coded
- Automated the process of sending status (after 3 seconds of the user changing the status, the presence is updated on pidgin)
- Added an status icon next to the name of the peer that changes color accordingly
- Added icons for each status

third patch:
- Simultaneous connection between multiple peers added
- Typing indicator added
- System messages mainly used for debugging purposes removed
- Improved ping/pong procedure allowing longer and stable connection between peers
- Added a toggle button which now hides the peer info on the left giving more space to the chat interface
- Peer name, time of the message and the message itself have their own fields and are no longer set to one line

forth patch:
- UI is now more dark mode friendly
- Connect button removed (the connection will happen  with any already online peer automatically after approximately 20 seconds of opening the app and the app actively tries to connect to any other new peer while it's running)
- Issue of presence stanzas not being updated on the client accordingly resolved
- Logo updated to a modified version of the original Barev Purple
- Up to 200 chat logs are saved for each peer
- .apk file available for download! (https://github.com/thexzzo/BarevAndroid/releases/download/v1.2/Barev.apk
)

fifth patch:
- Brought the 'Connect' button back for each chat in order to resolve the unstable connection

sixth patch:
- Resolved a bug where changing the status from away or dnd to available wouldn't update the status on the peer's app
- Resolved a bug where typing indicator of the peer wouldn't appear next to their nick

