package dev.snocr.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var entries: List<NoteEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listView = findViewById(R.id.note_list)
        emptyView = findViewById(R.id.empty_view)
        listView.emptyView = emptyView
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            startActivity(
                Intent(this, AskActivity::class.java)
                    .putExtra(AskActivity.EXTRA_FILE_PATH, entry.file.absolutePath)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasStoragePermission()) {
            refresh()
        } else {
            emptyView.text = getString(R.string.storage_permission_needed)
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            refresh()
        }
    }

    private fun hasStoragePermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        emptyView.text = getString(R.string.scanning)
        thread {
            val found = NoteRepository.scan()
            runOnUiThread {
                entries = found
                emptyView.text = getString(R.string.no_notes_found)
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, R.string.settings)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_REFRESH, 1, R.string.refresh)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SETTINGS -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        MENU_REFRESH -> {
            if (hasStoragePermission()) refresh(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_note, parent, false)
            val entry = entries[position]
            view.findViewById<TextView>(R.id.note_name).text = entry.name
            view.findViewById<TextView>(R.id.note_details).text = getString(
                R.string.note_details,
                entry.relativePath,
                DateUtils.getRelativeTimeSpanString(entry.file.lastModified()),
                Formatter.formatShortFileSize(this@MainActivity, entry.file.length()),
            )
            return view
        }
    }

    companion object {
        private const val REQUEST_STORAGE = 1
        private const val MENU_SETTINGS = 10
        private const val MENU_REFRESH = 11
    }
}
