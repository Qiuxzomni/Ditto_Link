# Ditto Link Privacy Policy

**Effective date: November 12, 2026**
**App version covered: 1.7.9 and later versions unless this policy is replaced**

Ditto Link is an Android companion app for transferring clipboard text and files between your phone and a PC running Ditto Clipboard Manager. This policy explains what the app processes, where it is stored, and who receives it.

## Who provides the app

Ditto Link is provided by **VibeCodeDrop**. For privacy questions, contact [VibeCodeDrop@pm.me](mailto:VibeCodeDrop@pm.me).

## Information the app processes

Ditto Link processes information needed to set up and use transfers:

- **Text and files you choose to transfer.** You can type text, tap **Paste** to read the phone clipboard into the composer, use Android's selected-text action, or choose files with Android's share sheet or file picker. When you send or receive content, it is transferred between your phone and the PC you select.
- **PC connection details.** Saved PC names, local or Tailscale IP addresses, the Ditto network password, and which PC is active. The app encrypts saved passwords using AES-GCM with a key stored in Android Keystore. Other connection details are stored in the app's private storage on your phone.
- **Transfer history.** The app keeps a local history of up to 100 recent transfers. A text entry can include the transferred text, limited to 4,000 characters per entry. File entries include a label, file name or local file reference, transfer direction, and time. Lifetime sent and received counts are also stored locally.
- **Files being transferred.** Files selected for sending are temporarily copied into the app's private cache so Ditto can send them. Files received from a PC are saved in `Download/Ditto` where supported by Android. Temporary files may remain in the app cache until Android or the app clears them.
- **Local network discovery results.** If you tap **Scan LAN**, the app probes reachable IPv4 addresses on the currently connected private Wi-Fi or Ethernet network for a Ditto PC. It displays any responding PC's IP address and an available device name; Android's system name resolver may be used to look up that name. The scan is initiated by you and its connection probes are limited to the local network.

If you enable **Background Receive**, the app runs a foreground listener so a configured PC can send content to your phone. Received text is placed on the Android clipboard, and received files are saved on the device. Android may show notifications about the listener or transfer progress.

## Where information goes

Ditto Link has no user account, cloud storage, or developer-operated server. It does not send transfer contents, connection details, or usage analytics to VibeCodeDrop. The app contains no advertising or analytics SDKs.

When you transfer content, the selected PC receives the content and the connection information needed by Ditto, which can include the phone's IP address. The app uses Ditto's compatible encrypted transfer format with the Ditto network password. This is not an HTTPS/TLS connection; protect the password and use only PCs and networks you trust. When you use Tailscale, its network service is provided separately by Tailscale and is subject to Tailscale's own privacy terms.

The app can open third-party services only when you choose an action such as installing Tailscale, opening the Ko-fi donation page, or emailing support. Those services may receive information under their own privacy policies. Ditto Link does not control them.

## Storage, retention, and deletion

App settings, connection profiles, history, and lifetime counts are stored on your phone in app storage. The app does not maintain a remote copy. History entries are limited to the 100 most recent transfers; text content in each entry is limited to 4,000 characters. Lifetime counters remain on the phone until app data is cleared or the app is uninstalled.

You can remove history entries using the app's available delete controls. You can delete received files from `Download/Ditto` using the app or Android's file manager. Deleting a history entry does not delete a copy already delivered to your PC. Files in the public `Download/Ditto` folder may remain after Ditto Link is uninstalled; delete those files yourself if you no longer want them. Clearing the app's storage or uninstalling it removes app-private profiles, history, cache, and the local encryption key.

VibeCodeDrop does not receive or retain your transfer contents, so there is no server-side copy for us to retrieve or delete.

## Security

Saved Ditto passwords are encrypted at rest using Android Keystore. Transfer contents are sent directly to your selected PC using Ditto's compatible encrypted protocol; transfers are not sent through a VibeCodeDrop server. No method of storage or transmission is guaranteed to be completely secure. Use a strong Ditto password, keep your device protected, and avoid sending sensitive content over networks you do not trust.

## Children

Ditto Link is not specifically designed for children and does not ask for a user's age. VibeCodeDrop does not knowingly collect personal information from children.

## Changes to this policy

This policy may be updated if the app's data practices change. The current version and effective date will be published at this URL.
