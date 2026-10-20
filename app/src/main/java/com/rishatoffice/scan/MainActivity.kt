package com.rishatoffice.scan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rishatoffice.scan.databinding.ActivityMainBinding
import com.rishatoffice.scan.databinding.ItemDocBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = DocAdapter()

    private val docsDir: File by lazy { File(filesDir, "docs").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.fab.setOnClickListener { startActivity(Intent(this, ScannerActivity::class.java)) }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refresh()
    }

    private fun copyUri(src: Uri, dst: File) {
        contentResolver.openInputStream(src)?.use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        }
    }

    private fun refresh() {
        val files = docsDir.listFiles { f -> f.extension.equals("pdf", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        adapter.submit(files)
        binding.empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun openPdf(file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(file), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_pdf_viewer, Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdf(file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uriFor(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun renameDoc(file: File) {
        val input = EditText(this).apply { setText(file.nameWithoutExtension) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newName = input.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (newName.isNotEmpty()) {
                    val newPdf = File(docsDir, "$newName.pdf")
                    if (!newPdf.exists()) {
                        File(docsDir, file.nameWithoutExtension + ".jpg")
                            .takeIf { it.exists() }?.renameTo(File(docsDir, "$newName.jpg"))
                        file.renameTo(newPdf)
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteDoc(file: File) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                File(docsDir, file.nameWithoutExtension + ".jpg").delete()
                file.delete()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class DocAdapter : RecyclerView.Adapter<DocAdapter.VH>() {
        private var items: List<File> = emptyList()
        private val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

        fun submit(list: List<File>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemDocBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDocBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val file = items[position]
            h.b.name.text = file.nameWithoutExtension
            h.b.meta.text = fmt.format(Date(file.lastModified())) + "  •  " +
                Formatter.formatShortFileSize(h.b.root.context, file.length())

            val thumb = File(docsDir, file.nameWithoutExtension + ".jpg")
            if (thumb.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                h.b.thumb.setImageBitmap(BitmapFactory.decodeFile(thumb.path, opts))
            } else {
                h.b.thumb.setImageDrawable(null)
            }

            h.b.root.setOnClickListener { openPdf(file) }
            h.b.more.setOnClickListener { v ->
                PopupMenu(this@MainActivity, v).apply {
                    menu.add(0, 1, 0, R.string.share)
                    menu.add(0, 2, 1, R.string.rename)
                    menu.add(0, 3, 2, R.string.delete)
                    setOnMenuItemClickListener {
                        when (it.itemId) {
                            1 -> sharePdf(file)
                            2 -> renameDoc(file)
                            3 -> deleteDoc(file)
                        }
                        true
                    }
                    show()
                }
            }
        }
    }
}
