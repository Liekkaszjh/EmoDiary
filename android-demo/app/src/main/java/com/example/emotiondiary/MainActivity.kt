package com.example.emotiondiary

import android.os.Bundle
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

    private fun show(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainContainer, fragment)
            .commit()
    }
}
