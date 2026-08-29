package com.wuying.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wuying.browser.R
import com.wuying.browser.data.DownloadManagerHelper
import com.wuying.browser.data.DownloadEntity
import kotlinx.coroutines.launch

class DownloadsActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private val adapter = DownloadsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        rv = findViewById(R.id.rv_downloads)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        lifecycleScope.launch {
            adapter.submit(DownloadManagerHelper.get(this@DownloadsActivity).all())
        }
        findViewById<TextView>(R.id.btn_clear_downloads).setOnClickListener {
            lifecycleScope.launch {
                DownloadManagerHelper.get(this@DownloadsActivity).clear()
                adapter.submit(emptyList())
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private class DownloadsAdapter : RecyclerView.Adapter<DownloadsAdapter.VH>() {
        private val items = mutableListOf<DownloadEntity>()
        fun submit(list: List<DownloadEntity>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            return VH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.name.text = e.fileName
            holder.url.text = e.url.ifBlank { e.localPath }
            holder.size.text = formatSize(e.size)
        }
        private fun formatSize(b: Long): String = when {
            b < 1024 -> "${b}B"
            b < 1024 * 1024 -> "${b / 1024}KB"
            b < 1024 * 1024 * 1024 -> "${b / 1024 / 1024}MB"
            else -> "${b / 1024 / 1024 / 1024}GB"
        }
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tv_name)
            val url: TextView = v.findViewById(R.id.tv_url)
            val size: TextView = v.findViewById(R.id.tv_size)
        }
    }
}
