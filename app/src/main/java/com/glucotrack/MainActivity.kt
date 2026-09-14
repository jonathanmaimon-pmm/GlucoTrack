package com.glucotrack

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.glucotrack.ui.GlucoTrackApp
import com.glucotrack.ui.GlucoTrackTheme

/**
 * Hosts the UI and owns the NFC reader session.
 *
 * Reader mode stays armed for the whole time the app is in the foreground, so taking a reading is
 * just holding the phone against the sensor — there is no button to find, which matters when one
 * hand is holding the phone and the other is holding a sleeve out of the way.
 */
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val viewModel: MainViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            GlucoTrackTheme {
                GlucoTrackApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this,
            this,
            // The Libre is an ISO 15693 tag. NDEF checks and the platform tag sound are both
            // skipped: the sensor carries no NDEF payload, and a chirp on every retry is noise.
            NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    /** Called on a binder thread when a tag enters the field. */
    override fun onTagDiscovered(tag: Tag) {
        viewModel.onTagDiscovered(tag)
    }
}
