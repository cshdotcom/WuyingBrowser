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
import com.wuying.browser.data.HistoryManager
import com.wuying.browser.data.HistoryEntity
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private val adapter = HistoryAdapter { url ->
        // 点击在新标签打开
        val intent = android.content.Intent(this, BrowserActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            data = android.net.Uri.parse(url)
        }
        startActivity(intent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        rv = findViewById(R.id.rv_history)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        lifecycleScope.launch {
            adapter.submit(HistoryManager.get(this@HistoryActivity).all())
        }

        findViewById<TextView>(R.id.btn_clear_history).setOnClickListener {
            lifecycleScope.launch {
                HistoryManager.get(this@HistoryActivity).clear()
                adapter.submit(emptyList())
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private class HistoryAdapter(val onClick: (String) -> Unit) : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private val items = mutableListOf<HistoryEntity>()
        fun submit(list: List<HistoryEntity>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return VH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.title.text = e.title.ifBlank { e.url }
            holder.url.text = e.url
            holder.itemView.setOnClickListener { onClick(e.url) }
        }
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tv_title)
            val url: TextView = v.findViewById(R.id.tv_url)
        }
    }
}
