package com.doubletap.screenshot

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ExcludedAppsActivity : AppCompatActivity() {

    private data class AppInfo(
        val name: String,
        val packageName: String,
        val icon: Drawable
    )

    private lateinit var adapter: AppAdapter
    private var allApps = listOf<AppInfo>()
    private var filteredApps = listOf<AppInfo>()
    private var excludedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_excluded_apps)

        excludedPackages = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
            .getStringSet(ScreenshotService.KEY_EXCLUDED_APPS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        val searchInput = findViewById<EditText>(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })

        adapter = AppAdapter()
        findViewById<ListView>(R.id.appList).adapter = adapter

        loadApps()
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)

        allApps = resolveInfos
            .filter { it.activityInfo.packageName != packageName }
            .map { ri ->
                AppInfo(
                    name = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName,
                    icon = ri.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }

        filteredApps = allApps
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun filterApps(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            val q = query.lowercase()
            allApps.filter { it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun toggleApp(packageName: String, excluded: Boolean) {
        if (excluded) {
            excludedPackages.add(packageName)
        } else {
            excludedPackages.remove(packageName)
        }
        getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putStringSet(ScreenshotService.KEY_EXCLUDED_APPS, excludedPackages)
            .apply()
        updateCount()
    }

    private fun updateCount() {
        val countText = findViewById<TextView>(R.id.excludedCount)
        val count = excludedPackages.size
        countText.text = if (count == 0) {
            getString(R.string.excluded_apps_none)
        } else {
            getString(R.string.excluded_apps_count, count)
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = filteredApps.size
        override fun getItem(position: Int): AppInfo = filteredApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val app = getItem(position)

            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.appName).text = app.name
            view.findViewById<TextView>(R.id.appPackage).text = app.packageName

            val checkbox = view.findViewById<CheckBox>(R.id.appCheckbox)
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = app.packageName in excludedPackages
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                toggleApp(app.packageName, isChecked)
            }

            view.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }

            return view
        }
    }
}
