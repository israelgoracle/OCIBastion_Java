# OCIBastion_Java

Java desktop implementation of the OCI Bastion application. The same JAR is intended for Windows, Linux and macOS. It provides an OCI Bastion SOCKS5 connection, SSH destinations, local SSH forwards, named connections, and compatible JSON import/export.

## Requirements

- A graphical desktop and Java 8 or later. A JDK is required to build; a runtime is enough to run the JAR. Java 9+ enables additional recovery checks for a previous owned SSH process when the operating system exposes sufficient process metadata.
- Oracle OCI CLI configured with a working OCI profile and permissions to list compartments/bastions and create/read Bastion sessions.
- OpenSSH client and `ssh-keygen` on PATH. Bastion SSH requires support for `StrictHostKeyChecking=accept-new` and `SetEnv` (OpenSSH 7.8+).
- Bastion public/private key pair and the separate private keys used by target instances. Load an encrypted Bastion private key into `ssh-agent` before connecting.
- Network access allowed by the Bastion's client CIDR allowlist. The Bastion must support dynamic port forwarding sessions.
- A local terminal: Windows PowerShell, macOS Terminal/iTerm2, or Linux `x-terminal-emulator`, GNOME Terminal, Konsole, XFCE Terminal or xterm.

There are **no Java library dependencies**, no Maven/Gradle requirement and no dependency on netcat, curl or AppleScript outside macOS terminal launching. The application uses your existing OCI CLI authentication; it never reads or saves OCI API private key contents.

OCI subprocesses run without `OCI_CLI_AUTO_PROMPT`: OCI CLI treats even the value `False` as enabling its interactive console. The app removes this variable only from its child environment, preserving your terminal environment and authentication settings.

## Build and run

From this folder on Windows:

```powershell
# Set JAVA_HOME if the JDK is not already on PATH. Example for the JDK found on this machine:
$env:JAVA_HOME = 'C:\Program Files\Java\jdk1.8.0_501'
.\build.ps1 -Test
.\run.ps1
```

On Linux or macOS:

```sh
# Set JAVA_HOME to your JDK if needed.
sh build.sh --test
sh run.sh
```

Or launch the built JAR directly on any platform:

```sh
java -jar build/OCIBastion_Java.jar
java -jar build/OCIBastion_Java.jar --help
```

Distribute `build/OCIBastion_Java.jar` with this README and the launch scripts. The recipient still needs Java, OCI CLI and OpenSSH. The `build/classes` and test directories are not runtime dependencies. A JDK's `jpackage` can be used separately to produce an OS-specific application image; no native installer or bundled runtime is supplied here.

## Workflow

1. Browse to your local `.oci/config`, load profiles and choose one. Profile selection clears stale compartment/bastion choices.
2. Load compartments in pages of 100. Select a compartment by its hierarchical label, or enter its OCID directly when listing is not permitted. Find and select a Bastion, or enter its OCID directly.
3. Select the Bastion public/private keys, SOCKS port (default 2222) and session TTL (1800–10800 seconds).
4. Choose automatic, manual or direct HTTP proxy mode. Automatic checks environment variables, Java's system proxy selector, then a static proxy in `http://wpad/wpad.dat`. Manual mode takes an HTTP CONNECT host and port.
5. Connect Bastion. The app verifies the key fingerprints, attempts to reuse its recorded active session, creates one when required, and starts a loopback-only SOCKS5 listener. The status/log show connection progress and session expiry.
6. Add/edit SSH destinations with host, user, SSH port and instance private key. **Open SSH** starts an interactive session in a terminal. **Copy SSH** copies a runnable command. Windows clipboard commands use `Start-Process` in PowerShell to preserve embedded native argument quotes.
7. Add local tunnels with an SSH host/key plus local port, remote host and remote port. **Open tunnel** runs `ssh -N -L 127.0.0.1:local:remote:port` through the active Bastion SOCKS proxy. A copied destination/tunnel command still needs the Bastion SOCKS listener to be active when executed.
8. Save the current form under a name. Update, rename and delete saved entries. Clear fields resets the current form while retaining saved connections. **Save settings** persists the current form without updating the named snapshot.
9. Export all configurations or only the active configuration. Active export of a named connection contains its name and current visible edits. Import merges by name, preserves unrelated entries, remaps colliding UUIDs and confirms before replacing the current form or existing names. Import and changes to the Bastion form are disabled while its tunnel is running.
10. Disconnect or quit to stop the owned Bastion SSH process. A tray indicator is available on desktops that support Java's SystemTray.

