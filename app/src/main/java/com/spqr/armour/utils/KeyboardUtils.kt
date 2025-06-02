package com.spqr.armour.utils

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager

object KeyboardUtils {
    /**
     * Hides the soft keyboard from the current focused view
     */
    fun hideKeyboard(activity: Activity) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusView = activity.currentFocus
        
        if (currentFocusView != null) {
            imm.hideSoftInputFromWindow(currentFocusView.windowToken, 0)
            currentFocusView.clearFocus()
        } else {
            // If no view has focus, try to hide keyboard from the activity's window
            val decorView = activity.window.decorView
            imm.hideSoftInputFromWindow(decorView.windowToken, 0)
        }
    }
} 