package com.spqr.armour.utils

import java.io.File

fun File.cleanDirectory() {
    if (this.isDirectory) {
        this.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                file.cleanDirectory() // Recursive call for subdirectories
            }
            file.delete() // Delete files and empty directories
        }
    }
    this.delete()
}