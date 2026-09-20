package com.cd4li.tmuxcore

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import java.util.concurrent.Executors

private const val TAG = "TmuxCore"

/** Android ships a cut-down "BC" provider without Ed25519; replace it with the
 * full BouncyCastle so JSch can sign with an ed25519 key. Idempotent. */
private fun ensureEd25519Provider() {
    if (Security.getProvider("BC") is BouncyCastleProvider) return
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 1)
}

/** Thrown when the server rejects all offered auth methods (so the UI can prompt
 * for a password). */
class SshAuthException(msg: String) : Exception(msg)

/**
 * One interactive SSH shell to [host] that runs [initialCommand] (typically a
 * tmux attach) and pumps bytes. Auth uses the key at [keyPath] if present and/or
 * [password] if given.
 *
 * Callbacks fire on background threads; the caller marshals to the UI thread.
 */
class SshSession(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val keyPath: String?,
    private val password: String?,
    private val initialCommand: String?,
    private val onBytes: (ByteArray, Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onClosed: () -> Unit
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @Volatile private var closed = false

    fun connect() {
        Thread({
            try {
                onStatus("Connecting to $user@$host …")
                val s = openSession(host, port, user, keyPath, password)
                session = s

                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm-256color", cols, rows, 0, 0)
                val input: InputStream = ch.inputStream
                out = ch.outputStream
                ch.connect(15000)
                channel = ch

                if (!initialCommand.isNullOrBlank()) {
                    onStatus("Starting …")
                    write("TERM=xterm-256color; $initialCommand\n")
                }

                Log.i(TAG, "channel connected, reading…")
                val buf = ByteArray(16 * 1024)
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) onBytes(buf.copyOf(n), n)
                }
            } catch (e: SshAuthException) {
                if (!closed) onStatus("AUTH_FAIL")
            } catch (e: Throwable) {
                Log.e(TAG, "SSH error", e)
                if (!closed) onStatus("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (!closed) onClosed()
            }
        }, "ssh-reader").start()
    }

    /** Queue raw bytes to the remote (safe to call from the UI thread). */
    fun send(data: ByteArray) {
        val o = out ?: return
        writer.execute {
            try {
                o.write(data)
                o.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private fun write(s: String) = send(s.toByteArray(Charsets.UTF_8))

    /**
     * Run a one-shot command on this live connection via a fresh exec channel
     * (reuses the existing auth — no new TCP/handshake). Used to drive tmux
     * server-side (select-pane, copy-mode, …) so control never goes through the
     * interactive pane and is independent of the user's tmux prefix.
     */
    fun exec(cmd: String) {
        val s = session ?: return
        writer.execute {
            try {
                val ch = s.openChannel("exec") as ChannelExec
                ch.setCommand(cmd)
                ch.setPty(false)
                val ins = ch.inputStream
                ch.connect(6000)
                val buf = ByteArray(2048)
                while (ins.read(buf) >= 0) { /* drain */ }
                ch.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        cols = newCols
        rows = newRows
        val ch = channel ?: return
        writer.execute {
            try {
                ch.setPtySize(newCols, newRows, 0, 0)
            } catch (_: Throwable) {
            }
        }
    }

    fun close() {
        closed = true
        writer.execute {
            try { channel?.disconnect() } catch (_: Throwable) {}
            try { session?.disconnect() } catch (_: Throwable) {}
        }
        writer.shutdown()
    }

    companion object {
        /** Generate an ed25519 keypair at [keyPath] if none exists yet, so the app
         * can install it via ssh-copy-id. Returns true if a key is present after. */
        fun ensureKey(keyPath: String, comment: String = "tmuxtv"): Boolean {
            val f = File(keyPath)
            if (f.exists() && f.length() > 0L) return true
            return try {
                ensureEd25519Provider()
                // JSch can't generate ed25519; ECDSA P-256 is short, modern and works on Android.
                val kp = com.jcraft.jsch.KeyPair.genKeyPair(JSch(), com.jcraft.jsch.KeyPair.ECDSA, 256)
                f.parentFile?.mkdirs()
                kp.writePrivateKey(keyPath)
                kp.writePublicKey("$keyPath.pub", comment)
                kp.dispose()
                f.setReadable(false, false); f.setReadable(true, true)
                f.setWritable(false, false); f.setWritable(true, true)
                Log.i(TAG, "generated ECDSA key at $keyPath")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "keygen failed", e)
                try { f.delete() } catch (_: Throwable) {}
                false
            }
        }

        /** The OpenSSH public-key line ("ssh-ed25519 AAAA… comment") for a private key. */
        fun publicKeyLine(keyPath: String, comment: String = "tmuxtv"): String? = try {
            ensureEd25519Provider()
            val kp = com.jcraft.jsch.KeyPair.load(JSch(), keyPath)
            val baos = ByteArrayOutputStream()
            kp.writePublicKey(baos, comment)
            kp.dispose()
            baos.toString("UTF-8").trim()
        } catch (e: Throwable) {
            Log.e(TAG, "pubkey read failed", e); null
        }

        /** Build and connect a JSch session with key and/or password auth. */
        fun openSession(
            host: String, port: Int, user: String,
            keyPath: String?, password: String?
        ): Session {
            ensureEd25519Provider()
            val jsch = JSch()
            if (keyPath != null && File(keyPath).exists()) jsch.addIdentity(keyPath)
            val s = jsch.getSession(user, host, port)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            if (password != null) s.setPassword(password)
            s.setConfig("ServerAliveInterval", "20000")
            s.serverAliveInterval = 20000
            try {
                s.connect(15000)
            } catch (e: com.jcraft.jsch.JSchException) {
                val m = e.message ?: ""
                if (m.contains("Auth fail", true) || m.contains("auth cancel", true))
                    throw SshAuthException(m)
                throw e
            }
            return s
        }

        /** Run [cmd] over an exec channel and return its stdout. Blocking. */
        fun runCommand(
            host: String, port: Int, user: String,
            keyPath: String?, password: String?, cmd: String,
            timeoutMs: Int = 12000
        ): String {
            val s = openSession(host, port, user, keyPath, password)
            try {
                val ch = s.openChannel("exec") as ChannelExec
                ch.setCommand(cmd)
                ch.setPty(false)
                val stdout = ch.inputStream
                val buf = ByteArrayOutputStream()
                ch.connect(timeoutMs)
                val tmp = ByteArray(8 * 1024)
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                while (true) {
                    while (stdout.available() > 0) {
                        val n = stdout.read(tmp)
                        if (n < 0) break
                        buf.write(tmp, 0, n)
                    }
                    if (ch.isClosed) break
                    if (System.nanoTime() > deadline) break
                    Thread.sleep(20)
                }
                ch.disconnect()
                return buf.toString("UTF-8")
            } finally {
                s.disconnect()
            }
        }
    }
}
