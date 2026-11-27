package org.example.hiddenxapk

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageInstaller
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var installButton: Button
    private var source: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        source = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent.data
        }
        status.text = if (source == null) {
            "Open an .xapk file from your file manager."
        } else {
            "Selected file. Tap Extract & Install."
        }
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 32, 36, 32)
        }
        val title = TextView(this).apply {
            text = "XAPK Installer"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 28)
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        installButton = Button(this).apply {
            text = "Extract & Install"
            setOnClickListener { extractAndInstall() }
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(progress)
        layout.addView(installButton)
        setContentView(layout)
    }

    private fun updateStatus(message: String, busy: Boolean) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            status.text = message
            progress.visibility = if (busy) View.VISIBLE else View.GONE
            installButton.isEnabled = !busy
            installButton.text = if (busy) "Preparing..." else "Extract & Install"
        }
    }

    private fun extractAndInstall() {
        val uri = source ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            status.text = "Allow 'Install unknown apps' for this installer, then tap again."
            val settings = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
            ).setData(Uri.parse("package:" + packageName))
            startActivity(settings)
            return
        }

        installButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Reading XAPK archive..."
        Thread({ processArchive(uri) }, "xapk-installer-worker").start()
    }

    private fun processArchive(uri: Uri) {
        val archive = File.createTempFile("xapk_", ".zip", cacheDir)
        var sessionId = -1
        var committed = false
        var session: PackageInstaller.Session? = null

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedInputStream(input, COPY_BUFFER_SIZE).use { buffered ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var totalCopied = 0L
                        var lastReported = 0L
                        while (true) {
                            val count = buffered.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            totalCopied += count
                            if (totalCopied - lastReported >= STATUS_UPDATE_BYTES) {
                                lastReported = totalCopied
                                updateStatus("Reading archive: " + formatBytes(totalCopied), true)
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Could not read the selected file.")

            ZipFile(archive).use { zip ->
                val apkEntries = ArrayList<ZipEntry>()
                var obbCount = 0
                val entries = zip.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val lowerName = entry.name.lowercase()
                    val baseName = entry.name.substringAfterLast('/')
                    if (baseName.endsWith(".apk", ignoreCase = true)) {
                        apkEntries.add(entry)
                    }
                    if (lowerName.contains("/android/obb/") ||
                        lowerName.startsWith("android/obb/")
                    ) {
                        obbCount++
                    }
                }

                if (apkEntries.isEmpty()) {
                    throw IllegalStateException("No APK files were found in this archive.")
                }

                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
                val knownApkBytes = apkEntries.sumOf { if (it.size > 0) it.size else 0L }
                if (knownApkBytes > 0L) params.setSize(knownApkBytes)

                sessionId = installer.createSession(params)
                session = installer.openSession(sessionId)

                var totalApkBytes = 0L
                apkEntries.forEachIndexed { index, entry ->
                    updateStatus(
                        "Preparing APK " + (index + 1) + " of " + apkEntries.size + "...",
                        true
                    )
                    zip.getInputStream(entry).use { apkInput ->
                        session.openWrite(
                            "package_part_" + index + ".apk",
                            0,
                            if (entry.size >= 0L) entry.size else -1L
                        ).use { apkOutput ->
                            totalApkBytes += apkInput.copyTo(apkOutput, COPY_BUFFER_SIZE)
                            session.fsync(apkOutput)
                        }
                    }
                }

                val callback = Intent(this, InstallStatusReceiver::class.java)
                    .setAction("org.example.hiddenxapk.INSTALL_STATUS")
                val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pending = PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
                )
                session.commit(pending.intentSender)
                committed = true

                var message = "Prepared " + apkEntries.size + " APK file(s) (" +
                    formatBytes(totalApkBytes) + "). Android's installer is starting."
                if (obbCount > 0) {
                    message += "\n\nOBB data detected (" + obbCount +
                        " file(s)); automatic OBB copying is not supported yet."
                }
                updateStatus(message, false)
            }
        } catch (e: Exception) {
            if (sessionId >= 0 && !committed) {
                try {
                    packageManager.packageInstaller.abandonSession(sessionId)
                } catch (_: Exception) {
                }
            }
            updateStatus(
                "Error: " + (e.message ?: e.javaClass.simpleName),
                false
            )
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {
            }
            archive.delete()
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        } else {
            (bytes / 1024L).toString() + " KB"
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val STATUS_UPDATE_BYTES = 16L * 1024L * 1024L
    }
}
