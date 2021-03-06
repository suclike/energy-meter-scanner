package ds.meterscanner.mvvm.view

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.preference.EditTextPreference
import android.support.v7.preference.Preference
import com.evernote.android.job.rescheduled
import com.github.salomonbrys.kodein.KodeinInjected
import com.github.salomonbrys.kodein.KodeinInjector
import com.github.salomonbrys.kodein.android.appKodein
import com.github.salomonbrys.kodein.instance
import com.takisoft.fix.support.v7.preference.PreferenceFragmentCompat
import ds.bindingtools.startActivity
import ds.meterscanner.R
import ds.meterscanner.data.Prefs
import ds.meterscanner.mvvm.BindableActivity
import ds.meterscanner.mvvm.SettingsView
import ds.meterscanner.mvvm.viewmodel.SettingsViewModel
import ds.meterscanner.scheduler.Scheduler
import kotlinx.android.synthetic.main.toolbar.*
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

@SuppressLint("CommitTransaction")
class SettingsActivity : BindableActivity<SettingsViewModel>(), SettingsView {

    override fun provideViewModel(): SettingsViewModel = defaultViewModelOf()
    override fun getLayoutId(): Int = R.layout.activity_settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        toolbar.title = getString(R.string.settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.content, Fragment.instantiate(this, SettingsFragment::class.java.name))
                .commitNow()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), KodeinInjected, SharedPreferences.OnSharedPreferenceChangeListener {
        override val injector: KodeinInjector = KodeinInjector()

        val prefs: Prefs by instance()
        val scheduler: Scheduler by instance()

        private val scanTries: EditTextPreference by PreferenceDelegate()
        private val city: EditTextPreference by PreferenceDelegate()
        private val alarms: Preference by PreferenceDelegate()
        private val jpegQuality: EditTextPreference by PreferenceDelegate()
        private val boilerTemp: EditTextPreference by PreferenceDelegate()
        private val correctionThreshold: EditTextPreference by PreferenceDelegate()
        private val shotTimeout: EditTextPreference by PreferenceDelegate()

        override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
            inject(appKodein())
            preferenceManager.sharedPreferencesName = "main_prefs"
            addPreferencesFromResource(R.xml.prefs)

            alarms.setOnPreferenceClickListener {
                activity.startActivity<AlarmsActivity>()
                true
            }
        }

        override fun onStart() {
            super.onStart()
            initView()
            preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onStop() {
            super.onStop()
            preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            initView()
        }

        private fun initView() {
            scanTries.summary = scanTries.text
            city.summary = city.text
            jpegQuality.summary = jpegQuality.text
            boilerTemp.summary = boilerTemp.text
            correctionThreshold.summary = correctionThreshold.text
            shotTimeout.summary = shotTimeout.text
            alarms.summary = scheduler.getScheduledJobs().filter { !it.rescheduled }.size.toString()
        }
    }
}

@Suppress("UNCHECKED_CAST")
class PreferenceDelegate<out T : Preference> : ReadOnlyProperty<PreferenceFragmentCompat, T> {
    private var cached: T? = null
    override fun getValue(thisRef: PreferenceFragmentCompat, property: KProperty<*>): T {
        if (cached == null)
            cached = thisRef.findPreference(property.name) as T
        return cached!!
    }
}