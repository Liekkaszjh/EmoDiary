package com.example.emotiondiary

import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.emotiondiary.databinding.ActivityMainBinding
import com.example.emotiondiary.main.AccountFragment
import com.example.emotiondiary.main.CalendarFragment
import com.example.emotiondiary.main.InsightFragment
import com.example.emotiondiary.main.RecordFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowScreenCapture()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_record -> show(RecordFragment())
                R.id.nav_insight -> show(InsightFragment())
                R.id.nav_calendar -> show(CalendarFragment())
                R.id.nav_account -> show(AccountFragment())
            }
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_record
    }

    override fun onResume() {
        super.onResume()
        allowScreenCapture()
    }

    private fun show(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainContainer, fragment)
            .commit()
    }

    private fun allowScreenCapture() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(true)
        }
    }
}
