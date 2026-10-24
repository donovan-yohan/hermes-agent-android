# Bot notification shade capture

Captured on a disposable API 34 emulator at 1080 × 1920, density 420 dpi. The user's emulator and physical Pixel were not used. The application received synthetic messages through the production `NotificationInbox`, `MessageNotifications` and `AndroidBotMessageSurface`. The letter avatars are synthetic bitmaps, not a real Gateway/profile fetch.

- `android-shade.png`: Android's automatically collapsed app bundle. Sender summaries are visible, but per-child avatars are not shown in this collapsed system header.
- `android-expanded.png`: the app bundle expanded. Planning bot and Research bot are independent native message children with distinct P/R avatars, the same Release planning room and separate previews. Native one-line ellipsis is expected; each child can be expanded further.
- `android-preview-off.png`: fixture restarted with previews disabled. OS readback confirms both message bodies are `New message`.

Android chooses app-level bundling. These are independently identified child notifications, not a guarantee that every bot appears as an always-expanded top-level card. No shortcut, bubbles or Conversation-space promotion is claimed.

The shade has no Desktop counterpart. Navigation keeps the existing Chat and Group Chats surfaces; this capture does not certify real Gateway cold/warm navigation, delayed Doze delivery, lock-screen rendering or physical-device behavior. Robolectric separately checks private publicVersion payloads and typed immutable notification intents.

The full synthetic notification dump is retained privately rather than published
with device diagnostics. It contains no configured Gateway or real conversation.
