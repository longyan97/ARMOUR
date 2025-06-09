package com.spqr.armour

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class ArmourAboutActivity : AppCompatActivity() {

    private lateinit var githubButton: Button
    private lateinit var paperButton: Button
    private lateinit var backButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force light mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        
        setContentView(R.layout.activity_armour_about)
        
        // Initialize buttons
        githubButton = findViewById(R.id.btnGithubProject)
        paperButton = findViewById(R.id.btnResearchPaper)
        backButton = findViewById(R.id.btnBack)
        
        // Set up click listeners
        githubButton.setOnClickListener {
            openUrl("https://github.com/longyan97/ARMOUR")
        }
        
        paperButton.setOnClickListener {
            openUrl("https://yanlong.site/files/wisec25-armour.pdf")  // Update this to the actual paper URL when available
        }
        
        backButton.setOnClickListener {
            finish()
        }
    }
    
    /**
     * Open URL in browser
     */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            // Handle error if no browser app available
            android.widget.Toast.makeText(this, "Unable to open URL", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
} 