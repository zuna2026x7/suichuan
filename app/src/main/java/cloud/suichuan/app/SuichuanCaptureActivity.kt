package cloud.suichuan.app

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * QR scanner that follows the phone's current orientation: held in portrait
 * it scans in portrait, held in landscape it scans in landscape. The library's
 * stock CaptureActivity declares sensorLandscape in its manifest, which kept
 * flipping the scanner to landscape; this subclass exists only so our
 * manifest declaration can use plain "sensor" orientation instead (see
 * AndroidManifest.xml). We never force either direction. All behavior is the
 * library's.
 */
class SuichuanCaptureActivity : CaptureActivity()
