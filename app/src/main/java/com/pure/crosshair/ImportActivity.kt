package com.pure.crosshair

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * A window-less activity whose only job is to run the system image picker.
 *
 * A Service cannot use registerForActivityResult, so importing from the floating panel goes
 * through here. It reads the picked image immediately and copies it into app storage, which
 * means no persistable URI grant and no storage permission are needed.
 */
class ImportActivity : AppCompatActivity() {

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }

            val stored = Library(this).import(uri)
            if (stored == null) {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            } else {
                // Newly imported images become the active crosshair straight away.
                Prefs(this).selected = stored
                Bridge.overlay?.onLibraryChanged()
                Bridge.refresh()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            runCatching { picker.launch(arrayOf("image/*")) }
                .onFailure {
                    Toast.makeText(this, R.string.import_no_picker, Toast.LENGTH_LONG).show()
                    finish()
                }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
