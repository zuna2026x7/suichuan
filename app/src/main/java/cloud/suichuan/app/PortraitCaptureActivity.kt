package cloud.suichuan.app

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * QR scanner locked to portrait. The library's stock CaptureActivity follows
 * the sensor and opens landscape, which is awkward when the user is holding
 * the phone upright; this subclass exists only so the manifest can pin it to
 * portrait (see AndroidManifest.xml). All behavior is the library's.
 */
class PortraitCaptureActivity : CaptureActivity()