### Older SSH servers

If a destination reports `no matching host key type found. Their offer: ssh-rsa,ssh-dss`, edit that SSH destination or local tunnel and select **Allow legacy RSA host key (ssh-rsa / SHA-1)** under SSH compatibility. Save the editor and reopen the SSH connection. Use **Update** on the named Bastion connection if you also want its saved snapshot to retain the change.

This appends `HostKeyAlgorithms=+ssh-rsa` for that entry, preserving the normal modern algorithm choices. It does not enable `ssh-dss`, change the Bastion's algorithms, or disable host-key verification. The server's host fingerprint still needs verification at OpenSSH's prompt. SHA-1 host signatures are a compatibility measure; updating the target SSH server to support modern host signatures removes the need for it. See [OpenSSH's legacy algorithm guidance](https://www.openssh.org/legacy.html).

If host-key negotiation succeeds but an RSA instance key is rejected with `Permission denied (publickey,...)`, an older SSH server may also require legacy RSA **user** signatures. Edit the entry and select **Allow legacy RSA user signatures (ssh-rsa / SHA-1)**. This independently appends `PubkeyAcceptedKeyTypes=+ssh-rsa` (the backward-compatible alias of `PubkeyAcceptedAlgorithms`). Both are additive per-entry options, disabled by default. See [OpenSSH 8.8's host/user RSA compatibility example](https://www.openssh.org/txt/release-8.8).

An authentication rejection alone does not prove an algorithm mismatch. If enabling user-signature compatibility does not help, verify the SSH username and that the selected **instance** key's public counterpart is in that account's `authorized_keys`. Trusting the server's host key does not authorize a client key. For diagnosis, add `-vv` to the SSH arguments and inspect the `Offering public key`, `Server accepts key`, and signature-algorithm messages; private key contents are not needed.

The optional JSON fields are `allowLegacyRsaHostKey: true` and `allowLegacyRsaUserSignature: true` on a destination/tunnel. They are preserved in Java settings and exports; the original Swift app ignores these extensions.

Sessions created in OCI remain until their configured expiry, including when you disconnect or cancel while OCI is creating a session. External SSH terminal windows remain under your control; their Bastion proxy stops when the application disconnects.

## Existing Swift configuration

Use **Export configuration** in the original app and **Import** here. Both archive versions 1 and 2 are supported, including legacy files without `tunnels`, `proxyMode`, or `terminalApp`, and named-only version 2 exports. Java exports use version 2 and the original Swift field names.

Change macOS paths such as `/Users/.../.oci/config` and `/Users/.../key.pem` to local paths after importing on another operating system. Reopen imported destination/tunnel editors to change instance key paths too. The app does not copy keys, unpack `claves.zip`, migrate credentials, or automatically modify the original application's settings.

## Storage and configuration overrides

| OS | Settings location |
| --- | --- |
| Windows | `%APPDATA%\OCIBastion_Java\settings.json` |
| macOS | `~/Library/Application Support/OCIBastion_Java/settings.json` |
| Linux | `${XDG_CONFIG_HOME:-~/.config}/OCIBastion_Java/settings.json` |

Settings and exports contain key **paths**, profiles, OCIDs and destination information, never key contents or passwords. Writes use a temporary file and atomic replacement where supported. Files receive mode `0600` on POSIX filesystems or a current-owner-only ACL where Windows ACLs are available. A malformed settings file produces a startup error instead of being silently replaced.

Executable paths are discovered from PATH (case-insensitive `Path`/`PATH` lookup on Windows), with standard OpenSSH/Homebrew paths as fallbacks. On Windows, OCI CLI is also detected in `Oracle\oci_cli` under Program Files and Program Files (x86), including when a desktop launcher inherited an older PATH. Windows OCI CLI must provide `oci.exe`; `.cmd`/`.bat` launchers are not executed. Restart the app and its launching terminal after other PATH changes. Absolute executable overrides are available before `-jar`:

```sh
java -Docitools.home=/path/to/settings -Docitools.oci=/path/to/oci -Docitools.ssh=/path/to/ssh -Docitools.ssh-keygen=/path/to/ssh-keygen -jar build/OCIBastion_Java.jar
```

On Windows, quote an entire `-D...` argument if it contains spaces. On macOS/Linux, use normal shell quoting. Paths to Java/the application containing `%` or double quotes are rejected for Windows proxy-helper commands because OpenSSH expands percent tokens.

## Reconnection and ownership

- Transient failures reconnect with delays increasing from 1 to 30 seconds. A session near expiry is replaced on reconnect; repeated session failures prevent indefinite reuse of that session.
- OCI timestamps support `Z` and numeric timezone offsets such as `+00:00`, including fractional seconds, on Java 8 and later. A malformed session timestamp stops reconnection instead of repeatedly creating new sessions.
- Key mismatch, occupied port, host-key failure and common authorization/authentication failures require intervention. Unrelated processes are never terminated to release a port.
- The app holds a file lock per settings directory, owns the `Process` it starts, and waits for it to stop when disconnecting. It marks SSH with a unique `SetEnv` token.
- On Java 9+, recovery of an orphan from a previous run requires a matching PID, process start time, executable, owner token, loopback port and SSH target. If metadata is unavailable, recovery fails closed and an occupied port requires manual intervention. Java 8 cannot inspect an orphan this way and reports an occupied port instead. Unlike the macOS source, the error does not attempt to identify an unrelated listener's PID.
- Bastion SSH uses `BatchMode=yes`, `IdentitiesOnly=yes`, keepalives, `ExitOnForwardFailure=yes` and `StrictHostKeyChecking=accept-new`. Destination terminal sessions retain normal interactive OpenSSH authentication/host-key prompts.

## Proxy scope

The bundled Java helper implements unauthenticated HTTP CONNECT and SOCKS5 CONNECT with remote target-name resolution. It preserves binary stdin/stdout for OpenSSH. OCI CLI receives the same resolved HTTP endpoint. Direct mode clears inherited HTTP/HTTPS/ALL proxy settings for OCI CLI.

Authenticated HTTP proxies, TLS-to-proxy URLs and full JavaScript PAC execution are not implemented. Static WPAD extraction follows the original app's behavior and does not evaluate URL-specific PAC branches. Set manual/direct mode if your network's PAC cannot be represented by a single endpoint. Java system proxy discovery depends on the OS/JVM's desktop integration, particularly on Linux.

## Validation

`build.ps1 -Test` or `sh build.sh --test` runs the offline regression suite: JSON validation, archive merge/export rules, original Swift fixtures when available, command validation and quoting, binary HTTP CONNECT/SOCKS5 handshakes, mocked OCI responses, settings permissions, occupied ports, process output draining, timeout and cancellation. When OpenSSH is installed, a real SSH client exchanges its banner through the actual Java ProxyCommand and a local mock HTTP proxy. No OCI request is made by the tests.

Windows/Java 8 has been exercised locally. Linux/macOS and live OCI connectivity still require validation on those systems with an authorized OCI profile.

## References

- [Oracle CLI dynamic port forwarding session command](https://docs.oracle.com/en-us/iaas/tools/oci-cli/latest/oci_cli_docs/cmdref/bastion/session/create-session-create-dynamic-port-forwarding-session-target-resource-details.html)
- [Oracle Java Process API](https://docs.oracle.com/en/java/javase/17/core/process-api1.html)
- [OpenJDK Windows process argument handling](https://github.com/openjdk/jdk17u/blob/master/src/java.base/windows/classes/java/lang/ProcessImpl.java)
