package org.example.hiddenxapk

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.pm.PackageInstaller
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var source: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        source = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent.data
        }
        status.text = if (source == null) "Open an .xapk file from your file manager." else "Selected file. Tap Extract & Install."
    }

    private fun buildUi() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 32, 36, 32)
        }
        val title = TextView(this).apply { text = "XAPK Installer"; textSize = 24f; gravity = Gravity.CENTER }
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 28, 0, 28) }
        val button = Button(this).apply { text = "Extract & Install"; setOnClickListener { extractAndInstall() } }
        layout.addView(title); layout.addView(status); layout.addView(button)
        setContentView(layout)
    }

    private fun extractAndInstall() {
        val uri = source ?: return
        try {
            val dir = File(cacheDir, "xapk_" + System.currentTimeMillis())
            dir.mkdirs()
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val safeName = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && !safeName.startsWith("/") && safeName.split("/").none { it == ".." }) {
                            val out = File(dir, safeName)
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { dest -> zip.copyTo(dest) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: throw Exception("Could not read the selected file.")
            val apks = dir.walkTopDown().filter { it.isFile && it.extension.equals("apk", true) }.toList()
            val obbs = dir.walkTopDown().filter { it.isFile && it.path.contains("/Android/obb/") }.toList()
            if (apks.isEmpty()) throw Exception("No APK files were found in this archive.")
            status.text = "Found " + apks.size + " APK file(s). Starting Android installer…"
            installApks(apks)
            if (obbs.isNotEmpty()) status.append("\\n\\nOBB data detected (" + obbs.size + " file(s)). This prototype does not copy OBB files automatically.")
        } catch (e: Exception) {
            status.text = "Error: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun installApks(files: List<File>) {
        if (!packageManager.canRequestPackageInstalls()) {
            status.text = "Allow 'Install unknown apps' for this installer, then open the XAPK again."
            val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:" + packageName))
            startActivity(settings)
            return
        }
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            files.forEachIndexed { index, file ->
                session.openWrite("apk_" + index + ".apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            val callback = Intent(this, InstallStatusReceiver::class.java).setAction("org.example.hiddenxapk.INSTALL_STATUS")
            val pending = PendingIntent.getBroadcast(this, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
            status.text = "Installation submitted. Follow Android's installer prompt."
        } finally {
            session.close()
        }
    }
}
